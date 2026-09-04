variable "environment" {
  description = "Environment name. Prefixes every resource name and lands in the `environment` label."
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,20}$", var.environment))
    error_message = "environment must be lowercase alphanumeric with hyphens, 2-21 characters."
  }
}

variable "location" {
  description = <<-EOT
    Hetzner location for both nodes and their Primary IPs.

    **The cost boundary is the network zone, not the location.** Hetzner charges nothing for
    internal traffic within `eu-central`, so `fsn1`, `nbg1` and `hel1` are interchangeable on
    price — including reaching the Object Storage buckets, which live in `fsn1` and cannot be
    moved. They do not pin the servers.

    What must stay together is the k3s node and the PostgreSQL node, which this variable
    guarantees by covering both: every query crosses that link, so inter-location latency would
    land on every request. Prefer a German location over Helsinki for a Berlin audience.

    **Deliberately not derived from live availability.** It would be easy to look up where capacity
    exists and pick automatically, and it would be a mistake: `location` forces replacement on
    servers *and* Primary IPs, so the moment Hetzner's stock shifted, an unrelated apply would plan
    to destroy and rebuild the environment — and move its public addresses out from under DNS.
    Moving location is a one-line edit that shows up in a plan and gets read. Keep it that way; use
    `check-capacity.sh` to decide, then change it on purpose.
  EOT
  type        = string
  default     = "fsn1"
  nullable    = false

  validation {
    # Changing location without changing the zone produces a subnet the servers cannot attach to,
    # and silently forfeits the free-traffic property above.
    condition     = var.network_zone != "eu-central" || contains(["fsn1", "nbg1", "hel1"], var.location)
    error_message = "With network_zone = \"eu-central\", location must be fsn1, nbg1 or hel1. Moving outside the zone means changing network_zone too — and losing free internal traffic to the Object Storage buckets, which stay in fsn1."
  }
}

variable "network_zone" {
  description = "Network zone the subnet lives in. `fsn1` and `nbg1` are both in `eu-central`."
  type        = string
  default     = "eu-central"
  nullable    = false
}

variable "network_ip_range" {
  description = "Private network range."
  type        = string
  default     = "10.0.0.0/16"
  nullable    = false
}

variable "subnet_ip_range" {
  description = "Subnet inside `network_ip_range` the servers attach to."
  type        = string
  default     = "10.0.1.0/24"
  nullable    = false
}

variable "image" {
  description = "OS image. Hetzner resolves the name against the server type's architecture, so this is the same string for arm64 and amd64."
  type        = string
  default     = "ubuntu-24.04"
  nullable    = false
}

# ---------------------------------------------------------------------------
# Nodes
# ---------------------------------------------------------------------------

variable "k3s_server_type" {
  description = "Server type for the k3s node. CAX21 (4 ARM vCPU / 8 GB) in production — see PLATFORM_SETUP.md §1 for the memory arithmetic."
  type        = string
  nullable    = false
}

variable "k3s_private_ip" {
  description = "Fixed private address for the k3s node. Fixed rather than allocated, so cloud-init can be templated without a dependency cycle between the two servers."
  type        = string
  default     = "10.0.1.10"
  nullable    = false
}

variable "postgres_server_type" {
  description = <<-EOT
    Server type for a dedicated PostgreSQL node, or `null` to co-locate PostgreSQL on the k3s node.

    Production uses a separate node. Staging sets this to `null` and runs both on one CAX11 — the
    topology stays honest either way, because the applications reach PostgreSQL over the network at
    a private address in both cases, never over a socket.
  EOT
  type        = string
  default     = null
}

variable "postgres_private_ip" {
  description = "Fixed private address for the dedicated PostgreSQL node. Ignored when `postgres_server_type` is null."
  type        = string
  default     = "10.0.1.20"
  nullable    = false
}

