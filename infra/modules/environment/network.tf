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

  # The second pair a cutover moves to (#4128). Each node exists once its type is set, so one
  # can be ordered while the other is not for sale.
  next_pair                = var.next_pair != null
  next_k3s                 = local.next_pair && try(var.next_pair.k3s_server_type, null) != null
  next_postgres            = local.next_pair && try(var.next_pair.postgres_server_type, null) != null
  next_colocated           = local.next_pair && coalesce(try(var.next_pair.colocated, null), !local.dedicated_postgres)
  next_k3s_private_ip      = local.next_pair ? coalesce(var.next_pair.k3s_private_ip, cidrhost(var.subnet_ip_range, 11)) : null
  next_postgres_private_ip = local.next_pair ? coalesce(var.next_pair.postgres_private_ip, cidrhost(var.subnet_ip_range, 21)) : null

  next_ips_distinct = length(setintersection(
    [local.next_k3s_private_ip, local.next_postgres_private_ip],
    [var.k3s_private_ip, var.postgres_private_ip],
  )) == 0
  next_ips_in_subnet = alltrue([
    for ip in [local.next_k3s_private_ip, local.next_postgres_private_ip] :
    can(cidrhost("${ip}/32", 0)) &&
    cidrhost("${ip}/${split("/", var.subnet_ip_range)[1]}", 0) == cidrhost(var.subnet_ip_range, 0)
  ])

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
