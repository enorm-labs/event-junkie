terraform {
  # Hetzner Object Storage speaks the S3 API through Ceph, which is close enough to S3 for this
  # backend and different enough to need every one of these flags. See infra/README.md for what
  # each one is working around, and for why the bucket itself is the one hand-made resource in
  # this repository.
  backend "s3" {
    bucket = "event-junkie-tfstate"
    key    = "bootstrap/terraform.tfstate"
    # The *bucket's* location, not the servers'. Buckets cannot be moved after creation; servers
    # can, and traffic between them is free anywhere in eu-central. Do not change this to follow a
    # server move — it would point at a bucket that does not exist.
    region = "fsn1"

    endpoints = {
      s3 = "https://fsn1.your-objectstorage.com"
    }

    use_path_style              = true
    skip_credentials_validation = true
    skip_region_validation      = true
    skip_requesting_account_id  = true
    skip_metadata_api_check     = true

    # Ceph does not implement the trailing-checksum header AWS added in 2025. Without this every
    # write fails with a signature mismatch that reads like a credentials problem and is not.
    skip_s3_checksum = true

    # UNVERIFIED ON CEPH. S3-native locking needs conditional writes (If-None-Match), which Hetzner
    # has not confirmed. Turn it on, run two applies at once, and write the answer into README.md
    # (PLATFORM_SETUP.md §10, step 4). Until then: one operator at a time.
    # use_lockfile = true
  }
}
