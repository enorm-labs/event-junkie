# Object Storage buckets that CAN be declared.
#
# `-tfstate` is not here and never will be: a state backend cannot be managed by the state it holds
# (README.md §"Why the state bucket is hand-made"). `-backups` is not here either, for a smaller
# reason — it already exists, so adopting it means `tofu import`, which is a deliberate act rather
# than a side effect of an observability issue. #586 decides that separately.

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
