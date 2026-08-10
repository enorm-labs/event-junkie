output "ssh_key_ids" {
  description = "Hetzner SSH key IDs. Feed these into each environment's `ssh_key_ids`."
  value       = [for key in hcloud_ssh_key.admin : key.id]
}

output "zone_names" {
  description = "Managed zone names."
  value       = [for zone in hcloud_zone.main : zone.name]
}

output "authoritative_nameservers" {
  description = <<-EOT
    Hetzner's nameservers for each zone. These are what INWX has to be pointed at — **after** the
    zone exists, never before: delegating to a zone that does not exist yet means the domain
    resolves to nothing.

    DNSSEC is a separate, later sitting. A DS record at INWX that does not match Hetzner's key
    makes the domain unresolvable, which is far worse than having no DNSSEC at all — so never
    change delegation and DS on the same day (#259).
  EOT
  value       = { for name, zone in hcloud_zone.main : name => zone.authoritative_nameservers }
}
