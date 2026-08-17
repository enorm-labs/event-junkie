output "k3s_ipv4" {
  description = "Reachable only on 51820/udp. Nothing answers on 80, 443, 22 or 6443 from the internet."
  value       = module.environment.k3s_ipv4
}

output "postgres_ip" {
  description = "Address PostgreSQL listens on. Co-located with k3s here, so it is the node's own private address."
  value       = module.environment.postgres_ip
}

output "postgres_data_device" {
  description = "Device PGDATA lives on. Compare against `findmnt /var/lib/postgresql` on the node when checking that a rebuild kept the data."
  value       = module.environment.postgres_data_device
}

output "wireguard_endpoint" {
  description = "`Endpoint` line for a peer's WireGuard config. The only way into this environment."
  value       = module.environment.wireguard_endpoint
}

output "wireguard_server_address" {
  description = "Point `staging.event-junkie.de` at this in the tunnel's DNS or in /etc/hosts. It is the only way the name resolves."
  value       = module.environment.wireguard_server_address
}

output "next_steps" {
  description = "The parts of bring-up that cannot be declared, in the order they have to happen."
  value       = module.environment.next_steps
}
