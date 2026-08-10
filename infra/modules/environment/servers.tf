# Primary IPs are separate resources rather than server-attached addresses so that a rebuilt server
# keeps its address and DNS never churns (PLATFORM_SETUP.md §10, step 6).
#
# `auto_delete = false` throughout: with it on, deleting a server deletes the IP too, which both
# defeats the point and — per the provider's own warning — breaks the state.

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

# `user_data` forces replacement. That is correct for nodes meant to be disposable, but it means an
# edit to anything under cloud-init/ rebuilds the node — including production. `tofu plan` says so
# plainly, in red; read it before typing yes.
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

  # `subnet_id` rather than `network_id`: the provider docs are explicit that `network_id` alone
  # attaches to "the last subnet (ordered by ip_range), which may be unpredictable", and referencing
  # the subnet also gives the ordering dependency the docs otherwise ask for a `depends_on` to state.
  #
  # `alias_ips = []` is not decoration. Without it a documented provider bug detaches and reattaches
  # the private network on *every* apply (hetznercloud/terraform-provider-hcloud#650).
  network {
    subnet_id = hcloud_network_subnet.main.id
    ip        = var.k3s_private_ip
    alias_ips = []
  }

  lifecycle {
    # `ssh_keys` cannot be updated in place: changing it destroys and recreates the server. Since
    # these keys are injected into *root* at creation and harden.sh disables root login minutes
    # later, they are write-once and worthless afterwards — rebuilding production to add a laptop
    # would be a pure loss. Day-to-day access is the `ops` user and the tunnel.
    ignore_changes = [ssh_keys]
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

  # No public IPv4 by default: the node's only route to the internet is IPv6, and its only route
  # inward is the private network. SSH to it from the k3s node, which is behind the tunnel.
  public_net {
    ipv4_enabled = var.postgres_public_ipv4
    ipv4         = var.postgres_public_ipv4 ? hcloud_primary_ip.postgres_ipv4[0].id : null
    ipv6_enabled = true
    ipv6         = hcloud_primary_ip.postgres_ipv6[0].id
  }

  # See the k3s node above for why `subnet_id` and `alias_ips = []`.
  network {
    subnet_id = hcloud_network_subnet.main.id
    ip        = var.postgres_private_ip
    alias_ips = []
  }

  lifecycle {
    ignore_changes = [ssh_keys]
  }
}
