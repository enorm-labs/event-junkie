variable "primary_domain" {
  description = "The domain the product is served on."
  type        = string
  default     = "event-junkie.de"
  nullable    = false
}

variable "defensive_domains" {
  description = <<-EOT
    Domains registered defensively. They get a zone and the anti-spoofing records, and nothing else
    — no address record, because nothing is served on them.

    Pointing `event-junkie.com` at the node as a 301 is go-live work (PLATFORM_SETUP.md §10, step
    23), not provisioning: a redirect needs a certificate, which needs the name to resolve, which
    needs the site to be up. Registering the name is what stops a squatter; serving it is a
    separate decision with duplicate-content consequences ADR-014 would rather not have.
  EOT
  type        = list(string)
  default     = ["event-junkie.com"]
  nullable    = false
}

variable "dns_ttl" {
  description = "Default TTL. 300s while records are still moving; raise it once things settle (#259, step 7)."
  type        = number
  default     = 300
  nullable    = false
}

variable "ssh_public_keys" {
  description = <<-EOT
    Admin SSH public keys, by name. Registered with Hetzner once here and referenced by every
    environment, so adding a laptop is one edit rather than one per environment.

    Public keys only — nothing here is secret.
  EOT
  type        = map(string)
  nullable    = false
}
