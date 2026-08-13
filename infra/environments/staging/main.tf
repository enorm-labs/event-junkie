module "environment" {
  source = "../../modules/environment"

  environment = "staging"
  # fsn1, nbg1 and hel1 are interchangeable on cost (free traffic within the eu-central zone) and
  # all three offer ARM, so this is the line to change when capacity moves — see check-capacity.sh.
  # Staging is a single node, so nothing here has to stay co-located; production does.
  #
  # nbg1 rather than fsn1 since 2026-08-13, because fsn1 had nothing orderable at all — not merely
  # no ARM, nothing of any architecture. hel1 has the same x86 types as nbg1, so the tie is broken
  # on latency: Nuremberg is ~25 ms closer to a Berlin audience than Helsinki (§1).
  #
  # BEING ADVERTISED IS NOT BEING ORDERABLE, which is what this line originally moved here for and
  # what it then ran into. Three orders for a cax11 in nbg1 were refused with `unsupported location
  # for server type (invalid_input)` while the datacenters endpoint listed it under `available` and
  # cax11's own pricing listed nbg1. That error reads as though this line is wrong. It was not —
  # see `k3s_server_type` below for how it was settled, and check-capacity.sh's header for why a
  # green result from it now means "worth trying" rather than "orderable".
  #
  # Changing this line is no longer free. It was on 2026-08-13 — the project was verified empty
  # first — but both Primary IPs now exist in nbg1, and a Primary IP is location-bound, so they have
  # to be destroyed before this can move again. Changing the server type below does not touch them.
  #
  # Nothing in the design wanted the two environments co-located: staging has its own network
  # (10.1.0.0/16), its own firewall and its own database, and reaches production over nothing at all.
  location = "nbg1"

  # One node running everything. PostgreSQL is co-located rather than given its own node, but it is
  # still reached over the network at a private address, so the connection path the applications
  # exercise is the same shape as production's.
  #
  # **cpx22 (x86) rather than cax11 (ARM), and this is forced rather than chosen (2026-08-13).**
  # Three separate orders for a cax11 in nbg1 were refused with `unsupported location for server
  # type` while Hetzner's API advertised it as available — including a probe for a bare server with
  # no IPs, no network and no firewall, which clears this module of any part in it. Production keeps
  # `cax21` and keeps waiting; staging could not, because everything downstream of a cluster
  # existing (#265, #286, #270, #416) was blocked behind it.
  #
  # The cost is €23.19/month against cax11's €7.13 for identical 2 vCPU / 4 GB, and the parity
  # argument in PLATFORM_SETUP §1 — laptop, staging and production all arm64 — now holds for two of
  # the three. It survives because #264 publishes multi-arch images, so the same chart and the same
  # tags run on both; nothing else about the environment differs.
  #
  # **This is meant to be temporary, and there are two cheaper targets to watch**, neither orderable
  # anywhere in eu-central today:
  #
  #   cax11  €7.13  ARM   — restores full parity with production
  #   cx23   €6.53  x86   — cheaper than the ARM plan ever was, and no re-platforming
  #
  # `./check-capacity.sh staging` tracks whatever this line names, so it will stop reporting on
  # those the moment this changes. Watch them with `./check-capacity.sh --all`.
  k3s_server_type      = "cpx22"
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
# no public address. The TXT record is public; the A record never exists.
#
# The chart renders that solver (#261); #265 installs cert-manager and the Hetzner webhook that
# answers it, both as Flux HelmReleases in deploy/clusters/staging/.
#
# The token it needs is an **hcloud** token — the same kind this stack authenticates with, from the
# Hetzner Console. The old dns.hetzner.com API and its separate DNS tokens were shut down in May
# 2026, which is also why `hcloud_zone` in bootstrap/ is the official provider's resource rather
# than a community DNS provider's.
# ---------------------------------------------------------------------------
