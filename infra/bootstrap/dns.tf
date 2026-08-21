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
  # standing invitation. These are the records for a domain that does neither, which is still true
  # of `event-junkie.com` and no longer true of the primary — see `mail_records` below.
  #
  # **The DKIM wildcard revoke stays, and it does not shadow a real selector.** A wildcard answers
  # only names with no record of their own, so `default2608._domainkey` returns konsoleH's key while
  # every other selector goes on being revoked. That record is konsoleH's and deliberately absent
  # from this file: whoever holds the private key owns the public one, and importing it would give
  # the next apply a way to revert a rotated key and break signing silently. docs/ops/EMAIL.md §5.
  #
  # `p=reject` still carries no `rua=`: a report address that nobody reads is worse than none, and
  # a mailbox existing does not make somebody read it.
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

  # Records for the one domain that actually receives mail (#274). Deliberately **not** in
  # `zone_records`, which is setproduct-ed across every domain: `event-junkie.com` is a defensive
  # registration and must go on saying it neither sends nor receives.
  #
  # **`include:` and not `ip4:`, and not the obvious include either.** `_spf.hetzner.com` lists
  # Hetzner's *corporate* relays and does not contain this account's server, so publishing it would
  # authorise machines that never send for us and refuse the one that does. The hosting server
  # publishes its own policy instead —
  #
  #     www750.your-server.de. TXT "v=spf1 a:www750.your-server.de a:mail.www750.your-server.de -all"
  #
  # — so including it inherits whatever Hetzner puts there, three lookups deep, and survives them
  # adding a relay without anyone here noticing. `-all` stays: the point of this record is to name
  # the one sender, not to soften the policy.
  #
  # Written from `var.mail_host` so the MX above and the SPF here cannot drift apart if the account
  # is ever migrated to another hosting server. docs/ops/EMAIL.md §5.
  mail_records = {
    mx = {
      name    = "@"
      type    = "MX"
      records = ["10 ${var.mail_host}"]
    }
    spf = {
      name    = "@"
      type    = "TXT"
      records = ["\"v=spf1 include:${trimsuffix(var.mail_host, ".")} -all\""]
    }
  }

  # One flat map so every (domain, record) pair is its own resource with a stable address.
  #
  # The mail records merge in **after**, keyed the same way. Nothing collides today; when SPF joins
  # them it will, and `event-junkie.de/spf` replacing the `-all` version by key is exactly the wanted
  # behaviour — one resource, one address, changed rather than duplicated.
  rrsets = merge(
    {
      for pair in setproduct(local.all_domains, keys(local.zone_records)) :
      "${pair[0]}/${pair[1]}" => {
        zone   = pair[0]
        record = local.zone_records[pair[1]]
      }
    },
    {
      for key, record in local.mail_records :
      "${var.primary_domain}/${key}" => {
        zone   = var.primary_domain
        record = record
      }
    },
  )
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
