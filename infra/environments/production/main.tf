module "environment" {
  source = "../../modules/environment"

  environment = "production"
  # Covers both nodes on purpose: they must share a location, every query crossing that link. All
  # three eu-central locations advertise ARM and none will sell it, so this is not the lever for ARM;
  # for x86 it is exactly the lever, and check-capacity.sh's header carries the caveat that a
  # successful probe means orderable at that instant and nothing about the next.
  #
  # **Not fsn1, and that constraint outlives the capacity one:** Object Storage lives there, so
  # production in fsn1 would put the database and its only off-server backups in one location. nbg1
  # over hel1 on latency (~25 ms closer to Berlin, PLATFORM_SETUP.md §1), accepting that staging is
  # in nbg1 too — staging is not production's failover, so a second location protects nothing here.
  #
  # **Moving will not be free again.** Once there is data it is a dump and a restore, not a variable.
  location = "nbg1"

  # CX33: 4 x86 vCPU / 8 GB / 80 GB disk. The memory arithmetic — including why Flux rather than
  # ArgoCD keeps this on an 8 GB node — is PLATFORM_SETUP.md §1.
  #
  # **It is x86 because ARM cannot be bought, and that is settled by ordering rather than asking.**
  # cax11 and cax21 are refused in all three eu-central locations with `unsupported location for
  # server type`, four of those in locations check-capacity.sh reports as available. The
  # advertisement is wrong in BOTH directions, which is what `--probe` exists for: bare servers, no
  # IPs, `start_after_create: false`, refusals in ~0.1s.
  #
  # **ARM is also the dearer plan.** cx33 + cx23 is **€16.63/month against cax21 + cax11's €19.61**
  # for the same cores, memory and disks, so waiting for CAX buys nothing at a price.
  #
  # **Going back, if CAX returns, is a REBUILD of both nodes and not a resize** — free only until the
  # first apply. Hetzner cannot rescale across architectures, and the plan renders a tidy in-place
  # update the API then refuses mid-apply. #460's volume means the database survives, the k3s cluster
  # does not (CLUSTER_BOOTSTRAP.md §Rebuilding a node). Images are safe either way: #264 is
  # multi-arch.
  k3s_server_type = "cx33"

  # CX23: 2 x86 vCPU / 4 GB / 40 GB disk — spec-for-spec what CAX11 was, for the reason above.
  #
  # IPv6-only was the intent, and #270 settled that it cannot be — not on a guess about `apt`, which
  # is fine, but on **github.com publishing no AAAA record at all**. wal-g ships only as a GitHub
  # release, so an IPv6-only node cannot install the one thing between us and losing the database,
  # and `backups.sh` stops the boot rather than coming up without it.
  #
  # ~€0.50/month for the address, against a NAT gateway (infra/AGENTS.md) worth building only if the
  # address itself is the objection. It is not: the firewall still admits nothing inbound, so this
  # buys egress and not exposure.
  postgres_server_type = "cx23"
  postgres_public_ipv4 = true

  # Locks the addresses and the database volume against a console mis-click.
  #
  # **Both are false until go-live, and turning them on is on the checklist with `publish_dns`**
  # ([#284](https://github.com/enorm-labs/event-junkie/issues/284)). While this environment is dark
  # it holds no data and gets rebuilt on a whim; protection there buys nothing and costs a failed
  # apply every time something location-bound moves. That is not hypothetical — it is why they are
  # false: moving location has to delete the Primary IPs and the volume, and
  #
  #     DELETE /primary_ips/<id>   ->   HTTP 423 protected
  #
  # tested directly against the API on a throwaway address. The delete is refused, not silently
  # skipped, so the apply fails partway with the environment half-moved.
  #
  # **They are NOT wired to `publish_dns`, deliberately**, tempting as it is — that would mean
  # taking a live site dark also stripped delete protection from the volume holding the database,
  # at exactly the moment somebody is already having a bad day.
  #
  # Whether `tofu destroy` lifts these is untested — the API demonstrably does not, and staging's
  # main.tf claims otherwise. Do not rely on either reading: if something protected has to go, turn
  # protection off in its own apply first.
  ip_delete_protection              = false
  postgres_volume_delete_protection = false
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
# Address records. The zone belongs to the bootstrap stack and is read, never managed, from here —
# so a `destroy` in this directory removes the records and leaves the zone, its delegation and its
# DNSSEC key untouched. That separation is the point of the two-stack split.
# ---------------------------------------------------------------------------

data "hcloud_zone" "main" {
  name = var.domain
}

locals {
  # The apex is canonical; `www` carries the same addresses and is redirected at Traefik rather than
  # in DNS, a CNAME being unable to sit at the apex and a redirect wanting to be one rule in one
  # place (#259). Until `publish_dns` is true this is one throwaway name instead, so a real
  # certificate can be issued and the TLS path exercised while the domain resolves to nothing. The
  # swap is deliberate: two lists that could both be published is how a temporary record becomes
  # permanent.
  hostnames = var.publish_dns ? ["@", "www"] : ["prod-check"]

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

# ---------------------------------------------------------------------------
# The redirect domain
#
# `event-junkie.com` serves nothing — the chart 301s it to the apex (ADR-014, `ingress.redirectHosts`)
# — but it still has to resolve, because production solves HTTP-01 and the challenge is served over
# the name being certified. A redirect domain that answers nothing produces a Certificate stuck in
# pending and an Ingress serving TLS errors, with every other object on the cluster green (#634).
#
# `www` is here for the same reason it is in `hostnames` above: it is redirected at Traefik, not in
# DNS, so it needs an address of its own.
#
# **No `prod-check` equivalent.** One rehearsal name on the primary domain exercises the whole TLS
# path; a second on a domain that only redirects would be a record nobody remembers to remove.
# ---------------------------------------------------------------------------

data "hcloud_zone" "redirect" {
  count = var.redirect_domain == "" ? 0 : 1

  name = var.redirect_domain
}

locals {
  publish_redirect = var.publish_dns && var.redirect_domain != ""

  redirect_records = {
    for pair in setproduct(local.publish_redirect ? ["@", "www"] : [], ["A", "AAAA"]) :
    "${pair[0]}/${pair[1]}" => {
      name  = pair[0]
      type  = pair[1]
      value = pair[1] == "A" ? module.environment.k3s_ipv4 : module.environment.k3s_ipv6
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
