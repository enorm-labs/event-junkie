module "environment" {
  source = "../../modules/environment"

  environment = "production"
  # fsn1, nbg1 and hel1 are interchangeable on cost (free traffic within the eu-central zone) and
  # all three offer ARM, so this is the line to change when capacity moves — see check-capacity.sh.
  # It covers both nodes on purpose: they must share a location, since every query crosses that link.
  location = "fsn1"

  # CAX21: 4 ARM vCPU / 8 GB. The memory arithmetic behind it — including why Flux rather than
  # ArgoCD is what keeps this on an 8 GB node — is PLATFORM_SETUP.md §1.
  k3s_server_type = "cax21"

  # CAX11: 2 ARM vCPU / 4 GB. IPv6-only was the intent, and #270 settled that it cannot be — not on
  # a guess about `apt`, which turns out to be fine (apt.postgresql.org answers on IPv6), but on
  # **github.com publishing no AAAA record at all**, checked 2026-08-18. wal-g ships only as a
  # GitHub release, so an IPv6-only node cannot install the one thing standing between us and losing
  # the database, and `backups.sh` stops the boot rather than coming up without it.
  #
  # ~€0.50/month for the address, against a NAT gateway (infra/AGENTS.md) that is only worth
  # building if the address itself is the objection. It is not: the firewall still admits nothing
  # inbound, so this buys egress and not exposure.
  postgres_server_type = "cax11"
  postgres_public_ipv4 = true

  # Locks the addresses and the database volume against a console mis-click. Neither stops
  # `tofu destroy` — the provider lifts its own locks — so treat a destroy in this directory as
  # unguarded and mean it. What a rebuild *does* survive is the volume, which is a resource of its
  # own that no server references (#460).
  ip_delete_protection              = true
  postgres_volume_delete_protection = true
  enable_backups                    = true
  public_web                        = true

  ssh_key_ids     = var.ssh_key_ids
  ssh_public_keys = var.ssh_public_keys
  admin_cidrs     = var.admin_cidrs
  wireguard_peers = var.wireguard_peers

  # So a kubeconfig can name the host rather than an address, if it ever needs to.
  k3s_extra_tls_sans = ["k8s.${var.domain}"]
}

# ---------------------------------------------------------------------------
# Address records
#
# The zone belongs to the bootstrap stack and is read, never managed, from here — so a `destroy`
# in this directory removes the records and leaves the zone, its delegation and its DNSSEC key
# untouched. That separation is the point of the two-stack split.
# ---------------------------------------------------------------------------

data "hcloud_zone" "main" {
  name = var.domain
}

locals {
  # The apex is canonical; `www` carries the same addresses and is redirected at Traefik rather
  # than in DNS, because a CNAME cannot sit at the apex and a redirect wants to be one rule in one
  # place (#259).
  hostnames = ["@", "www"]

  address_records = {
    for pair in setproduct(local.hostnames, ["A", "AAAA"]) :
    "${pair[0]}/${pair[1]}" => {
      name  = pair[0]
      type  = pair[1]
      value = pair[1] == "A" ? module.environment.k3s_ipv4 : module.environment.k3s_ipv6
    }
  }
}

resource "hcloud_zone_rrset" "address" {
  for_each = local.address_records

  zone = data.hcloud_zone.main.name
  name = each.value.name
  type = each.value.type
  ttl  = var.dns_ttl

  records = [{ value = each.value.value }]

  labels = {
    managed-by  = "opentofu"
    environment = "production"
  }
}
