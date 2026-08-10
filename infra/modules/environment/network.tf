locals {
  # One label set on everything, so a stray resource is identifiable in the console and
  # `hcloud server list -l environment=staging` is a reliable answer rather than a guess.
  labels = {
    environment = var.environment
    managed-by  = "opentofu"
    project     = "event-junkie"
  }

  # A dedicated PostgreSQL node, or PostgreSQL co-located on the k3s node.
  dedicated_postgres = var.postgres_server_type != null

  # Whichever address PostgreSQL ends up listening on. The bff's connection string points here in
  # both topologies, which is what keeps staging a fair rehearsal of production.
  postgres_ip = local.dedicated_postgres ? var.postgres_private_ip : var.k3s_private_ip
}

resource "hcloud_network" "main" {
  name     = "${var.environment}-net"
  ip_range = var.network_ip_range
  labels   = local.labels
}

resource "hcloud_network_subnet" "main" {
  network_id   = hcloud_network.main.id
  type         = "cloud"
  network_zone = var.network_zone
  ip_range     = var.subnet_ip_range
}
