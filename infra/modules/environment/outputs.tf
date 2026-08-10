output "k3s_ipv4" {
  description = "Public IPv4 of the k3s node. The A record's value, and the SSH target for the very first login."
  value       = hcloud_server.k3s.ipv4_address
}

output "k3s_ipv6" {
  description = "First address of the k3s node's assigned /64. The AAAA record's value."
  value       = hcloud_server.k3s.ipv6_address
}

output "k3s_ipv6_network" {
  description = "The whole /64 routed to the k3s node."
  value       = hcloud_server.k3s.ipv6_network
}

output "k3s_private_ip" {
  description = "Private address of the k3s node."
  value       = var.k3s_private_ip
}

output "postgres_ip" {
  description = "Address PostgreSQL listens on, whether it runs on its own node or beside k3s. This is what the bff's connection string points at."
  value       = local.postgres_ip
}

output "postgres_ipv6" {
  description = "Public IPv6 of the dedicated PostgreSQL node, or null when it is co-located. Egress only; nothing answers on it."
  value       = local.dedicated_postgres ? hcloud_server.postgres[0].ipv6_address : null
}

output "network_id" {
  description = "Private network ID, for anything attached later."
  value       = hcloud_network.main.id
}

output "wireguard_endpoint" {
  description = "`Endpoint` line for a peer's WireGuard config."
  value       = "${hcloud_server.k3s.ipv4_address}:${var.wireguard_port}"
}

output "wireguard_server_address" {
  description = "The node's address inside the tunnel — the host a kubeconfig and SSH should point at once the tunnel is up."
  value       = cidrhost(var.wireguard_subnet, 1)
}

output "next_steps" {
  description = "The parts of bring-up that cannot be declared, in the order they have to happen."
  value       = <<-EOT
    1. Collect the WireGuard server public key, which is generated on the node and never enters the
       state file:

           ssh ops@${hcloud_server.k3s.ipv4_address} sudo cat /etc/wireguard/public.key

    2. Put it in your local WireGuard config, with:

           Endpoint   = ${hcloud_server.k3s.ipv4_address}:${var.wireguard_port}
           AllowedIPs = ${var.wireguard_subnet}

    3. Bring the tunnel up, confirm ssh ops@${cidrhost(var.wireguard_subnet, 1)} works, then set
       `admin_cidrs = []` and re-apply. 22 and 6443 are then unreachable from the internet.

    4. Fetch the kubeconfig and repoint it at the tunnel address:

           ssh ops@${cidrhost(var.wireguard_subnet, 1)} sudo cat /etc/rancher/k3s/k3s.yaml \
             | sed 's|127.0.0.1|${cidrhost(var.wireguard_subnet, 1)}|' > ~/.kube/event-junkie-${var.environment}
  EOT
}
