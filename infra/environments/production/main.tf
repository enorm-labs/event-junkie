module "environment" {
  source = "../../modules/environment"

  environment = "production"
  # Covers both nodes on purpose: every query crosses that link. All three eu-central locations
  # advertise ARM and none will sell it; for x86 this is exactly the lever, and a successful
  # check-capacity.sh probe means orderable at that instant only.
  #
  # Not fsn1, a constraint that outlives the capacity one: Object Storage lives there, so the
  # database and its only off-server backups would share a location. nbg1 over hel1 on latency
  # (~25 ms closer to Berlin, PLATFORM_SETUP.md §1); staging is not production's failover, so
  # sharing nbg1 with it protects nothing. Moving is a dump and a restore once there is data.
  location = "nbg1"

  # CX33: 4 x86 vCPU / 8 GB / 80 GB disk; the memory arithmetic is PLATFORM_SETUP.md §1.
  #
  # x86 because ARM cannot be bought, settled by ordering: cax11 and cax21 are refused in all three
  # eu-central locations with `unsupported location for server type`, in locations
  # check-capacity.sh reports as available, which is what `--probe` exists for. ARM is also dearer:
  # cx33 + cx23 is €16.63/month against cax21 + cax11's €19.61 for the same cores, memory and disks.
  #
  # Going back, if CAX returns, is a REBUILD of both nodes: Hetzner cannot rescale across
  # architectures, and the plan renders an in-place update the API refuses mid-apply. #460's volume
  # means the database survives, the k3s cluster does not (CLUSTER_BOOTSTRAP.md §Rebuilding a node).
  # Images are multi-arch (#264).
  k3s_server_type = "cx33"

  # CX23: 2 x86 vCPU / 4 GB / 40 GB disk, spec-for-spec what CAX11 was.
  #
  # IPv6-only cannot be (#270): github.com publishes no AAAA record, wal-g ships only as a GitHub
  # release, and `backups.sh` stops the boot rather than coming up without it. ~€0.50/month for the
  # address; the firewall still admits nothing inbound, so this buys egress and not exposure.
  postgres_server_type = "cx23"
  postgres_public_ipv4 = true

  # Locks the addresses and the database volume against a console mis-click. Both still false after
  # go-live (#284 listed turning them on with `publish_dns`), because moving location has to delete
  # the Primary IPs and the volume, and
  #
  #     DELETE /primary_ips/<id>   ->   HTTP 423 protected
  #
  # tested against the API on a throwaway address, so the apply fails partway with the environment
  # half-moved. NOT wired to `publish_dns`, deliberately: taking a live site dark must not also strip
  # delete protection from the volume holding the database. `tofu destroy` lifts these locks itself
  # (infra/README.md), so they guard the console and the API, not OpenTofu.
  ip_delete_protection              = false
  postgres_volume_delete_protection = false
  enable_backups                    = true
  public_web                        = true

  ssh_key_ids     = var.ssh_key_ids
  ssh_public_keys = var.ssh_public_keys
  admin_cidrs     = var.admin_cidrs
  wireguard_peers = var.wireguard_peers

  # So a kubeconfig can name the host rather than an address.
  k3s_extra_tls_sans = ["k8s.${var.domain}"]
}

# ---------------------------------------------------------------------------
# Address records. The zone belongs to the bootstrap stack and is read, never managed, from here,
# so a `destroy` here removes the records and leaves the zone, its delegation and its DNSSEC key.
# ---------------------------------------------------------------------------

data "hcloud_zone" "main" {
  name = var.domain
}

locals {
  # The apex is canonical; `www` is redirected at Traefik, since a CNAME cannot sit at the apex
  # (#259). Until `publish_dns` is true this is one throwaway name, so a real certificate can be
  # issued while the domain resolves to nothing. A swap rather than two lists, because two lists
  # that could both be published is how a temporary record becomes permanent.
  hostnames = var.publish_dns ? ["@", "www"] : ["prod-check"]

  # A only. k3s runs single-stack IPv4, so Traefik's hostPorts do not exist on the node's IPv6 and
  # an AAAA record sends every IPv6-first client to a refused connection (#1941). Add AAAA back
  # only with a dual-stack cluster, which k3s cannot switch to in place.
  address_records = {
    for name in local.hostnames : "${name}/A" => {
      name  = name
      type  = "A"
      value = module.environment.k3s_ipv4
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

# ---------------------------------------------------------------------------
# The redirect domain. `event-junkie.com` serves nothing (the chart 301s it, ADR-014) but must
# resolve: production solves HTTP-01 over the name being certified, and a domain that answers
# nothing leaves a Certificate stuck in pending with every other object green (#634). `www` is
# here because it is redirected at Traefik, not in DNS. No `prod-check` equivalent: a rehearsal
# name on a domain that only redirects would be a record nobody remembers to remove.
# ---------------------------------------------------------------------------

data "hcloud_zone" "redirect" {
  count = var.redirect_domain == "" ? 0 : 1

  name = var.redirect_domain
}

locals {
  publish_redirect   = var.publish_dns && var.redirect_domain != ""
  redirect_hostnames = local.publish_redirect ? ["@", "www"] : []

  # A only, for the same reason as the apex (#1941).
  redirect_records = {
    for name in local.redirect_hostnames : "${name}/A" => {
      name  = name
      type  = "A"
      value = module.environment.k3s_ipv4
    }
  }
}

resource "hcloud_zone_rrset" "redirect" {
  for_each = local.redirect_records

  zone = data.hcloud_zone.redirect[0].name
  name = each.value.name
  type = each.value.type
  ttl  = var.dns_ttl

  records = [{ value = each.value.value }]

  labels = {
    managed-by  = "opentofu"
    environment = "production"
  }
}
