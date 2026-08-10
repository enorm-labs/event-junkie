module "environment" {
  source = "../../modules/environment"

  environment = "staging"
  # fsn1, nbg1 and hel1 are interchangeable on cost (free traffic within the eu-central zone) and
  # all three offer ARM, so this is the line to change when capacity moves — see check-capacity.sh.
  # Staging is a single node, so nothing here has to stay co-located; production does.
  location = "fsn1"

  # One CAX11 running everything. PostgreSQL is co-located rather than given its own node, but it
  # is still reached over the network at a private address, so the connection path the applications
  # exercise is the same shape as production's.
  k3s_server_type      = "cax11"
  postgres_server_type = null

  # Distinct from production's 10.0.0.0/16 and 10.10.0.0/24. Both tunnels are routinely up at the
  # same time, and overlapping ranges fail in a way that looks like a firewall problem for an hour
  # before anyone checks the routing table.
  network_ip_range = "10.1.0.0/16"
  subnet_ip_range  = "10.1.1.0/24"
  k3s_private_ip   = "10.1.1.10"
  wireguard_subnet = "10.10.1.0/24"

  # Not on the public internet at all (PLATFORM_SETUP.md §4a). No 80/443 from the world, and — see
  # below — no address record either, so the name does not resolve for anyone outside the tunnel.
  public_web = false

  # No lock and no backups: this is the environment where the destroy/apply cycle actually gets
  # exercised, and there is nothing here worth paying 20% to keep.
  ip_delete_protection = false
  enable_backups       = false

  ssh_key_ids     = var.ssh_key_ids
  ssh_public_keys = var.ssh_public_keys
  admin_cidrs     = var.admin_cidrs
  wireguard_peers = var.wireguard_peers

  k3s_extra_tls_sans = ["staging.event-junkie.de"]
}

# ---------------------------------------------------------------------------
# There are deliberately no DNS records in this file.
#
# `staging.event-junkie.de` does not resolve on the public internet — that is the design, not an
# omission (§4a). Name resolution happens over the tunnel, via its DNS or a `hosts` entry pointing
# at the node's WireGuard address.
#
# The consequence, worth stating where someone will look for it: TLS here cannot use the HTTP-01
# challenge, because HTTP-01 requires Let's Encrypt to reach the host and this design exists to
# stop that. cert-manager uses DNS-01 against the Hetzner DNS API instead, which needs no inbound
# access at all — so staging gets a genuine, publicly-trusted certificate for a hostname that has
# no public address. The TXT record is public; the A record never exists. That is #261's work.
# ---------------------------------------------------------------------------
