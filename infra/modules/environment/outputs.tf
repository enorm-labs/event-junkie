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
  description = "This apply's addresses, and the one command that must come next. The runbook is docs/CLUSTER_BOOTSTRAP.md."
  # Deliberately short. The full ordered runbook is docs/CLUSTER_BOOTSTRAP.md and there is exactly
  # one copy of it; this output exists to supply the two things that document cannot know -- the
  # addresses this apply just created -- plus the single command that has to happen next.
  value = <<-EOT
    Addresses from this apply:

        node (public)     ${hcloud_server.k3s.ipv4_address}
        node (tunnel)     ${cidrhost(var.wireguard_subnet, 1)}
        WireGuard         ${hcloud_server.k3s.ipv4_address}:${var.wireguard_port}
        AllowedIPs        ${var.wireguard_subnet}
        kubeconfig name   ~/.kube/event-junkie-${var.environment}

    Next: collect the WireGuard server public key, which is generated on the node and never enters
    the state file, so nothing but the node can tell you it:

        ssh -i <key> ops@${hcloud_server.k3s.ipv4_address} sudo cat /etc/wireguard/public.key

    Two things that waste an hour if unsaid: the -i is required unless that key is in your agent
    (otherwise "Permission denied (publickey)", which reads as though the ops user is missing), and
    port 22 is not open for the first couple of minutes -- while the node boots the connection times
    out rather than being refused, which looks exactly like the firewall dropping you.

    Then follow docs/CLUSTER_BOOTSTRAP.md from step 4. It covers the tunnel, the kubeconfig, the
    database, both secrets and flux bootstrap, in the order that avoids the failures we hit.
  EOT
}
