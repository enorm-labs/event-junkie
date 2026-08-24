variable "primary_domain" {
  description = "The domain the product is served on."
  type        = string
  default     = "event-junkie.de"
  nullable    = false
}

variable "defensive_domains" {
  description = <<-EOT
    Domains registered defensively. They get a zone and the anti-spoofing records, and nothing else
    — no address record, because nothing is served on them.

    Pointing `event-junkie.com` at the node as a 301 is go-live work (PLATFORM_SETUP.md §10, step
    23), not provisioning: a redirect needs a certificate, which needs the name to resolve, which
    needs the site to be up. Registering the name is what stops a squatter; serving it is a
    separate decision with duplicate-content consequences ADR-014 would rather not have.
  EOT
  type        = list(string)
  default     = ["event-junkie.com"]
  nullable    = false
}

variable "mail_host" {
  description = <<-EOT
    The Hetzner webhosting server the role mailboxes live on, as an **absolute** name.

    The trailing dot is not decoration: without it the value is read as relative and the MX becomes
    `www750.your-server.de.event-junkie.de.`, which resolves to nothing and bounces every message.

    It is account-specific — ordered 2026-08-21, this account landed on `www750` — and it moves if
    Hetzner ever migrates the hosting. A variable rather than a literal because the MX below and the
    SPF that follows it (#274) both have to move together when that happens.
  EOT
  type        = string
  default     = "www750.your-server.de."
  nullable    = false

  validation {
    condition     = endswith(var.mail_host, ".")
    error_message = "mail_host must end in a dot, or the MX target is treated as relative to the zone."
  }
}

variable "dns_ttl" {
  description = "Default TTL. 300s while records are still moving; raise it once things settle (#259, step 7)."
  type        = number
  default     = 300
  nullable    = false
}

variable "ssh_public_keys" {
  description = <<-EOT
    Admin SSH public keys, by name. Registered with Hetzner once here and referenced by every
    environment, so adding a laptop is one edit rather than one per environment.

    Public keys only — nothing here is secret.
  EOT
  type        = map(string)
  nullable    = false
}

variable "object_storage_endpoint" {
  description = <<-EOT
    Object Storage endpoint the MinIO provider talks to.

    **Host and optional port, with no scheme** — the provider takes `host[:port]` while
    `AWS_ENDPOINT_URL_S3` in `.envrc.example` carries `https://`. Passing the URL form here fails
    with a connection error rather than a validation one, which is the slower way to find out.

    Matches the region below and the S3 backend in `backend.tf`; traffic inside the `eu-central`
    network zone is free.
  EOT
  type        = string
  default     = "fsn1.your-objectstorage.com"
  nullable    = false

  validation {
    condition     = !can(regex("^https?://", var.object_storage_endpoint))
    error_message = "object_storage_endpoint is host[:port] with no scheme — drop the https:// prefix."
  }
}

variable "object_storage_region" {
  description = <<-EOT
    Region for Object Storage request signing, and the location the `-o2` bucket is created in.

    **Hetzner enforces this in the signature and rejects a request signed with the wrong region**,
    so a mismatch fails every call with a signature error that reads like bad credentials rather
    than like a wrong region. The MinIO provider's own default is `us-east-1`, which is never right
    here.

    `fsn1` matches the S3 backend in `backend.tf` and the `-backups` bucket. Buckets cannot be moved
    after creation, so this is chosen once and then load-bearing — `backend.tf` carries the same
    warning for the same reason.
  EOT
  type        = string
  default     = "fsn1"
  nullable    = false
}

variable "object_storage_bucket_backups" {
  description = <<-EOT
    Object Storage bucket for `wal-g`'s base backups and WAL segments (#270).

    Declared rather than clicked, for the reason `-o2` was (#586): the lifecycle rule on this bucket
    is what the privacy notice's **30-day backup claim** rests on once the node is down, and a
    control backing a statement made to data subjects should be visible in a diff rather than set in
    a console.

    **Adopted, not created.** The bucket has existed since 2026-08-10; `storage.tf` carries the
    `import` block and the argument for that form.
  EOT
  type        = string
  default     = "event-junkie-backups"
  nullable    = false

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$", var.object_storage_bucket_backups))
    error_message = "Bucket names are lower-case letters, digits and hyphens, 3-63 characters, not starting or ending with a hyphen."
  }
}

variable "backup_retention_backstop_days" {
  description = <<-EOT
    The hard ceiling on how long a database backup can survive, enforced by a bucket lifecycle rule
    rather than by anything running on the node (#586).

    **This is a second copy of a number that lives in another stack**, and the duplication is
    unavoidable rather than sloppy: the sweep's window is
    `environments/*`'s `backup_retention_days`, and a bootstrap-stack lifecycle rule cannot read a
    variable from an environment stack. Same shape as `networkPolicy.databaseCidr` against
    `database.host` in the chart. **If one moves, move the other**, and re-check the privacy notice,
    which states both figures.

    **35 rather than 30, and the five days are load-bearing.** The sweep runs
    `wal-g delete before FIND_FULL`, which finds the last *full* backup before the cutoff and
    deletes only what precedes it — so it deliberately keeps a backup older than 30 days whenever
    that backup is what makes the rest of the window restorable. With a daily base backup that puts
    the real window at about 31 days on a healthy schedule. A rule set at exactly 30 would expire
    that base backup while the WAL segments depending on it survived, which is not an expiry but an
    unrestorable gap — and the restore drill (#270) would not find it, because it tests a recent
    backup.

    **35 rather than 90, which is what the `-o2` backstop uses.** That one is a floor under a
    control the notice actually rests on, so its distance from the real figure costs nothing. This
    one *is* the guarantee whenever the node is down, so every day of slack is a day the notice has
    to admit to. The notice states 30 in the ordinary case and 35 as the ceiling.
  EOT
  type        = number
  default     = 35
  nullable    = false

  validation {
    condition     = var.backup_retention_backstop_days > 30
    error_message = "The backstop must sit above the sweep's 30-day window, or it expires the base backup that FIND_FULL deliberately keeps and breaks the restore chain."
  }
}

variable "object_storage_bucket_o2" {
  description = <<-EOT
    Object Storage bucket for OpenObserve's Parquet files (#271, ADR-015).

    Declared rather than clicked, unlike `-tfstate` and `-backups`. The reason is not tidiness: the
    retention policy that will live on this bucket is what the privacy notice's log-retention claim
    depends on (LEGAL.md §7.5), and a control backing a statement made to data subjects should be
    visible in a diff rather than set in a console.
  EOT
  type        = string
  default     = "event-junkie-o2"
  nullable    = false

  validation {
    # Buckets are global to the project and cannot be renamed, so a typo here is a stray bucket
    # somebody has to notice and delete by hand.
    condition     = can(regex("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$", var.object_storage_bucket_o2))
    error_message = "Bucket names are lower-case letters, digits and hyphens, 3-63 characters, not starting or ending with a hyphen."
  }
}
