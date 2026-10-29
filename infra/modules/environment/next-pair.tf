# The second pair a type change moves to (#4128). Built beside the running pair on temporary
# Primary IPs, so the old cluster serves until the cutover. The cutover moves the real Primary IPs
# and the volume, which is why none of them is referenced here.

resource "hcloud_primary_ip" "k3s_next_ipv4" {
  count = local.next_k3s ? 1 : 0

  name              = "${var.environment}-k3s-next-ipv4"
  type              = "ipv4"
  location          = var.location
  auto_delete       = false
  delete_protection = false
  labels            = local.labels
}

resource "hcloud_primary_ip" "k3s_next_ipv6" {
  count = local.next_k3s ? 1 : 0

  name              = "${var.environment}-k3s-next-ipv6"
  type              = "ipv6"
  location          = var.location
  auto_delete       = false
  delete_protection = false
  labels            = local.labels
}

# The hostname is `<env>-k3s`, as on the running node: it becomes the Kubernetes node name, which
# outlives the cutover.
resource "hcloud_server" "k3s_next" {
  count = local.next_k3s ? 1 : 0

  name         = "${var.environment}-k3s-next"
  server_type  = var.next_pair.k3s_server_type
  image        = var.image
  location     = var.location
  ssh_keys     = var.ssh_key_ids
  backups      = var.enable_backups
  user_data    = local.k3s_user_data_by_node.next
  firewall_ids = [hcloud_firewall.k3s.id]

  labels = local.labels

  public_net {
    ipv4_enabled = true
    ipv4         = hcloud_primary_ip.k3s_next_ipv4[0].id
    ipv6_enabled = true
    ipv6         = hcloud_primary_ip.k3s_next_ipv6[0].id
  }

  # See servers.tf for `subnet_id`, `alias_ips = []` and the ignored attributes.
  network {
    subnet_id = hcloud_network_subnet.main.id
    ip        = local.next_k3s_private_ip
    alias_ips = []
  }

  lifecycle {
    ignore_changes = [ssh_keys, user_data]

    precondition {
      condition     = local.next_ips_distinct
      error_message = "next_pair's private addresses must differ from the running pair's: both pairs share one network."
    }

    precondition {
      condition     = local.next_ips_in_subnet
      error_message = "next_pair's private addresses must be plain IPs inside subnet_ip_range."
    }
  }
}

resource "hcloud_primary_ip" "postgres_next_ipv4" {
  count = local.next_postgres && var.postgres_public_ipv4 ? 1 : 0

  name              = "${var.environment}-postgres-next-ipv4"
  type              = "ipv4"
  location          = var.location
  auto_delete       = false
  delete_protection = false
  labels            = local.labels
}

resource "hcloud_primary_ip" "postgres_next_ipv6" {
  count = local.next_postgres ? 1 : 0

  name              = "${var.environment}-postgres-next-ipv6"
  type              = "ipv6"
  location          = var.location
  auto_delete       = false
  delete_protection = false
  labels            = local.labels
}

resource "hcloud_server" "postgres_next" {
  count = local.next_postgres ? 1 : 0

  name         = "${var.environment}-postgres-next"
  server_type  = var.next_pair.postgres_server_type
  image        = var.image
  location     = var.location
  ssh_keys     = var.ssh_key_ids
  backups      = var.enable_backups
  user_data    = local.postgres_user_data_by_node.next
  firewall_ids = [hcloud_firewall.postgres[0].id]

  labels = local.labels

  public_net {
    ipv4_enabled = var.postgres_public_ipv4
    ipv4         = var.postgres_public_ipv4 ? hcloud_primary_ip.postgres_next_ipv4[0].id : null
    ipv6_enabled = true
    ipv6         = hcloud_primary_ip.postgres_next_ipv6[0].id
  }

  network {
    subnet_id = hcloud_network_subnet.main.id
    ip        = local.next_postgres_private_ip
    alias_ips = []
  }

  lifecycle {
    ignore_changes = [ssh_keys, user_data]

    precondition {
      condition     = local.next_ips_distinct
      error_message = "next_pair's private addresses must differ from the running pair's: both pairs share one network."
    }

    precondition {
      condition     = local.next_ips_in_subnet
      error_message = "next_pair's private addresses must be plain IPs inside subnet_ip_range."
    }
  }
}

check "next_pair_user_data_drift" {
  assert {
    condition     = !local.next_k3s || one(hcloud_server.k3s_next[*].user_data) == lookup(local.user_data_hash, "k3s_next", "")
    error_message = "${var.environment}-k3s-next booted another user_data than cloud-init/ renders. Apply the fix by hand before the cutover."
  }

  assert {
    condition     = !local.next_postgres || one(hcloud_server.postgres_next[*].user_data) == lookup(local.user_data_hash, "postgres_next", "")
    error_message = "${var.environment}-postgres-next booted another user_data than cloud-init/ renders. Apply the fix by hand before the cutover."
  }
}
