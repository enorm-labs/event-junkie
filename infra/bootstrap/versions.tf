terraform {
  # Lower bound: `nullable` and cross-variable `validation` need 1.9. Upper bound because a major
  # may change language semantics, and this config is applied rarely enough that nobody would be
  # watching.
  required_version = ">= 1.9.0, < 2.0.0"

  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.68"
    }
    # Object Storage buckets, Hetzner having no Cloud API for them; its own S3 guide calls third-party
    # providers "the only supported method". `-tfstate` stays hand-made regardless (README.md). The
    # OpenTofu registry holds no signing key for this provider, so `tofu init` reports "Signature
    # validation was skipped" and `.terraform.lock.hcl` is the supply-chain control: it records the
    # version and 26 hashes every later init is checked against, and must stay committed (#443).
    minio = {
      source  = "aminueza/minio"
      version = "~> 3.0"
    }
  }
}

provider "hcloud" {
  # From HCLOUD_TOKEN. Never written to a file: the state file is shared and the token is not.
}

provider "minio" {
  # From MINIO_ENDPOINT / MINIO_USER / MINIO_PASSWORD, the same Keychain credential the S3 backend
  # uses under the names this provider reads. Three settings are not optional against Hetzner:
  #
  #   minio_region   enforced in the request signature; the default `us-east-1` fails every call
  #                  with what reads like bad credentials.
  #   minio_ssl      defaults to false, and Hetzner is HTTPS only.
  #   s3_compat_mode skips the MinIO-specific admin calls this backend does not implement.
  #
  # `minio_server` is Required in the schema, so it lives here although the provider would read
  # MINIO_ENDPOINT: `tofu validate` fails without it. Host and optional port, no scheme.
  minio_server   = var.object_storage_endpoint
  minio_region   = var.object_storage_region
  minio_ssl      = true
  s3_compat_mode = true
}
