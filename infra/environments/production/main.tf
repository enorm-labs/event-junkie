module "environment" {
  source = "../../modules/environment"

  environment = "production"
  # fsn1, nbg1 and hel1 are interchangeable on cost (free traffic within the eu-central zone), so
  # this is the line to change when capacity moves — see check-capacity.sh. It covers both nodes on
  # purpose: they must share a location, since every query crosses that link.
  #
  # It used to say "all three offer ARM". They advertise it; none of them will sell it, which is
  # what the server types below now record. Moving location is not the lever it looks like.
  #
  # Moving it is free today and only today: this environment has never been applied, so there are no
  # Primary IPs and no volume pinning it. Both are location-bound once they exist (staging, #460).
  location = "fsn1"

  # CX33: 4 x86 vCPU / 8 GB / 80 GB disk. The memory arithmetic behind it — including why Flux
  # rather than ArgoCD is what keeps this on an 8 GB node — is PLATFORM_SETUP.md §1 and is unchanged
  # by this: cores, memory and disk are identical to the CAX21 that stood here until 2026-08-21.
  #
  # ## It is x86 because ARM cannot be bought, and that was settled by ordering rather than asking
  #
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
  # ## And ARM was, by this point, the dearer plan
  #
  # cx33 + cx23 is **€16.63/month against cax21 + cax11's €19.61**, for the same cores, the same
  # memory and the same disks. So waiting was not buying a cheaper machine; it was buying nothing at
  # a price. Whatever the ARM argument in PLATFORM_SETUP.md §1 was worth, it was not this.
  #
  # ## Going back, if CAX returns
  #
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
