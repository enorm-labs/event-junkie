# Object Storage buckets that CAN be declared.
#
# `-tfstate` is not here and never will be: a state backend cannot be managed by the state it holds
# (README.md §"Why the state bucket is hand-made"). Both others are, `-o2` per #271 and `-backups`
# per #586.

# Adopting the bucket that already exists, rather than creating one.
#
# **`event-junkie-o2` was made by hand, in the same minute as `-tfstate` and `-backups`** — all three
# by the same account. It was never a new bucket to create, and
# README.md said so in the present tense all along: "the other two buckets (`-o2` for OpenObserve,
# `-backups` for `wal-g`) _could_ be declared that way when their issues land". Their issue landed;
# the bucket was already sitting there.
#
# So the first apply stopped at `bucket already exists!`, and the provider refusing to take over a
# resource it did not create is the correct instinct rather than a bug — silently adopting a bucket
# it found by name is how you end up managing someone else's.
#
# **An `import` block rather than `tofu import` on the command line.** The CLI form is a state edit
# that happens immediately and leaves nothing behind to review; this one appears in `tofu plan` as
# an import before anything is written, is visible in the diff of this file, and would have to be
# deleted deliberately to stop applying. That is the same argument this configuration makes for
# declaring the bucket at all.
#
# **Safe to delete once applied.** OpenTofu treats a block whose target is already in state as a
# no-op, so leaving it costs nothing but a stale note; removing it in a later change is tidier. What
# must NOT happen is deleting the `resource` block below and leaving this one — that is a bucket
# nothing manages and nothing reports.
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
# **OpenObserve expires its own data** (`ZO_COMPACT_DATA_RETENTION_DAYS`, 14 days as of #271),
# deleting the Parquet and the file-list entry together. That is the control the privacy notice
# rests on, and it is deliberately not this rule: a lifecycle rule alone would delete objects out
# from under OpenObserve's file list, leaving queries pointing at files that no longer exist —
# corruption rather than expiry.
#
# **So why have this at all?** Because the compactor only runs while OpenObserve does. A pod that is
# down, crash-looping or misconfigured stops expiring anything, and the retention window quietly
# becomes "forever" — precisely the failure #586 describes for the backup sweep. This rule is the
# floor that holds when that happens.
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

# Adopted, not created — the same situation `-o2` was in, and for the same reason: all three buckets
# were made by hand. See the block above for why an `import` block beats
# `tofu import` on the command line, and why deleting the `resource` while leaving the `import` is
# the one move that must not happen.
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
# Unlike the `-o2` rule above, this is not a backstop under an application-level control. **Whenever
# the node is down, this rule IS the retention**, because the only other enforcement is the nightly
# `wal-g delete` sweep on that node, and a sweep cannot run on a machine that is off. That is the
# whole of #586: an outage silently extended the window, the longer the outage the further the drift,
# and nothing reported it.
#
# **Why 35 and not 30.** The sweep runs `wal-g delete before FIND_FULL <30 days ago>`, which finds
# the last *full* backup before the cutoff and deletes only what precedes it — so it deliberately
# keeps a backup older than 30 days whenever that backup is what makes the rest of the window
# restorable. With a daily base backup (`walg-basebackup.timer`) the real window is about 31 days. A
# rule at exactly 30 would delete that base backup while the WAL segments depending on it survived:
# not an expiry but an unrestorable gap at the oldest end, which #270's restore drill would not find,
# because a drill restores something recent.
#
# **Why 35 and not 90.** `-o2` can afford 90 because OpenObserve's own compactor is the control the
# notice rests on, so the backstop's distance costs nothing. Here there is no second control and
# every day of slack is a day the notice has to admit to. Five days buys the chain its margin and no
# more, which is why the notice states 30 in the ordinary case and 35 as the ceiling.
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
