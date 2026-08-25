module "environment" {
  source = "../../modules/environment"

  environment = "staging"
  # The line to change when capacity moves: fsn1, nbg1 and hel1 are interchangeable on cost (free
  # traffic within eu-central) and all three offer ARM. See check-capacity.sh. nbg1 beats hel1 on
  # latency — Nuremberg is ~25 ms closer to a Berlin audience (§1) — and fsn1 has nothing orderable.
  #
  # BEING ADVERTISED IS NOT BEING ORDERABLE: see `k3s_server_type` below, and check-capacity.sh's
  # header for why a green result there means "worth trying" rather than "orderable".
  #
  # **Changing this line is not free.** Both Primary IPs and the PGDATA volume are location-bound,
  # so all three are destroyed to move — and the volume carries the database (#460), so the data has
  # to come off it first. Changing the server type below touches neither.
  location = "nbg1"

  # One node running everything. PostgreSQL is co-located rather than given its own node, but it is
  # still reached over the network at a private address, so the connection path the applications
  # exercise is the same shape as production's.
  #
  # **cx33 (x86): 4 vCPU / 8 GB at €10.10/month.** 8 GB is the floor for this stack, not a comfort —
  # PLATFORM_SETUP §1.5 has the component budget and what 4 GB cost (#271). Disk stays at 80 GB:
  # `keep_disk` is unset, so growing it would foreclose ever going back to a smaller type.
  #
  # **This line does not rebuild the node, and assuming it does is the trap.** `server_type` is an
  # in-place attribute within one architecture; `user_data` is what forces replacement, so any commit
  # touching `cloud-init/` leaves staging **one `tofu apply` away from a rebuild, whatever that apply
  # is for.** Crossing architectures is a rebuild too, rendered as a tidy in-place update and refused
  # by the API mid-apply. **"In-place" is a property of the attribute, not a prediction about the
  # apply** — read the plan. A rebuild keeps the volume (#460), the Primary IPs, the network and the
  # firewall, loses the k3s cluster and everything hand-made in it, and costs ~40 minutes;
  # docs/ops/CLUSTER_BOOTSTRAP.md §Rebuilding a node is the procedure.
  #
  # **Availability is advertised, not promised, and it lies in both directions — only an order
  # settles it.** cx33 is missing from nbg1's `datacenters` list and orders fine; cax11/cax21 are
  # listed and refuse with `HTTP 422 unsupported location for server type`. Refusals are free and
  # return in 0.1s: `./check-capacity.sh --probe`, whose header has the detail. Production keeps
  # `cax21` and keeps waiting, so PLATFORM_SETUP §1's arm64 parity argument holds for two of three.
  k3s_server_type      = "cx33"
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
  # exercised, and there is nothing here worth paying 20% to keep. The volume is unprotected for the
  # same reason — a locked one would survive `tofu destroy` anyway (the provider lifts its own
  # locks), so the flag would buy nothing here and cost the one thing staging is for.
  #
  # It still gets a volume, and that is deliberate: this is the *only* environment where the rebuild
  # can actually be proven, because production has never been applied and must not be destroyed to
  # find out. ~€0.44/month to not be guessing.
  ip_delete_protection              = false
  postgres_volume_delete_protection = false
  enable_backups                    = false

  ssh_key_ids     = var.ssh_key_ids
  ssh_public_keys = var.ssh_public_keys
  admin_cidrs     = var.admin_cidrs
  wireguard_peers = var.wireguard_peers

  k3s_extra_tls_sans = ["staging.event-junkie.de"]
}

# ---------------------------------------------------------------------------
# There are deliberately no DNS records in this file. `staging.event-junkie.de` does not resolve on
# the public internet — the design, not an omission (PLATFORM_SETUP.md §6). Name resolution happens
# over the tunnel, and the consequence worth stating where somebody will look for it is that TLS
# cannot use HTTP-01, which needs Let's Encrypt to reach the host. cert-manager uses DNS-01 against
# the Hetzner DNS API instead, so a hostname with no public address still gets a real ACME
# certificate: the TXT record is public, the A record never exists. The chart renders that solver
# (#261); #265 installs cert-manager and the webhook that answers it. The token is an **hcloud**
# token, which is also why `hcloud_zone` in bootstrap/ is the official provider's resource.
# ---------------------------------------------------------------------------
