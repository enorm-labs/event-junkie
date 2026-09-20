# comment-lint: allow-file 20 module arguments, one of which destroys the volume holding the database (#460).
module "environment" {
  source = "../../modules/environment"

  environment = "staging"
  # nbg1 wins on latency, ~25 ms closer to a Berlin audience (§1). Changing this line is not free:
  # Primary IPs and the PGDATA volume are location-bound, so all three are destroyed to move, and
  # the volume carries the database (#460).
  location = "nbg1"

  # One node running everything, PostgreSQL still reached over the network at a private address, so
  # the connection path has production's shape. cx33 (x86): 4 vCPU / 8 GB at €10.10/month, where
  # 8 GB is the floor for this stack (PLATFORM_SETUP §1.5, #271). `keep_disk` is unset, so growing
  # the disk would foreclose a smaller type.
  #
  # This line does not rebuild the node: `server_type` is in-place within one architecture, but
  # `user_data` forces replacement, so any commit touching `cloud-init/` leaves staging one
  # `tofu apply` away from a rebuild. Crossing architectures rebuilds too, rendered as an in-place
  # update and refused mid-apply. Read the plan. A rebuild keeps the volume (#460), the IPs, the
  # network and the firewall, loses the k3s cluster, and costs ~40 minutes (CLUSTER_BOOTSTRAP.md
  # §Rebuilding).
  #
  # Availability is advertised, not promised: cx33 is missing from nbg1's `datacenters` list and
  # orders fine; cax11/cax21 are listed and refuse with `HTTP 422 unsupported location for server
  # type`. `./check-capacity.sh --probe` settles it; its WATCH list names the ARM pair production
  # would return to (PLATFORM_SETUP.md § Why the nodes are x86 and not ARM).
  k3s_server_type      = "cx33"
  postgres_server_type = null

  # Distinct from production's 10.0.0.0/16 and 10.10.0.0/24: both tunnels are up at once, and an
  # overlap looks like a firewall problem until someone reads the routing table.
  network_ip_range = "10.1.0.0/16"
  subnet_ip_range  = "10.1.1.0/24"
  k3s_private_ip   = "10.1.1.10"
  wireguard_subnet = "10.10.1.0/24"

  # Not on the public internet at all (PLATFORM_SETUP.md §4a): no 80/443, and no address record.
  public_web = false

  # No lock and no backups: this is where the destroy/apply cycle is exercised, and a locked volume
  # would not survive `tofu destroy` anyway, the provider lifting its own locks. It still gets a
  # volume, so the rebuild can be proven here: ~€0.44/month to not be guessing.
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
# Deliberately no DNS records: `staging.event-junkie.de` resolves only over the tunnel
# (PLATFORM_SETUP.md §6). So TLS cannot use HTTP-01, and cert-manager uses DNS-01 against the
# Hetzner DNS API: the TXT record is public, the A record never exists. The chart renders that
# solver (#261), #265 installs the webhook, and the token is an hcloud one, which is why
# `hcloud_zone` in bootstrap/ is the official provider's resource.
# ---------------------------------------------------------------------------
