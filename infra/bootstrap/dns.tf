# The DNS zones live here, in `bootstrap/`, and not in either environment.
#
# That split is the whole reason this stack exists. A `tofu destroy` on an environment is meant to
# be a routine thing — it is how the "destroy/apply produces a working environment" promise gets
# tested — and a zone caught in that blast radius is not routine at all. Delegation itself would
# survive, because Hetzner's nameservers are fixed. **DNSSEC would not**: a re-created zone has a
# new key, the DS record at INWX no longer matches, and the domain becomes *unresolvable* rather
# than merely wrong.
#
# Putting the zone somewhere `destroy` never reaches makes that failure structurally impossible
# instead of something to remember at the wrong moment. `delete_protection` is the second lock.

locals {
  all_domains = concat([var.primary_domain], var.defensive_domains)

  # A domain that sends no mail and says so is not spoofable; a fresh domain with no SPF is a
  # standing invitation. All three records are true today and stay true until #274 gives the
  # project real mailboxes — at which point all three have to change together.
  #
  # `p=reject` carries no `rua=`: a report address that nobody reads is worse than none, and there
  # is no mailbox to point it at yet.
  antispoofing = {
    spf = {
      name    = "@"
      type    = "TXT"
      records = ["\"v=spf1 -all\""]
    }
    dmarc = {
      name    = "_dmarc"
      type    = "TXT"
      records = ["\"v=DMARC1; p=reject; sp=reject; adkim=s; aspf=s\""]
    }
    dkim_revoke = {
      name    = "*._domainkey"
      type    = "TXT"
      records = ["\"v=DKIM1; p=\""]
    }
  }

  # Before requesting any certificate, not after. Costs nothing and stops every other CA issuing
  # for the name. `issuewild` is listed separately because a CAA `issue` record does not authorise
  # wildcards, and staging's DNS-01 setup brings one within reach (PLATFORM_SETUP.md §4a).
  caa = {
    name = "@"
    type = "CAA"
    records = [
      "0 issue \"letsencrypt.org\"",
      "0 issuewild \"letsencrypt.org\"",
    ]
  }

  zone_records = merge(local.antispoofing, { caa = local.caa })

  # One flat map so every (domain, record) pair is its own resource with a stable address.
  rrsets = {
    for pair in setproduct(local.all_domains, keys(local.zone_records)) :
    "${pair[0]}/${pair[1]}" => {
      zone   = pair[0]
      record = local.zone_records[pair[1]]
    }
  }
}

resource "hcloud_zone" "main" {
  for_each = toset(local.all_domains)

  name = each.value
  mode = "primary"
  ttl  = var.dns_ttl

  # Stops deletion through the console, the API or any other tool. It does **not** stop OpenTofu:
  # the provider lifts its own locks before destroying, which the provider docs state plainly. The
  # `prevent_destroy` below is what actually holds the line against a `tofu destroy` in this
  # directory, and given the DNSSEC failure above, both are wanted.
  delete_protection = true

  labels = {
    managed-by = "opentofu"
    project    = "event-junkie"
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "hcloud_zone_rrset" "defaults" {
  for_each = local.rrsets

  zone = hcloud_zone.main[each.value.zone].name
  name = each.value.record.name
  type = each.value.record.type
  ttl  = var.dns_ttl

  records = [for value in each.value.record.records : { value = value }]

  labels = {
    managed-by = "opentofu"
  }
}
