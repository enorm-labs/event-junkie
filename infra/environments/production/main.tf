module "environment" {
  source = "../../modules/environment"

  environment = "production"
  # fsn1, nbg1 and hel1 are interchangeable on cost (free traffic within the eu-central zone), so
  # this is the line to change when capacity moves — see check-capacity.sh. It covers both nodes on
  # purpose: they must share a location, since every query crosses that link.
  #
  # It used to say "all three offer ARM". They advertise it; none of them will sell it, which is
  # what the server types below record. Moving location is not the lever it looks like — for ARM.
  # For x86 it is exactly the lever, and it got pulled on 2026-08-21.
  #
  # **nbg1 since 2026-08-21, mid-apply, because fsn1 ran out of cx33 between the probe and the
  # order.** `check-capacity.sh --probe production` returned ORDERABLE for both types at 00:0x. The
  # apply thirty minutes later created `production-postgres` (cx23) and then failed on the k3s node:
  #
  #     Error: error during placement (resource_unavailable)
  #
  # Re-probing immediately afterwards confirmed it: cx33 and cx43 both gone in fsn1, cx23 still
  # there — so it is the 8 GB+ CX types that went, minutes after this environment took one of the
  # last cx23s. **A successful probe means orderable at that instant and nothing about the next
  # one**, which is a real limit of that tool and is now in its header.
  #
  # Both types were orderable in nbg1 and hel1 at the same moment. nbg1 was chosen on latency —
  # Nuremberg is ~25 ms closer to a Berlin audience than Helsinki (PLATFORM_SETUP.md §1) — accepting
  # that staging is also in nbg1. That co-location costs little: staging is not production's
  # failover and never was, so the thing a second location would protect is not a thing this design
  # has.
  #
  # **It also fixed something the original choice had wrong.** Object Storage lives in fsn1, so
  # production in fsn1 would have put the database and its only off-server backups in one location.
  # Moving out separates them, which is the property backups are for. That was not the reason for
  # the move and is the better argument for it.
  #
  # Moving cost nothing today and will not be free again: the volume was empty and nothing was live.
  # Once there is data, moving is a dump and a restore, not a variable.
  location = "nbg1"

  # CX33: 4 x86 vCPU / 8 GB / 80 GB disk. The memory arithmetic behind it — including why Flux
  # rather than ArgoCD is what keeps this on an 8 GB node — is PLATFORM_SETUP.md §1 and is unchanged
  # by this: cores, memory and disk are identical to the CAX21 that stood here until 2026-08-21.
  #
  # **It is x86 because ARM cannot be bought, and that was settled by ordering rather than asking.**
  # Bare servers — no IPs, no network, `start_after_create: false` — were ordered in every
  # eu-central location on 2026-08-21. Refusals are free and return in ~0.1s:
  #
  #   cax21   fsn1 / nbg1 / hel1   refused: `unsupported location for server type (invalid_input)`
  #   cax11   fsn1 / nbg1 / hel1   refused: the same, in all three
  #   cx33    fsn1                 ORDERABLE
  #   cx23    fsn1                 ORDERABLE
  #
  # **Four of those six refusals were in locations check-capacity.sh reported as available.** That
  # is the finding, not a footnote: the advertisement is wrong in BOTH directions, and acting on a
  # green result would have moved this environment to nbg1 to collect exactly the same refusal.
  # `--probe` was added to that script so the question can be settled the way this was.
  #
  # The shortage has held since 2026-08-11. It is not an ARM-only shortage and never was — the whole
  # `cx` line was gone at the same time and has since come back, in fsn1, which is why there is now
  # something to move to.
  #
  # **And ARM was, by this point, the dearer plan.**
  # cx33 + cx23 is **€16.63/month against cax21 + cax11's €19.61**, for the same cores, the same
  # memory and the same disks. So waiting was not buying a cheaper machine; it was buying nothing at
  # a price. Whatever the ARM argument in PLATFORM_SETUP.md §1 was worth, it was not this.
  #
  # **Going back, if CAX returns.**
  # A one-line change per node — but free only until the first apply. **After that it is a REBUILD
  # of both nodes and not a resize**, because Hetzner cannot rescale across architectures and the
  # plan renders a tidy in-place update that the API then refuses mid-apply. #460's volume means the
  # database survives it; the k3s cluster does not. docs/ops/CLUSTER_BOOTSTRAP.md §Rebuilding a node.
  #
  # What keeps either direction safe is #264's multi-arch images: same chart, same tags, digests per
  # platform, nothing to re-tag. And this restores staging/production parity rather than breaking
  # it — staging has been x86 since 2026-08-13, so the two environments now match on architecture
  # for the first time.
  k3s_server_type = "cx33"

  # CX23: 2 x86 vCPU / 4 GB / 40 GB disk — spec-for-spec what CAX11 was, for the reason above.
  #
  # IPv6-only was the intent, and #270 settled that it cannot be — not on
  # a guess about `apt`, which turns out to be fine (apt.postgresql.org answers on IPv6), but on
  # **github.com publishing no AAAA record at all**, checked 2026-08-18. wal-g ships only as a
  # GitHub release, so an IPv6-only node cannot install the one thing standing between us and losing
  # the database, and `backups.sh` stops the boot rather than coming up without it.
  #
  # ~€0.50/month for the address, against a NAT gateway (infra/AGENTS.md) that is only worth
  # building if the address itself is the objection. It is not: the firewall still admits nothing
  # inbound, so this buys egress and not exposure.
  postgres_server_type = "cx23"
  postgres_public_ipv4 = true

  # Locks the addresses and the database volume against a console mis-click.
  #
  # **Both are false until go-live, and turning them on is on the checklist with `publish_dns`**
  # ([#284](https://github.com/enorm-labs/event-junkie/issues/284)). While this environment is dark
  # it holds no data and gets rebuilt on a whim; protection there buys nothing and costs a failed
  # apply every time something location-bound moves. That is not hypothetical — it is why they are
  # false: the 2026-08-21 move to nbg1 had to delete four Primary IPs and a volume, and
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
  # The old comment here said "neither stops `tofu destroy` — the provider lifts its own locks".
  # The API demonstrably does not lift them; whether the *provider* does is untested, and the same
  # claim in staging's main.tf is now suspect for the same reason. Do not rely on either reading:
  # if something protected has to go, turn protection off in its own apply first.
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
  #
  # Until `publish_dns` is true this is one throwaway name instead, so a real certificate can be
  # issued and the whole TLS path exercised while the domain still resolves to nothing. See the
  # variable. The swap is deliberate: two lists that could both be published is how a temporary
  # record becomes permanent.
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
