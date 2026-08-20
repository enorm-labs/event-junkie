terraform {
  # Lower bound: `nullable` and cross-variable `validation` both need 1.9. Upper bound because
  # a major release may change language semantics, and this config is applied rarely enough
  # that nobody would be watching when it did.
  required_version = ">= 1.9.0, < 2.0.0"

  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.68"
    }
    # Object Storage buckets, because Hetzner has no Cloud API for them. Their own S3 guide says
    # third-party providers are "the only supported method" and then links a workflow using this
    # one; the provider in turn names Hetzner Object Storage among its tested S3-compatible
    # backends, so this is the documented path from both ends rather than an improvisation.
    #
    # `-tfstate` stays hand-made regardless — a state backend cannot be managed by the state it
    # holds. This stops the exception growing, it does not remove it (README.md).
    minio = {
      source  = "aminueza/minio"
      version = "~> 3.0"
    }
  }
}

provider "hcloud" {
  # From HCLOUD_TOKEN. Never written to a file — the gitleaks pre-commit hook would catch it, but
  # the reason not to is that the state file is shared and the token is not.
}

provider "minio" {
  # From MINIO_ENDPOINT / MINIO_USER / MINIO_PASSWORD, which .envrc.example exports from the same
  # Keychain entries the S3 backend already uses — the same credential under the names this
  # provider reads, not a second one to store and rotate.
  #
  # Three settings are not optional against Hetzner, and each fails in its own way:
  #
  #   minio_region   Hetzner enforces the region in the request signature and REJECTS a request
  #                  signed with the wrong one. The provider's default is `us-east-1`, so leaving
  #                  it unset fails every call with a signature error that reads like bad
  #                  credentials.
  #   minio_ssl      Defaults to false. Hetzner is HTTPS only.
  #   s3_compat_mode Skips MinIO-specific admin calls the backend does not implement, instead of
  #                  erroring on them. This is the flag that makes a MinIO provider usable against
  #                  something that is not MinIO.
  #
  # `minio_server` is Required in the provider schema, so it lives here even though the provider
  # would also read MINIO_ENDPOINT — `tofu validate` fails without it, and a config that cannot be
  # validated without a shell environment is one CI cannot check. **Host and optional port, no
  # scheme**, unlike the `AWS_ENDPOINT_URL_S3` next to it in .envrc.example, which carries one.
  minio_server   = var.object_storage_endpoint
  minio_region   = var.object_storage_region
  minio_ssl      = true
  s3_compat_mode = true
}
