# These three read the Primary IPs and not `hcloud_server.k3s`, and that is the whole point of them
# (#883). `servers.tf` declares the Primary IPs so an address outlives the machine. Reading the
# address off the server kept the value stable and made every consumer depend on the server anyway --
# so `tofu apply -target=hcloud_zone_rrset.address`, which is meant to be the whole of a go-live,
# pulled both nodes into the plan and replaced them on any pending `user_data` drift. The graph is
# built from the expression, not from the value it happens to produce.

output "k3s_ipv4" {
  description = "Public IPv4 of the k3s node. The A record's value, and the SSH target for the very first login."
  value       = hcloud_primary_ip.k3s_ipv4.ip_address
}

output "k3s_ipv6" {
  description = "The k3s node's public IPv6 address, the first host in its /64. The AAAA record's value."

  # **`hcloud_primary_ip.ipv6.ip_address` is NOT a usable address, whatever its name says.** Measured
  # against production: it returns the base of the /64, `2a01:4f8:c0c:9c82::`, while the server answers
  # on `...::1`. The provider documents it as "IP address of the Primary IP", which reads like a host
  # address and is not one.
  #
  # It is also not a CIDR, so it carries no `/` and looks entirely plausible in an AAAA record. It
  # would be accepted by the zone and would simply never resolve. `cidrhost` on the network is what
  # reproduces the address the server actually uses, without depending on the server to say so.
  value = cidrhost(hcloud_primary_ip.k3s_ipv6.ip_network, 1)

  # Guards the mistake above rather than a malformed string: the base of the network is exactly the
  # wrong answer, and it is the one the obvious attribute hands you.
  precondition {
    condition     = cidrhost(hcloud_primary_ip.k3s_ipv6.ip_network, 1) != hcloud_primary_ip.k3s_ipv6.ip_address
    error_message = "k3s_ipv6 resolved to the base of the /64, which is the address the server does not answer on. See infra/modules/environment/outputs.tf."
  }
}

output "k3s_ipv6_network" {
  description = "The whole /64 routed to the k3s node."
  value       = hcloud_primary_ip.k3s_ipv6.ip_network
}

output "k3s_private_ip" {
  description = "Private address of the k3s node."
  value       = var.k3s_private_ip
}

output "postgres_ip" {
  description = "Address PostgreSQL listens on, whether it runs on its own node or beside k3s. This is what the bff's connection string points at."
  value       = local.postgres_ip
}

output "postgres_data_device" {
  description = "Device PGDATA lives on. What `findmnt /var/lib/postgresql` on the node should be reporting, and the one value that makes a rebuild verifiable without opening the console."
  value       = hcloud_volume.postgres.linux_device
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
  description = "This apply's addresses, and the one command that must come next. The runbook is docs/ops/CLUSTER_BOOTSTRAP.md."
  # Deliberately short. The full ordered runbook is docs/ops/CLUSTER_BOOTSTRAP.md and there is exactly
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

    Then follow docs/ops/CLUSTER_BOOTSTRAP.md from step 4. It covers the tunnel, the kubeconfig, the
    database, both secrets and flux bootstrap, in the order that avoids the failures we hit.
  EOT
}
