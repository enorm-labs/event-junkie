# comment-lint: allow-file every bucket here carries why it exists and why it differs, and the file
# sat at 54% before the images bucket. A fourth bucket documented like the other three reaches the
# cap by arithmetic rather than by verbosity, and the alternative is deleting a reason.

# Object Storage buckets that CAN be declared.
#
# `-tfstate` is not here and never will be: a state backend cannot be managed by the state it holds
# (README.md §"Why the state bucket is hand-made"). Both others are, `-o2` per #271 and `-backups`
# per #586.

# Adopting the bucket that already exists, rather than creating one. `event-junkie-o2` was made by
# hand alongside `-tfstate` and `-backups`, so an apply without this stops at `bucket already
# exists!` — the provider refusing to take over a resource it did not create, which is the correct
# instinct rather than a bug.
#
# **An `import` block rather than `tofu import` on the command line.** The CLI form is a state edit
# that happens immediately and leaves nothing to review; this one shows in `tofu plan` before
# anything is written and has to be deleted deliberately to stop applying. **Safe to delete once
# applied** — a block whose target is already in state is a no-op. What must NOT happen is deleting
# the `resource` block below and leaving this one: a bucket nothing manages and nothing reports.
import {
  to = minio_s3_bucket.o2
  id = "event-junkie-o2"
}

resource "minio_s3_bucket" "o2" {
  bucket = var.object_storage_bucket_o2

  # Private, and stated rather than defaulted. The bucket holds logs and metrics: request paths,
  # error strings, and whatever a venue's HTML dragged into a stack trace. LEGAL.md §7.5 treats log
  # content as capable of carrying personal data, which makes a public bucket a disclosure rather
  # than an untidiness.
  acl = "private"

  # `false` so that `tofu destroy` cannot silently take the observability history with it. Emptying
  # it first is the deliberate step, and it should be deliberate.
  force_destroy = false
}

# A backstop under OpenObserve's own retention, not the mechanism itself.
#
# **OpenObserve expires its own data** (`ZO_COMPACT_DATA_RETENTION_DAYS`, 14 days per #271),
# deleting the Parquet and its file-list entry together. That is the control the privacy notice
# rests on, deliberately not this rule: a lifecycle rule alone deletes objects out from under
# OpenObserve's file list, which is corruption rather than expiry. **So why have it?** The compactor
# runs only while OpenObserve does, and a pod that is down expires nothing — the window quietly
# becomes "forever", the failure #586 describes for the backup sweep. This is the floor that holds.
#
# **90 days, six times the application's 14**, and the gap is the point: it must never be the thing
# that expires data in normal operation, only the thing that catches a stalled compactor. Narrowing
# it toward 14 would start deleting files OpenObserve still has indexed.
resource "minio_s3_bucket_lifecycle" "o2" {
  bucket = minio_s3_bucket.o2.bucket

  rule {
    id     = "backstop-expiry"
    status = "Enabled"

    expiration {
      days = 90
    }
  }

  rule {
    id     = "abort-incomplete-uploads"
    status = "Enabled"

    # An interrupted upload leaves parts that are billed and invisible to a plain listing. Nothing
    # here resumes one, so a week is generous.
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# --- the wal-g backup bucket, and the rule the privacy notice rests on ---------------------------

# Adopted, not created — same as `-o2`, and the block above has why an `import` block beats
# `tofu import`, and why deleting the `resource` while leaving the `import` must not happen.
import {
  to = minio_s3_bucket.backups
  id = "event-junkie-backups"
}

resource "minio_s3_bucket" "backups" {
  bucket = var.object_storage_bucket_backups

  # Private, stated rather than defaulted, and here the stakes are higher than for `-o2`: this
  # bucket holds a physical copy of the entire database. A public backup bucket is not a disclosure
  # of log fragments but of everything.
  acl = "private"

  # `false` so `tofu destroy` cannot take the backups with it. Losing the backups in the same action
  # that loses the node is the failure the backups exist for.
  force_destroy = false
}

# **The control that makes the privacy notice true when nothing of ours is running (#586).**
#
# Unlike the `-o2` rule above this is not a backstop under an application-level control. **Whenever
# the node is down, this rule IS the retention**: the only other enforcement is the nightly `wal-g
# delete` sweep on that node, and a sweep cannot run on a machine that is off. That is the whole of
# #586 — an outage silently extends the window and nothing reports it.
#
# **Why 35 and not 30.** The sweep runs `wal-g delete before FIND_FULL <30 days ago>`, keeping the
# last *full* backup before the cutoff, so with a daily base backup the real window is about 31 days.
# A rule at exactly 30 would delete that base backup while the WAL segments depending on it survived
# — an unrestorable gap at the oldest end, which #270's restore drill cannot find because a drill
# restores something recent. **Why not 90**, as `-o2` affords: there the compactor is the control the
# notice rests on, and here there is no second control, so every day of slack is a day the notice has
# to admit to. Five buys the chain its margin; the notice states 30 ordinarily and 35 as the ceiling.
#
# **The number is duplicated across stacks and cannot be otherwise.** The sweep's window is
# `backup_retention_days` in `modules/environment`; a bootstrap-stack rule cannot read it. Move one,
# move the other, and re-check both privacy notices — they state both figures.
resource "minio_s3_bucket_lifecycle" "backups" {
  bucket = minio_s3_bucket.backups.bucket

  rule {
    id     = "retention-ceiling"
    status = "Enabled"

    expiration {
      days = var.backup_retention_backstop_days
    }
  }

  rule {
    id     = "abort-incomplete-uploads"
    status = "Enabled"

    # A base backup is large and multipart. An interrupted push leaves parts that are billed and
    # invisible to a plain listing, and nothing here resumes one.
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# --- the cached venue image bucket, and the one that must never expire ----------------------------

# **Created, not adopted.** Its two neighbours above predate this configuration and carry `import`
# blocks. This bucket has never existed, so there is nothing to take over.
resource "minio_s3_bucket" "images" {
  bucket = var.object_storage_bucket_images

  # Private, and the reason differs from its neighbours. Theirs is disclosure; this bucket holds
  # third-party material we serve, so a public bucket would also publish an origin we do not
  # control the URLs of. The BFF streams from here and is the only reader (ADR-019 §2.2).
  acl = "private"

  # `false`, as for the other two. Losing these costs a refetch of every venue image rather than
  # data, but a refetch is thousands of requests to venues that ADR-007 exists to avoid making.
  force_destroy = false
}

# **There is deliberately no `minio_s3_bucket_lifecycle` here, and that is the point.**
#
# `-o2` and `-backups` expire because both hold history. This bucket holds live content, so an
# expiry rule would delete an object out from under the page serving it (ADR-019 §2.7).
#
# An orphan sweep replaces the rule: it asks the database whether anything still points at an
# object. Without it the bucket grows forever, so the sweep is load-bearing rather than tidy-up.
#
# The sweep must run under its own environment prefix. Content-addressed keys mean staging computes
# the same key as production, so a sweep asking its own database about every key would delete the
# other environment's objects — #270's shape, one bucket over.