variable "postgres_public_ipv4" {
  description = <<-EOT
    Whether the dedicated PostgreSQL node gets a public IPv4.

    Defaults to false: the node is IPv6-only and reaches the internet over IPv6 for `apt` and for
    the wal-g release. Flip this to true and re-apply if either fetch fails; the address attaches in
    seconds. Do not pre-emptively route egress through the k3s node.

    **The apt half of this is settled and the GitHub half is not, in opposite directions.**
    `apt.postgresql.org` answers on IPv6 (via Fastly), so PLATFORM_SETUP.md §1's "one unresolved
    assumption" resolves in the design's favour. But **github.com publishes no AAAA record at all**
    — checked 2026-08-18 — and wal-g ships only as a GitHub release, so on a node with no public
    IPv4 `backups.sh` cannot fetch its binary and stops the boot saying so (#270). Production
    therefore has to set this true, buy egress another way, or accept a node without backups, and
    the third is not an option. ~€0.50/month is the cheap answer; the NAT-gateway alternative is in
    AGENTS.md and is only worth building if the address itself is the problem.
  EOT
  type        = bool
  default     = false
  nullable    = false
}

variable "postgres_volume_size" {
  description = <<-EOT
    Size in GB of the volume PGDATA lives on.

    10 is Hetzner's minimum and about €0.44/month. Deliberately the floor: growing a volume is an
    online operation (`hcloud volume resize` plus `resize2fs`), shrinking one is not possible at
    all, so the cheap direction is the only one available and there is nothing to buy in advance.

    This is a durability decision, not a capacity one — see PLATFORM_SETUP.md §1. The database
    outgrowing the node's local disk was never the problem; the node being replaced was.
  EOT
  type        = number
  default     = 10
  nullable    = false

  validation {
    condition     = var.postgres_volume_size >= 10
    error_message = "Hetzner's minimum volume size is 10 GB."
  }
}

variable "postgres_version" {
  description = "PostgreSQL major version, installed from the PGDG apt repository."
  type        = number
  default     = 18
  nullable    = false
}

variable "k3s_version" {
  description = <<-EOT
    Pinned k3s version, e.g. `v1.36.3+k3s1`.

    Pinned deliberately: `get.k3s.io` without `INSTALL_K3S_VERSION` installs whatever is current at
    boot, which would mean a destroy/apply cycle silently produces a different cluster than the one
    it replaced. That is the opposite of what this issue is for.

    Watched weekly by `.github/workflows/node-pin-reminder.yml` against the `update.k3s.io` stable
    channel, because no manifest declares this string and no ecosystem owns it. It opens an issue and
    never a pull request: `user_data` is force-new, so a bump replaces the node.
  EOT
  type        = string
  default     = "v1.36.3+k3s1"
  nullable    = false
}

variable "k3s_extra_tls_sans" {
  description = "Additional TLS SANs for the Kubernetes API certificate, beyond the addresses this module already knows. Hostnames you might point a kubeconfig at."
  type        = list(string)
  default     = []
  nullable    = false
}

# ---------------------------------------------------------------------------
# Access
# ---------------------------------------------------------------------------

variable "ssh_key_ids" {
  description = "Hetzner SSH key IDs to inject at build time. Produced by the bootstrap stack."
  type        = list(string)
  nullable    = false
}

variable "ssh_public_keys" {
  description = <<-EOT
    The same keys as OpenSSH `authorized_keys` lines, for the unprivileged `ops` user.

    Hetzner injects `ssh_key_ids` into **root**, and cloud-init then disables root login — so
    without these you would lock yourself out of a freshly built node. The module refuses to build
    with an empty list for exactly that reason.
  EOT
  type        = list(string)
  nullable    = false

  validation {
    condition     = length(var.ssh_public_keys) > 0
    error_message = "At least one public key is required, or cloud-init's SSH hardening locks the node out."
  }
}

variable "admin_cidrs" {
  description = <<-EOT
    Source ranges allowed to reach SSH (22) and the Kubernetes API (6443) on the public interface.

    **Bootstrap value, not a daily-use one** (PLATFORM_SETUP.md §8a). The very first apply happens
    before WireGuard is running, so the firewall has to admit *somewhere* long enough to bring the
    tunnel up:

        ADMIN="[\"$(curl -s https://ifconfig.me)/32\",\"$(dig +short myip.opendns.com @resolver1.opendns.com | tail -1)/32\"]"
        tofu apply -var "admin_cidrs=$ADMIN"

    Two addresses because a corporate HTTP proxy egresses from a different one than unproxied SSH
    and WireGuard do; an allowlist built from `ifconfig.me` alone refuses the connection it was
    meant to admit.

    Once the tunnel works this can go back to `[]`, which removes both rules entirely and leaves
    22 and 6443 unreachable from the internet at any address.
  EOT
  type        = list(string)
  default     = []
  nullable    = false
}

variable "wireguard_peers" {
  description = <<-EOT
    WireGuard clients. Public keys only — the *server's* keypair is generated on the node at first
    boot and never leaves it, so no private key is ever written to the state file.

    `address` must be a /32 inside `wireguard_subnet` and unique per peer.
  EOT
  type = list(object({
    name       = string
    public_key = string
    address    = string
  }))
  default  = []
  nullable = false

  # These three checks exist because every one of them fails *silently and late* otherwise: the
  # node boots, `wg-quick` fails or the tunnel comes up useless, and by then the firewall admits
  # nobody — no 22, no 6443, and on staging no 80/443 either. The only way back in is Hetzner's
  # browser console. A plan-time error costs seconds; that costs an evening.
  validation {
    condition = alltrue([
      for peer in var.wireguard_peers : can(regex("^[A-Za-z0-9+/]{43}=$", peer.public_key))
    ])
    error_message = "Each wireguard_peers[*].public_key must be a WireGuard key: base64, exactly 44 characters, ending in '='. An SSH public key ('ssh-ed25519 AAAA…') is a different format and will not work — generate one with `wg genkey | wg pubkey`, or copy it from the WireGuard app."
  }

  validation {
    condition = alltrue([
      for peer in var.wireguard_peers :
      can(cidrhost("${peer.address}/32", 0)) &&
      cidrhost("${peer.address}/${split("/", var.wireguard_subnet)[1]}", 0) == cidrhost(var.wireguard_subnet, 0)
    ])
    error_message = "Each wireguard_peers[*].address must be a plain IP inside wireguard_subnet. Production and staging use different tunnel subnets on purpose, so a peer copied between them lands here."
  }

  validation {
    condition     = length(distinct([for peer in var.wireguard_peers : peer.address])) == length(var.wireguard_peers)
    error_message = "wireguard_peers[*].address must be unique. Two peers on one address means whichever connects second silently displaces the first."
  }
}

variable "wireguard_subnet" {
  description = "Tunnel network. The node takes the first usable address; peers take the rest."
  type        = string
  default     = "10.10.0.0/24"
  nullable    = false
}

variable "wireguard_port" {
  description = "UDP port WireGuard listens on. Open to the world by design — WireGuard does not answer unauthenticated packets, so it is silent to scanners (PLATFORM_SETUP.md §8a)."
  type        = number
  default     = 51820
  nullable    = false
}

# ---------------------------------------------------------------------------
# Exposure and lifecycle
# ---------------------------------------------------------------------------

variable "public_web" {
  description = <<-EOT
    Whether 80/443 are open to the internet on the k3s node.

    False for staging, which is not on the public internet at all (PLATFORM_SETUP.md §4a): no
    public A record, no public 80/443, ingress reached over the tunnel. That is also why staging's
    certificates have to use the DNS-01 challenge — HTTP-01 needs Let's Encrypt to reach the host,
    which is precisely what this prevents.
  EOT
  type        = bool
  default     = true
  nullable    = false
}

variable "enable_backups" {
  description = "Hetzner's automated daily backups. 20% of the server price; worth it in production, wasted on staging."
  type        = bool
  default     = false
  nullable    = false
}

variable "ip_delete_protection" {
  description = <<-EOT
    Delete protection on the Primary IPs — against the console, the API and any other tool.

    **It does not stop OpenTofu.** The provider lifts its own locks before destroying, so a
    `tofu destroy` here removes the addresses regardless. What actually keeps a rebuilt server on
    the same address is `auto_delete = false`, which is set unconditionally: deleting a server does
    not take its Primary IP with it, so DNS never churns across a rebuild.

    True in production, false in staging, because the value of the lock is proportional to how much
    a mis-click costs.
  EOT
  type        = bool
  default     = false
  nullable    = false
}

variable "postgres_volume_delete_protection" {
  description = <<-EOT
    Delete protection on the PGDATA volume — against the console, the API and any other tool.

    **It does not stop OpenTofu**, for exactly the reason `ip_delete_protection` does not: the
    provider lifts its own locks before destroying. A `tofu destroy` in the production directory
    takes the database with it, and nothing here prevents that. `lifecycle { prevent_destroy }`
    would, but it needs a literal and so cannot differ between environments while the volume lives
    in this shared module — and staging has to stay destroyable (#424).

    True in production, false in staging. What actually keeps the data across a *node rebuild* is
    that the volume is a resource in its own right, referenced by no server — and that is
    unconditional.
  EOT
  type        = bool
  default     = false
  nullable    = false
}

# ---------------------------------------------------------------------------
# Off-server backups — wal-g to Hetzner Object Storage (#270)
# ---------------------------------------------------------------------------
#
# The S3 access key and secret are deliberately absent from this file and from every other. They
# would reach the node through `user_data`, which is state, and nothing secret goes into state. The
# operator writes them to /etc/wal-g/credentials.env by hand — CLUSTER_BOOTSTRAP.md §8b.

variable "backup_bucket" {
  description = <<-EOT
    Object Storage bucket holding wal-g's WAL and base backups.

    **Declared in `bootstrap/`, not created here and no longer made by hand** (#586). Hetzner has no
    Cloud API for buckets, but the MinIO provider is the documented path and `bootstrap/` already
    carried it for `-o2`; that bucket's lifecycle rule is what enforces backup retention while this
    node is down. This variable is the *name* the node writes to, and it must match
    `object_storage_bucket_backups` over there — two stacks cannot share a variable.

    Shared by both environments and separated by a prefix derived from `environment`, since the
    subscription is billed per account and a second bucket would buy nothing. **The lifecycle rule
    is therefore bucket-wide and applies to both**, which is correct only while both carry the same
    retention window; they do.
  EOT
  type        = string
  default     = "event-junkie-backups"
  nullable    = false
}

variable "backup_endpoint" {
  description = <<-EOT
    S3 endpoint wal-g pushes to.

    `fsn1` regardless of where the servers run: traffic inside the `eu-central` network zone is
    free, and buckets cannot be moved afterwards. It answers on IPv6, so the dedicated PostgreSQL
    node can reach it without a public IPv4 — unlike GitHub, see `postgres_public_ipv4`.
  EOT
  type        = string
  default     = "https://fsn1.your-objectstorage.com"
  nullable    = false
}

variable "backup_region" {
  description = "Region wal-g signs S3 requests for. Must match the bucket's location, like `region` in backend.tf."
  type        = string
  default     = "fsn1"
  nullable    = false
}

variable "backup_retention_days" {
  description = <<-EOT
    How far back point-in-time recovery reaches, in days.

    **This is a number the privacy notice has to state** (#277), not only an ops
    setting.

    **Enforced twice, and the second one is not in this stack.** This value drives the nightly
    `wal-g delete` sweep on the node. The ceiling that holds when the node is *not* running is
    `backup_retention_backstop_days` in `bootstrap/`, a lifecycle rule on the bucket itself (#586) —
    because a sweep cannot run on a machine that is off, and without it the window quietly became
    "forever" for the length of any outage.

    **The two numbers differ on purpose: 30 here, 35 there.** The sweep runs
    `delete before FIND_FULL`, which keeps the last full backup before the cutoff even when that
    backup is older than the window, because deleting it would break the chain the remaining WAL
    depends on. The bucket rule is not chain-aware, so it sits above that overshoot. Moving either
    number means moving both and re-checking the privacy notice, which states them both.

    30 days, decided 2026-08-18: long enough that corruption noticed weeks later is still
    recoverable, and a defensible figure to put in front of a data subject.
  EOT
  type        = number
  default     = 30
  nullable    = false

  validation {
    condition     = var.backup_retention_days >= 7
    error_message = "A retention window under a week cannot survive a quiet holiday, which is when it would matter."
  }
}

variable "walg_version" {
  description = <<-EOT
    wal-g release tag. Pinned rather than tracking latest, because an unattended boot is a poor place
    to meet a new major.

    **It moves together with `walg_checksums` or the node does not boot.** Watched weekly by
    `.github/workflows/node-pin-reminder.yml`, which opens an issue carrying both replacement values.
  EOT
  type        = string
  default     = "v3.0.8"
  nullable    = false
}

variable "walg_checksums" {
  description = <<-EOT
    SHA-256 of the `wal-g-pg-24.04-*.tar.gz` release assets, keyed by dpkg architecture.

    Pinned here rather than fetched from the host that served the tarball, which would verify only
    that the download completed. Both architectures are carried because production is ARM and
    staging is x86 (#424), and the node picks its own at boot.

    A `walg_version` bumped without these aborts cloud-init on a failed `sha256sum -c`, and nothing
    in CI boots a node, so no check reports it. That is why no updater is allowed to propose one.
  EOT
  type        = map(string)
  default = {
    amd64 = "ce382535fb3a59f07fd3ae02e96a039764ac490de49abfb3564fec36d65fafbc"
    arm64 = "6789fcaecef1b3e0bfdf9d494460fa301f9c9afcf055d25ecc73a769d87dc156"
  }
  nullable = false

  validation {
    condition     = alltrue([for arch in ["amd64", "arm64"] : can(var.walg_checksums[arch])])
    error_message = "Both amd64 and arm64 checksums are required; the node picks one at boot and neither environment may be the odd one out."
  }
}
