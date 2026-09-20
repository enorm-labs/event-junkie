# Object Storage buckets that CAN be declared: `-tfstate` never will be, because a state backend
# cannot be managed by the state it holds (README.md §"Why the state bucket is hand-made").

# Adopting the bucket that already exists: `event-junkie-o2` was made by hand, so an apply without
# this stops at `bucket already exists!`. An `import` block rather than `tofu import`: the CLI form
# is an immediate state edit with nothing to review, this one shows in `tofu plan`. Safe to delete
# once applied. What must NOT happen is deleting the `resource` block below and leaving this one:
# a bucket nothing manages and nothing reports.
import {
  to = minio_s3_bucket.o2
  id = "event-junkie-o2"
}

resource "minio_s3_bucket" "o2" {
  bucket = var.object_storage_bucket_o2

  # Private, stated rather than defaulted: the bucket holds logs and metrics, which LEGAL.md §7.5
  # treats as capable of carrying personal data.
  acl = "private"

  # `false` so `tofu destroy` cannot silently take the observability history; emptying it first is
  # the deliberate step.
  force_destroy = false
}

# A backstop under OpenObserve's own retention, not the mechanism. OpenObserve expires its data
# (`ZO_COMPACT_DATA_RETENTION_DAYS`, 14 days per #271), Parquet and file-list entry together, and
# that is the control the privacy notice rests on: a lifecycle rule alone deletes objects out from
# under the file list, corruption rather than expiry. The compactor runs only while OpenObserve
# does, and a pod that is down expires nothing (#586's failure), so this is the floor that holds.
#
# 90 days, six times the application's 14, and the gap is the point: it must only catch a stalled
# compactor. Narrowing it toward 14 would delete files OpenObserve still has indexed.
#
# `abort-incomplete-uploads` comes first in both lifecycle resources because the server returns it
# first: `rule` is a positional list and Hetzner hands the rules back sorted, so a configuration
# leading with the expiry rule plans two in-place updates on every run, forever, measured on both
# buckets either side of an apply.
resource "minio_s3_bucket_lifecycle" "o2" {
  bucket = minio_s3_bucket.o2.bucket

  rule {
    id     = "abort-incomplete-uploads"
    status = "Enabled"

    # An interrupted upload leaves parts that are billed and invisible to a plain listing.
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  rule {
    id     = "backstop-expiry"
    status = "Enabled"

    expiration {
      days = 90
    }
  }
}

# --- the wal-g backup bucket, and the rule the privacy notice rests on ---------------------------

# Adopted, not created, as `-o2` above; the same rules about the `import` block apply.
import {
  to = minio_s3_bucket.backups
  id = "event-junkie-backups"
}

resource "minio_s3_bucket" "backups" {
  bucket = var.object_storage_bucket_backups

  # Private, and the stakes are higher than `-o2`: a physical copy of the entire database.
  acl = "private"

  # `false`: losing the backups in the action that loses the node is the failure they exist for.
  force_destroy = false
}

# The control that makes the privacy notice true when nothing of ours is running (#586). Whenever
# the node is down this rule IS the retention: the only other enforcement is the nightly `wal-g
# delete` sweep on that node, and an outage silently extends the window.
#
# Why 35 and not 30: the sweep runs `wal-g delete before FIND_FULL <30 days ago>`, keeping the last
# full backup before the cutoff, so the real window is about 31 days. A rule at exactly 30 would
# delete that base backup while the WAL depending on it survived, an unrestorable gap at the oldest
# end that a restore drill cannot find. Not 90 as `-o2` affords, because here there is no second
# control; the notice states 30 ordinarily and 35 as the ceiling.
#
# Duplicated across stacks by necessity: the sweep's window is `backup_retention_days` in
# `modules/environment`, and a bootstrap-stack rule cannot read it. Move one, move the other, and
# re-check both privacy notices.
resource "minio_s3_bucket_lifecycle" "backups" {
  bucket = minio_s3_bucket.backups.bucket

  # Ordered to match the server, as `o2` above.
  rule {
    id     = "abort-incomplete-uploads"
    status = "Enabled"

    # A base backup is large and multipart; an interrupted push leaves billed, invisible parts.
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  rule {
    id     = "retention-ceiling"
    status = "Enabled"

    expiration {
      days = var.backup_retention_backstop_days
    }
  }
}

# --- the cached venue image bucket, and the one that must never expire ----------------------------

# Created, not adopted: this bucket has never existed, so there is nothing to take over.
resource "minio_s3_bucket" "images" {
  bucket = var.object_storage_bucket_images

  # Private for a different reason: third-party material we serve, so a public bucket would publish
  # an origin we do not control the URLs of. The BFF is the only reader (ADR-019 §2.2).
  acl = "private"

  # `false`: losing these costs a refetch of every venue image, thousands of requests ADR-007
  # exists to avoid.
  force_destroy = false
}

# Deliberately no `minio_s3_bucket_lifecycle`: this bucket holds live content, and an expiry rule
# would delete an object out from under the page serving it (ADR-019 §2.7). An orphan sweep
# replaces the rule, and it must run under its own environment prefix: content-addressed keys mean
# staging computes the same key as production, so a sweep asking its own database about every key
# would delete the other environment's objects.

# --- the one public bucket: photographs we took ourselves -----------------------------------------

# Public, and the only bucket here that is. ADR-028 declined to open `-images` instead: "private
# because third-party" must not be widened to cover material that is ours, so the different rule
# lives in a different bucket.
resource "minio_s3_bucket" "images_own" {
  bucket = var.object_storage_bucket_own_images

  # World-readable the moment it lands: the importer fetches from this URL as from Commons, and a
  # credit link has to resolve for a visitor. Nothing but a reviewed photograph belongs in it.
  acl = "public-read"

  # `false`, and these files have no upstream to refetch from.
  force_destroy = false
}

# No lifecycle rule, for `-images`' reason and one more: the orphan sweep asks the database about
# cached derivatives, not sources, so an unneeded object here is deleted by a person (ADR-028
# § Consequences).
