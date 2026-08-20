# Object Storage buckets that CAN be declared.
#
# `-tfstate` is not here and never will be: a state backend cannot be managed by the state it holds
# (README.md §"Why the state bucket is hand-made"). `-backups` is not here either, for a smaller
# reason — it already exists, so adopting it means `tofu import`, which is a deliberate act rather
# than a side effect of an observability issue. #586 decides that separately.

# Adopting the bucket that already exists, rather than creating one (2026-08-20).
#
# **`event-junkie-o2` was made by hand on 2026-08-10, in the same minute as `-tfstate` and
# `-backups`** — all three at 19:43, by the same account. It was never a new bucket to create, and
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
