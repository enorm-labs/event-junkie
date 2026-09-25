# The DNS zones live in `bootstrap/`, not in either environment, and that split is why this stack
# exists: a `tofu destroy` on an environment is routine, and a zone in that blast radius is not.
# Delegation would survive (Hetzner's nameservers are fixed); DNSSEC would not: a re-created zone
# has a new key, the DS record at INWX no longer matches, and the domain becomes unresolvable.
# `delete_protection` is the second lock.

locals {
  all_domains = concat([var.primary_domain], var.defensive_domains)

  # A domain that sends no mail and says so is not spoofable. Still true of `event-junkie.com`, no
  # longer of the primary (`mail_records` below).
  #
  # The DKIM wildcard revoke does not shadow a real selector: a wildcard answers only names with no
  # record of their own, so `default2608._domainkey` returns konsoleH's key. That record is
  # deliberately absent here: importing it would let the next apply revert a rotated key and break
  # signing silently (docs/ops/EMAIL.md §5). `p=reject` carries no `rua=`: a report address nobody
  # reads is worse than none.
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

  # Before requesting any certificate: stops every other CA issuing for the name. `issuewild` is
  # separate because `issue` does not authorise wildcards, and staging's DNS-01 brings one within
  # reach (PLATFORM_SETUP.md §4a).
  caa = {
    name = "@"
    type = "CAA"
    records = [
      "0 issue \"letsencrypt.org\"",
      "0 issuewild \"letsencrypt.org\"",
    ]
  }

  zone_records = merge(local.antispoofing, { caa = local.caa })

  # Records for the one domain that receives mail (#274), NOT in `zone_records`: `event-junkie.com`
  # is defensive and must go on saying it neither sends nor receives.
  #
  # `include:` and not `ip4:`, and not `_spf.hetzner.com`, which lists Hetzner's corporate relays
  # and not this account's server. The hosting server publishes its own policy,
  #
  #     www750.your-server.de. TXT "v=spf1 a:www750.your-server.de a:mail.www750.your-server.de -all"
  #
  # so including it survives Hetzner adding a relay. `-all` stays. Written from `var.mail_host` so
  # the MX and the SPF cannot drift apart (docs/ops/EMAIL.md §5).
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

  # One flat map so every (domain, record) pair is its own resource with a stable address. The mail
  # records merge in after, keyed the same way, so `event-junkie.de/spf` replaces the `-all` version
  # by key: one resource, changed rather than duplicated.
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

locals {
  # Search Console's domain-property tokens (#288). Public by design: Google reads them from DNS, so
  # anyone can. A name has one TXT set, so each token joins its domain's SPF set by the `spf` key.
  site_verification = {
    "event-junkie.de"  = "google-site-verification=-F9q9kIq-uPFzrrLpc7oRC9tPvZFXpaKDl77Ppvef70"
    "event-junkie.com" = "google-site-verification=gz6UP4DpQGjAgz7pNgBhbjAdb0aBAF3MFtvdOAoiTzQ"
  }

  dns_rrsets = {
    for key, rrset in local.rrsets : key => (
      endswith(key, "/spf") && contains(keys(local.site_verification), rrset.zone)
      ? merge(rrset, {
        record = merge(rrset.record, {
          records = concat(rrset.record.records, ["\"${local.site_verification[rrset.zone]}\""])
        })
      })
      : rrset
    )
  }
}

resource "hcloud_zone" "main" {
  for_each = toset(local.all_domains)

  name = each.value
  mode = "primary"
  ttl  = var.dns_ttl

  # Stops deletion through the console and the API, not OpenTofu, which lifts its own locks before
  # destroying. `prevent_destroy` below holds the line against `tofu destroy`; both are wanted.
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
  for_each = local.dns_rrsets

  zone = hcloud_zone.main[each.value.zone].name
  name = each.value.record.name
  type = each.value.record.type
  ttl  = var.dns_ttl

  records = [for value in each.value.record.records : { value = value }]

  labels = {
    managed-by = "opentofu"
  }
}
