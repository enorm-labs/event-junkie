output "k3s_ipv4" {
  description = "Public IPv4 of the k3s node, and the value of the apex and `www` A records."
  value       = module.environment.k3s_ipv4
}

output "k3s_ipv6" {
  description = "Public IPv6 of the k3s node, and the value of the apex and `www` AAAA records."
  value       = module.environment.k3s_ipv6
}

output "postgres_ip" {
  description = "Private address PostgreSQL listens on. This is what the bff's connection string points at."
  value       = module.environment.postgres_ip
}

output "postgres_data_device" {
  description = "Device PGDATA lives on. Compare against `findmnt /var/lib/postgresql` on the PostgreSQL node when checking that a rebuild kept the data."
  value       = module.environment.postgres_data_device
}

output "wireguard_endpoint" {
  description = "`Endpoint` line for a peer's WireGuard config."
  value       = module.environment.wireguard_endpoint
}

output "next_steps" {
  description = "The parts of bring-up that cannot be declared — collecting the WireGuard key, closing the firewall, fetching the kubeconfig — in the order they have to happen."
  value       = module.environment.next_steps
}
