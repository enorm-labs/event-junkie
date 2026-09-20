terraform {
  # Hetzner Object Storage speaks S3 through Ceph, close enough for this backend and different
  # enough to need every flag here; infra/README.md has what each works around, and why the bucket
  # is the one hand-made resource.
  backend "s3" {
    bucket = "event-junkie-tfstate"
    key    = "bootstrap/terraform.tfstate"
    # The bucket's location, not the servers'. Buckets cannot be moved; do not change this to follow
    # a server move.
    region = "fsn1"

    endpoints = {
      s3 = "https://fsn1.your-objectstorage.com"
    }

    use_path_style              = true
    skip_credentials_validation = true
    skip_region_validation      = true
    skip_requesting_account_id  = true
    skip_metadata_api_check     = true

    # Ceph does not implement the trailing-checksum header AWS added in 2025; without this every write
    # fails with a signature mismatch that reads like a credentials problem.
    skip_s3_checksum = true

    # UNVERIFIED ON CEPH: S3-native locking needs conditional writes (If-None-Match). Turn it on, run
    # two applies at once, write the answer into README.md (PLATFORM_SETUP.md §10, step 4). Until
    # then, one operator at a time.
    # use_lockfile = true
  }
}
