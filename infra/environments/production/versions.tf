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
  }
}

provider "hcloud" {
  # From HCLOUD_TOKEN.
}
