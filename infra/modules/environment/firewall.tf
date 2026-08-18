# Two things about Hetzner Cloud Firewalls decide the shape of this file, and both are easy to get
# wrong from the diagram alone:
#
#   1. They filter the **public interface only**. Traffic on the private network is never inspected,
#      so "PostgreSQL reachable on 5432 from the private network only" is enforced by the *absence*
#      of a public route plus PostgreSQL's own `listen_addresses` and `pg_hba.conf` — not by a rule
#      here. See cloud-init/postgres.sh.
#   2. They are stateful for outbound-initiated connections. A firewall with zero inbound rules
#      still lets `apt` work; it does not need a companion "allow established" rule.

locals {
  world = ["0.0.0.0/0", "::/0"]

  # Public web. Absent on staging, which is not on the internet at all (§4a).
  web_rules = var.public_web ? [
    {
      description = "HTTP - ACME HTTP-01 challenge and the redirect to HTTPS"
      protocol    = "tcp"
      port        = "80"
      source_ips  = local.world
    },
    {
      description = "HTTPS"
      protocol    = "tcp"
      port        = "443"
      source_ips  = local.world
    },
  ] : []

  # Open to the world on purpose. WireGuard does not reply to a packet without a valid key, so to a
  # scanner this port is indistinguishable from a closed one — a far better public surface than SSH,
  # which announces itself and its version to anyone who connects.
  wireguard_rules = [
    {
      description = "WireGuard"
      protocol    = "udp"
      port        = tostring(var.wireguard_port)
      source_ips  = local.world
    },
  ]

  # Break-glass only. With `admin_cidrs = []` these rules do not exist and neither port is reachable
  # from the internet at any address; the tunnel is the only way in. See §8a.
  admin_rules = length(var.admin_cidrs) == 0 ? [] : [
    {
      description = "SSH - bootstrap and break-glass; use the tunnel for daily work"
      protocol    = "tcp"
      port        = "22"
      source_ips  = var.admin_cidrs
    },
    {
      description = "Kubernetes API - bootstrap and break-glass"
      protocol    = "tcp"
      port        = "6443"
      source_ips  = var.admin_cidrs
    },
  ]

  icmp_rules = [
    {
      description = "ICMP - ping and, on IPv6, Path MTU Discovery, which breaks in confusing ways when dropped"
      protocol    = "icmp"
      port        = null
      source_ips  = local.world
    },
  ]

  k3s_rules = concat(local.web_rules, local.wireguard_rules, local.admin_rules, local.icmp_rules)
}

resource "hcloud_firewall" "k3s" {
  name   = "${var.environment}-k3s"
  labels = local.labels

  dynamic "rule" {
    for_each = local.k3s_rules
    content {
      description = rule.value.description
      direction   = "in"
      protocol    = rule.value.protocol
      port        = rule.value.port
      source_ips  = rule.value.source_ips
    }
  }
}

# Deliberately empty: no inbound rule of any kind, at any port, from anywhere. Everything the node
# needs — `apt`, the wal-g release from GitHub, and wal-g pushing to Object Storage — is outbound,
# which the stateful firewall allows.
#
# This is also why `postgres_public_ipv4 = true` in production (#270) is not an exposure. The
# address exists so the node can *reach* GitHub, which publishes no AAAA record; nothing can reach
# back through it, because there is no rule here for anything to match.
resource "hcloud_firewall" "postgres" {
  count = local.dedicated_postgres ? 1 : 0

  name   = "${var.environment}-postgres"
  labels = local.labels
}
