terraform {
  # Lower bound: `nullable` and cross-variable `validation` both need 1.9. Upper bound because a
  # major release may change language semantics, and this config is applied rarely enough that
  # nobody would be watching when it did.
  required_version = ">= 1.9.0, < 2.0.0"

  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.68"
    }
    # Object Storage buckets, Hetzner having no Cloud API for them: its own S3 guide calls
    # third-party providers "the only supported method" and links a workflow using this one.
    # `-tfstate` stays hand-made regardless — a state backend cannot be managed by the state it
    # holds (README.md). **Its first install is trust-on-first-use, and the lock file closes that:**
    # the OpenTofu registry holds no signing key, so `tofu init` reports "Signature validation was
    # skipped", and `.terraform.lock.hcl` records the version and 26 hashes that every later init is
    # checked against. **A supply-chain control rather than a convenience, and it must stay
    # committed** — #443's argument for pinning actions to a SHA.
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
  # From MINIO_ENDPOINT / MINIO_USER / MINIO_PASSWORD, which .envrc.example exports from the same
  # Keychain entries the S3 backend uses — the same credential under the names this provider reads,
  # not a second one to store and rotate. Three settings are not optional against Hetzner:
  #
  #   minio_region   Hetzner enforces it in the request signature and REJECTS a mismatch; the
  #                  default `us-east-1` fails every call with what reads like bad credentials.
  #   minio_ssl      Defaults to false. Hetzner is HTTPS only.
  #   s3_compat_mode Skips MinIO-specific admin calls this backend does not implement — the flag
  #                  that makes a MinIO provider usable against something that is not MinIO.
  #
  # `minio_server` is Required in the schema, so it lives here even though the provider would also
  # read MINIO_ENDPOINT: `tofu validate` fails without it, and a config CI cannot validate without a
  # shell environment is one CI cannot check. **Host and optional port, no scheme.**
  minio_server   = var.object_storage_endpoint
  minio_region   = var.object_storage_region
  minio_ssl      = true
  s3_compat_mode = true
}
