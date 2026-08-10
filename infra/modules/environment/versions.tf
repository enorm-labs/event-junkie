terraform {
  # Lower bound: `nullable` and cross-variable `validation` both need 1.9. Upper bound because
  # a major release may change language semantics, and this config is applied rarely enough
  # that nobody would be watching when it did.
  required_version = ">= 1.9.0, < 2.0.0"

  required_providers {
    hcloud = {
      source = "hetznercloud/hcloud"
      # DNS (`hcloud_zone`, `hcloud_zone_rrset`) went GA in 1.56.0, and 1.67.0
      # deprecated `datacenter` in favour of `location`. Do not drop below 1.68.
      version = "~> 1.68"
    }
  }
}
