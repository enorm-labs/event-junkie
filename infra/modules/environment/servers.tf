# Primary IPs are separate resources so a rebuilt server keeps its address and DNS never churns
# (PLATFORM_SETUP.md §10, step 6). `auto_delete = false` throughout: with it on, deleting a
# server deletes the IP too, and per the provider's warning breaks the state.

resource "hcloud_primary_ip" "k3s_ipv4" {
  name              = "${var.environment}-k3s-ipv4"
  type              = "ipv4"
  location          = var.location
  auto_delete       = false
  delete_protection = var.ip_delete_protection
  labels            = local.labels
}

resource "hcloud_primary_ip" "k3s_ipv6" {
  name              = "${var.environment}-k3s-ipv6"
  type              = "ipv6"
  location          = var.location
  auto_delete       = false
  delete_protection = var.ip_delete_protection
  labels            = local.labels
}

# `user_data` is force-new in the provider, and ignored here: an edit under cloud-init/ replaced
# both production nodes as a side effect of a DNS apply (#4129). A rebuild is now asked for by name,
# `tofu apply -replace=module.environment.hcloud_server.k3s`, and boots the current cloud-init/.
# The check at the end of this file keeps the drift visible.
resource "hcloud_server" "k3s" {
  name         = "${var.environment}-k3s"
  server_type  = var.k3s_server_type
  image        = var.image
  location     = var.location
  ssh_keys     = var.ssh_key_ids
  backups      = var.enable_backups
  user_data    = local.k3s_user_data
  firewall_ids = [hcloud_firewall.k3s.id]

  labels = local.labels

  public_net {
    ipv4_enabled = true
    ipv4         = hcloud_primary_ip.k3s_ipv4.id
    ipv6_enabled = true
    ipv6         = hcloud_primary_ip.k3s_ipv6.id
  }

  # `subnet_id` rather than `network_id`: `network_id` alone attaches to "the last subnet (ordered
  # by ip_range), which may be unpredictable", and the subnet reference supplies the ordering
  # dependency. `alias_ips = []` is not decoration: without it a provider bug detaches and
  # reattaches the private network on every apply (hetznercloud/terraform-provider-hcloud#650).
  network {
    subnet_id = hcloud_network_subnet.main.id
    ip        = var.k3s_private_ip
    alias_ips = []
  }

  lifecycle {
    # `ssh_keys` cannot be updated in place. Injected into root at creation and worthless once
    # harden.sh disables root login minutes later, so rebuilding production to add a laptop would be a
    # pure loss; day-to-day access is the `ops` user and the tunnel.
    ignore_changes = [ssh_keys, user_data]
  }
}

# ---------------------------------------------------------------------------
# Dedicated PostgreSQL node
# ---------------------------------------------------------------------------

resource "hcloud_primary_ip" "postgres_ipv4" {
  count = local.dedicated_postgres && var.postgres_public_ipv4 ? 1 : 0

  name              = "${var.environment}-postgres-ipv4"
  type              = "ipv4"
  location          = var.location
  auto_delete       = false
  delete_protection = var.ip_delete_protection
  labels            = local.labels
}

resource "hcloud_primary_ip" "postgres_ipv6" {
  count = local.dedicated_postgres ? 1 : 0

  name              = "${var.environment}-postgres-ipv6"
  type              = "ipv6"
  location          = var.location
  auto_delete       = false
  delete_protection = var.ip_delete_protection
  labels            = local.labels
}

resource "hcloud_server" "postgres" {
  count = local.dedicated_postgres ? 1 : 0

  name         = "${var.environment}-postgres"
  server_type  = var.postgres_server_type
  image        = var.image
  location     = var.location
  ssh_keys     = var.ssh_key_ids
  backups      = var.enable_backups
  user_data    = local.postgres_user_data
  firewall_ids = [hcloud_firewall.postgres[0].id]

  labels = local.labels

  # No public IPv4 by default: the only route out is IPv6, the only route in the private network.
  # SSH to it from the k3s node.
  public_net {
    ipv4_enabled = var.postgres_public_ipv4
    ipv4         = var.postgres_public_ipv4 ? hcloud_primary_ip.postgres_ipv4[0].id : null
    ipv6_enabled = true
    ipv6         = hcloud_primary_ip.postgres_ipv6[0].id
  }

  # See the k3s node above for `subnet_id` and `alias_ips = []`.
  network {
    subnet_id = hcloud_network_subnet.main.id
    ip        = var.postgres_private_ip
    alias_ips = []
  }

  lifecycle {
    ignore_changes = [ssh_keys, user_data]
  }
}

# A warning, never a failure: a node that booted older cloud-init/ is drift to fix by hand or by
# -replace (CLUSTER_BOOTSTRAP.md § Applying a `cloud-init` fix without rebuilding), not a reason
# to block an unrelated apply.
check "user_data_drift" {
  assert {
    condition     = hcloud_server.k3s.user_data == local.user_data_hash.k3s
    error_message = "${hcloud_server.k3s.name} booted another user_data than cloud-init/ renders. Apply the fix by hand, or rebuild with -replace=module.environment.hcloud_server.k3s."
  }

  assert {
    condition     = !local.dedicated_postgres || one(hcloud_server.postgres[*].user_data) == local.user_data_hash.postgres
    error_message = "${var.environment}-postgres booted another user_data than cloud-init/ renders. Apply the fix by hand, or rebuild with -replace='module.environment.hcloud_server.postgres[0]'."
  }
}
