terraform {
  backend "s3" {
    bucket = "event-junkie-tfstate"
    key    = "production/terraform.tfstate"
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
    skip_s3_checksum            = true

    # See infra/bootstrap/backend.tf — unverified on Ceph, and the same answer applies here.
    # use_lockfile = true
  }
}
