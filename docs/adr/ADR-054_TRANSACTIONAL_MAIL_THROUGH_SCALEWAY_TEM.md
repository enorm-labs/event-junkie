# ADR-054: Transactional mail goes through Scaleway TEM, from its own subdomain

## Status

**Accepted (2026-10-24). The site sends transactional mail through Scaleway Transactional Email (TEM), over SMTP, from
`noreply@notify.event-junkie.de`. The role mailboxes stay on Hetzner Webhosting S and send nothing for the product.**

Decided in [#3886](https://github.com/enorm-labs/event-junkie/issues/3886), by the operator. #3886 built the sender, and
[#3887](https://github.com/enorm-labs/event-junkie/issues/3887) sends the first mail, the sign-in link.

**Does not supersede anything.** [ADR-012](ADR-012_CLOUD_PLATFORM.md) chose Hetzner for hosting and keeps that decision. This ADR adds a processor for
one purpose, outbound mail. [ADR-053](ADR-053_VISITORS_SIGN_IN_BY_EMAIL_LINK_IN_THE_BFF.md) decided that visitors sign in by e-mail link. This ADR decides
how that mail leaves the system. [`docs/ops/EMAIL.md`](../ops/EMAIL.md) covers the role mailboxes and does not change.

## Context

ADR-053 makes mail a product path. A sign-in link that does not arrive locks a visitor out. The digest of
[#3891](https://github.com/enorm-labs/event-junkie/issues/3891) sends one mail to every follower in one run.

**The obvious sender is forbidden for the digest.** The role mailboxes run on Hetzner Webhosting S. Its
[service terms](https://www.hetzner.com/de/rechtliches/webhosting/), §4 _Massenmails_, say:

> Die stündliche Versandmenge von E-Mails darf 500 Mails nicht übersteigen. Das Versenden von Massenmails/Newsletter über E-Mailkonten des Webhosting
> Accounts ist nicht gestattet. […] Hetzner Online ist berechtigt, Kundenaccounts, welche dagegen verstoßen, unmittelbar zu sperren.

A weekly digest to every follower reads as a newsletter. A suspension would also take `hello@`, `security@` and `alerts@`. The imprint and the privacy
notice name those addresses as legal contact routes.

**The expected volume**, at about one digest per account per week plus sign-in links:

| Accounts | Mails per month | At 500 per hour, the digest takes |
| -------- | --------------- | --------------------------------- |
| 1,000    | ~5,000          | 2 hours                           |
| 10,000   | ~45,000         | 20 hours                          |
| 100,000  | ~450,000        | 8 days                            |

**The constraints any option had to satisfy:**

- **No US processor** for visitor data in a product path. ADR-012 set that bar for the request path. The same reasoning applies to a visitor's address.
- **Strict DMARC.** `event-junkie.de` publishes `p=reject; sp=reject; adkim=s; aspf=s` (`infra/bootstrap/dns.tf`). A mail must align on the exact domain.
- **One operator.** A failure must be visible without someone watching blocklists.
- **Port 25 is closed.** Hetzner Cloud blocks outbound port 25 by default and opens it case by case.

## Candidate options

1. **Hetzner Webhosting SMTP.** No new processor. Forbidden for the digest, and a breach risks the legal mailboxes.
2. **Our own Postfix on Hetzner Cloud.** No new processor. Port 25 needs a request. A new IP needs weeks of warm-up, and Hetzner's ranges often appear on
   blocklists. Bounces, complaints and blocklist checks become a permanent task. A delivery failure is silent.
3. **Scaleway TEM.** A French company with EU hosting. Pay per mail. SMTP and API.
4. **Sweego, Lettermint, Mailjet, Brevo, MailerSend.** All are EU companies. Each one costs more at the volumes above, or caps its free tier per day.

## Comparison

Prices as read on 2026-10-24 from the providers' pages or, where marked, from third-party pages. Check the order form before relying on them.

| Option                   | Country        | Free tier                | ~5k / month | ~45k / month    | ~450k / month   |
| ------------------------ | -------------- | ------------------------ | ----------- | --------------- | --------------- |
| Hetzner Webhosting       | DE             | Included                 | Included    | **Not allowed** | **Not allowed** |
| Own Postfix              | DE             | —                        | Ops time    | Ops time        | Ops time        |
| **Scaleway TEM**         | **FR**         | **300 / month**          | **~€1**     | **~€11**        | **~€112**       |
| Sweego                   | FR             | 100 / day                | €10         | €25             | €270            |
| Lettermint               | NL             | 300 / month              | €10         | ~€54            | ~€500           |
| Mailjet (third-party)    | FR, Sinch (SE) | 6,000 / month, 200 / day | Free or $19 | Not checked     | Not checked     |
| Brevo (third-party)      | FR             | 300 / day                | ~$9         | ~$30            | Not checked     |
| MailerSend (third-party) | LT             | 500 / month              | $7          | Not checked     | Not checked     |

## Decision

**Option 3, Scaleway TEM.** The reason that settled it: **it is the cheapest EU option at every volume, and it charges per mail.** No plan has to be
outgrown, and a quiet month costs nothing.

The rules:

1. **SMTP, not the API.** `smtp.tem.scaleway.com:587` with STARTTLS. The username is the Scaleway project ID. The password is the secret key of an IAM
   API key. A change of provider then changes only the Secret.
2. **A sending subdomain, `notify.event-junkie.de`.** For a domain Scaleway does not host, TEM needs SPF, DKIM, DMARC and MX set by hand. An MX on
   `event-junkie.de` would collide with the Hetzner mailboxes. The subdomain also keeps the role mailboxes' reputation apart from product mail.
3. **DKIM carries DMARC.** The root's `sp=reject; adkim=s` applies to the subdomain. A DKIM signature with `d=notify.event-junkie.de` aligns strictly.
   The DNS records live in `infra/bootstrap/dns.tf`, with the values the Scaleway console shows.
4. **The IAM key may send mail and nothing else.**
5. **The role mailboxes send no product mail.** `hello@`, `security@` and `alerts@` stay on Hetzner, and only people use them.

## Consequences

- **A third processor.** Hetzner and Anthropic are the two today (LEGAL.md §7.3a). Scaleway receives a visitor's e-mail address and the mail's content.
  It is in the EU, so no transfer mechanism is needed. Its DPA must be in place before the first visitor mail.
- **The privacy notice names Scaleway in #3887**, the PR that sends the first visitor mail. Not earlier, because the notice describes what runs.
- **The default quota is 10,000 mails per month.** That is reached at about 2,500 accounts. The quota rises on request, so the request must go in before
  that point.
- **Sign-in depends on a third party.** A Scaleway outage stops new sign-ins. Existing sessions keep working.
- **A new egress path.** The BFF connects out on TCP 587. The chart denies all egress by default, so it needs a named NetworkPolicy.
- **The cost grows with use.** At the volumes above it stays below the cost of one more node.

## When to revisit

- **Volume passes about 400,000 mails a month.** TEM's Scale plan includes a dedicated IP and may then cost less.
- **Delivery to a large mailbox provider fails repeatedly**, and Scaleway's shared IPs are the cause.
- **A German provider with comparable price and terms appears.**

## References

- [#3886](https://github.com/enorm-labs/event-junkie/issues/3886) — the issue · [#398](https://github.com/enorm-labs/event-junkie/issues/398) — the epic
- [ADR-012](ADR-012_CLOUD_PLATFORM.md) · [ADR-053](ADR-053_VISITORS_SIGN_IN_BY_EMAIL_LINK_IN_THE_BFF.md) · [`docs/ops/EMAIL.md`](../ops/EMAIL.md)
- [Hetzner: Service-Vereinbarungen Webhosting](https://www.hetzner.com/de/rechtliches/webhosting/)
- [Scaleway: pricing](https://www.scaleway.com/en/pricing/managed-services/) · [TEM limits](https://scaleway.com/en/docs/transactional-email/reference-content/tem-capabilities-and-limits)
  · [SMTP configuration](https://www.scaleway.com/en/docs/transactional-email/reference-content/smtp-configuration/)
- [Lettermint pricing](https://lettermint.co/pricing) · [Sweego](https://european-alternatives.eu/product/sweego-transactional-email) ·
  [Mailjet price change](https://documentation.mailjet.com/hc/en-us/articles/25750983876763)
- [Hetzner Cloud port 25 (forum)](https://meta.discourse.org/t/ports-blocked-hetzner-cloud-server/225146?tl=en)
