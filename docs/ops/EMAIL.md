# The role mailboxes — ordering them at Hetzner and wiring up the DNS

`hello@event-junkie.de` and `security@event-junkie.de` are **published and do not exist**. This page is how to change that: what to order, what it costs, which
DNS records have to change together, and what breaks if they do not.

**This is [#274](https://github.com/enorm-labs/event-junkie/issues/274)**, and [LEGAL.md](../LEGAL.md) §14 item 6 is the reason it blocks go-live — the imprint,
the privacy notice, `SECURITY.md` and `CODE_OF_CONDUCT.md` all name one of these addresses as a reporting route, and every one of them is currently a dead
address.

**Nothing here may be applied by an agent.** [`infra/AGENTS.md`](../../infra/AGENTS.md) opens with `tofu plan/apply/destroy/import` never being run on
initiative, and the ordering half is a purchase. The sequence below is written to be read, planned, and then applied by a human.

## 1. What already sends mail to `hello@`, today

Worth knowing before this reads like a purely legal errand — **something is already writing to that address and being swallowed**:

| Consumer                             | Where                                                         | What arrives                                          |
| ------------------------------------ | ------------------------------------------------------------- | ----------------------------------------------------- |
| Let's Encrypt ACME account (staging) | `deploy/clusters/staging/helm-release.yaml`                   | Expiry warnings, account and rate-limit notices       |
| Let's Encrypt ACME account (prod)    | `deploy/clusters/production/helm-release.yaml`                | The same, for the certificates that matter            |
| Code of Conduct enforcement          | `CODE_OF_CONDUCT.md`                                          | Incident reports                                      |
| Imprint and privacy notice           | `events-frontend/src/lib/legal.ts` → both `PrivacyView.*.vue` | Art. 15–21 requests, which have a **statutory clock** |

A GDPR request that bounces is not a request that failed politely; the month starts running when it is sent. That is the sharp end of this issue.

**`hello@event-junkie.de` is also the OpenObserve root login** ([SECRETS.md](SECRETS.md)). That is a username, not a mailbox, and creating the real address
changes nothing about it — but the two look identical in a password manager, so they are worth a note.

## 2. What to buy, and what it costs

Hetzner has **no standalone mailbox product**. Mail comes with a web-hosting package, and the smallest one is the whole answer:

| Item                                  | Gross / month | Gross / year | Note                                                              |
| ------------------------------------- | ------------- | ------------ | ----------------------------------------------------------------- |
| **Webhosting S**                      | €1.90         | €22.80       | 10 GB NVMe, **unlimited mailboxes and forwarders**                |
| **External domain** `event-junkie.de` | €0.76         | €9.12        | Because the domain is registered at INWX, not Hetzner             |
| **Total**                             | **€2.66**     | **€31.92**   | Both addresses; a second mailbox on the same domain costs nothing |
| _optional_ `event-junkie.com` as well | +€0.76        | +€9.12       | Only if the defensive domain should receive mail — it should not  |
| _if the 10 GB is ever exceeded_       | +€0.92 / GB   |              | Mail counts against the package's webspace, not a separate quota  |

**No setup fee and no minimum term** — S can be cancelled the same day. The larger packages (M €4.90, L €9.90, XL €19.90) differ only in webspace, databases and
PHP resources; **all four have unlimited mailboxes**, so nothing about two role addresses argues for anything above S.

_List prices read 2026-08-21 from Hetzner's own pages, gross, 19% VAT included — the same convention as [COSTS.md](COSTS.md). Confirm them in the order form
rather than trusting this table; it is a snapshot, and it is not derived from an invoice._

**This adds a line to [COSTS.md](COSTS.md) once it is actually ordered**, not before. That page reports measured charges, and a plan is not a charge.

### Why Hetzner rather than a mail specialist

Not price. **Processor count.**

`PROCESSOR_CONTRACTS_PENDING` in `events-frontend/src/lib/legal.ts` is `false` because the Hetzner Art. 28 contract was concluded on 2026-08-19 at
<https://accounts.hetzner.com/account/dpa> — and that contract is **account-level**, so a web-hosting package ordered under the same account is covered by the
agreement that already exists. Mailbox.org, Fastmail, Migadu or Google would each be a **second processor**: a new DPA, a new row in §5 of both privacy notices,
and `PROCESSOR_CONTRACTS_PENDING` back to `true` until the paperwork lands. The constant's own comment says exactly that.

A mail specialist is the better product. For two role mailboxes that receive a handful of messages, the better product is not worth a second Art. 28
relationship — and if one is ever wanted, this decision is €1.90/month deep and reversible.

**Either way, §7.3a of [LEGAL.md](../LEGAL.md) has to change**, because an email address is a category of personal data the notice's processing inventory was not
written against. Choosing Hetzner avoids the contract work; it does not avoid the disclosure work.

## 3. Ordering it

1. Sign in at <https://accounts.hetzner.com/> — the same account that holds Cloud and the DPA. Do **not** create a second account; that is what would split the
   contract.
2. Order **Webhosting S** from <https://www.hetzner.com/webhosting/>. Do not order a domain with it — `event-junkie.de` stays at INWX.
3. Wait for the provisioning mail. It names the **web-hosting server the account landed on**, and that name is the MX target in §5. **Ordered 2026-08-21, this
   account is on `www750.your-server.de`** — `167.235.121.178`, `2a01:4f8:1061:21e6::2`, account name `event-junkie`. It is account-specific: copying a server
   name out of a blog post is how mail ends up delivered to a stranger's machine, and **if Hetzner ever migrates the account, the MX and the SPF in §5 both
   move with it.**
4. Sign in to **konsoleH** at <https://konsoleh.hetzner.com/>.

## 4. Adding the domain and the two mailboxes in konsoleH

1. **Domains → add `event-junkie.de` as a domain without registration** (_Domain ohne Registrierung_ / external domain). This is the €0.76/month line. konsoleH
   will confirm it cannot manage the nameservers — **that is correct and expected**: the zone lives in Hetzner Cloud DNS and is owned by OpenTofu (§5).
2. **E-Mail → create `hello@event-junkie.de`** with a generated password. Store it in the password manager under `Accounts/mail` per
   [CREDENTIALS.md](../CREDENTIALS.md).
3. **Create `security@event-junkie.de`** the same way — a **separate mailbox, not an alias**. They have different audiences and different retention
   expectations, and an alias cannot be handed to someone else later without handing over `hello@` too.
4. **Set a forward on each** to the address actually read day to day, keeping a copy on the server. #274's "done when" is not _the mailbox exists_ — it is _a
   test message arrives somewhere a human reads_, and a mailbox that only fills up quietly fails that in a way nobody notices for months.
5. **Enable DKIM** for the domain (_E-Mail → Mailsicherheit_). konsoleH generates the key pair, publishes nothing (the nameservers are not its), and shows you
   the **selector** and the **public key**. Both go into §5.

## 5. The DNS half — and it is not clicking in a console

**`event-junkie.de` is delegated to Hetzner's nameservers, and the zone is OpenTofu's** — `infra/bootstrap/dns.tf`, `hcloud_zone` and `hcloud_zone_rrset`. A
record added by hand in the Cloud Console is a record the next `tofu plan` proposes to delete. Everything below is a code change.

**Four records change together, and the file says so.** `dns.tf` describes the current SPF/DMARC/DKIM triple as _"all three are true today and stay true until
#274 gives the project real mailboxes — at which point all three have to change together"_. This is that moment.

| Record         | Today                                  | After                                           | Why                                                                       |
| -------------- | -------------------------------------- | ----------------------------------------------- | ------------------------------------------------------------------------- |
| `@ MX`         | _absent_                               | `10 www750.your-server.de.`                     | Without it nothing is delivered anywhere                                  |
| `@ TXT` (SPF)  | `v=spf1 -all`                          | `v=spf1 include:www750.your-server.de -all`     | `-all` means _this domain sends no mail_, and it is about to send replies |
| `SELECTOR TXT` | `*._domainkey` → `v=DKIM1; p=`         | a real key at the konsoleH selector             | The wildcard revokes every selector; the specific name overrides it       |
| `_dmarc TXT`   | `p=reject; sp=reject; adkim=s; aspf=s` | unchanged, **`rua=` only if somebody reads it** | Strict alignment still holds — see below                                  |

**The obvious SPF include is the wrong one, and this page said so before it was checked.** `include:_spf.hetzner.com` looks right and is not: `_spf4`/`_spf6`
under it are explicit lists of ~19 IPs in `213.133.*`, `78.46.*`, `85.10.*`, `88.198.*` and `213.95.*` — **Hetzner's own corporate relays**, and
`167.235.121.178` is not among them. Publishing it would authorise machines this account never sends from, and fail to authorise the one it does.

The hosting server publishes its own policy, which is the thing to inherit:

```console
$ dig +short TXT www750.your-server.de
"v=spf1 a:www750.your-server.de a:mail.www750.your-server.de -all"
```

`include:www750.your-server.de` therefore tracks whatever Hetzner puts there — three lookups, well inside SPF's limit of ten, and self-maintaining in a way a
hard-coded `ip4:` is not. Keep the trailing `-all`.

**Treat even that as a hypothesis until a message proves it.** konsoleH's submission and webmail host is the _shared_ `mail.your-server.de` — `78.46.5.205`,
which the include above does **not** cover and which is not in `_spf.hetzner.com` either. Whether outbound mail leaves from the account's own server or from
that shared front end is a question **one test message answers and no amount of documentation does** (§7). Publish SPF from the `Received` chain, not from a
guess — that is why §5 is two applies rather than one.

**DMARC's strict alignment survives this** and should not be loosened reflexively: mail sent through konsoleH carries an envelope sender and a DKIM `d=` of
`event-junkie.de`, so `aspf=s` and `adkim=s` both align. If a test lands in spam, read the `Authentication-Results` header before touching the policy — DMARC is
the last thing to weaken and the first thing people weaken.

**`rua=` is still optional and still a judgement call.** `dns.tf` left it out deliberately: _"a report address that nobody reads is worse than none"_. A mailbox
existing does not make that untrue. Add it when someone will actually open the aggregate reports — and if so, point it at a third address rather than at
`security@`, which should stay a human channel.

### The shape of the change

`local.zone_records` is applied to **every** domain via `setproduct`, and `event-junkie.com` is a defensive registration that must keep saying _I send and
receive nothing_. So the mail records cannot go in that map. Merge a primary-domain-only map **after** it — the `"${domain}/${record}"` keys make the SPF
override a clean replacement rather than a duplicate:

```hcl
locals {
  # Only the domain that actually receives mail. event-junkie.com keeps `v=spf1 -all`, no MX and the
  # DKIM revoke — the correct posture for a name that exists so nobody else has it.
  mail_records = {
    mx = {
      name    = "@"
      type    = "MX"
      records = ["10 www750.your-server.de."] # this account's server, from the provisioning mail
    }
    spf = {
      name    = "@"
      type    = "TXT"
      records = ["\"v=spf1 include:www750.your-server.de -all\""] # after §7 step 2 confirms it
    }
    dkim = {
      name    = "SELECTOR._domainkey" # the selector konsoleH shows after "DKIM aktivieren"
      type    = "TXT"
      records = [provider::hcloud::txt_record("v=DKIM1; k=rsa; p=MIIBIjAN...")]
    }
  }

  rrsets = merge(
    { for pair in setproduct(local.all_domains, keys(local.zone_records)) : ... }, # unchanged
    { for key, record in local.mail_records :
      "${var.primary_domain}/${key}" => { zone = var.primary_domain, record = record }
    },
  )
}
```

Three details in that snippet each cost something to discover:

- **The MX priority lives inside the value string.** `hcloud_zone_rrset` records take a single `value`; there is no separate priority field. The **trailing dot
  is required** — without it the target is treated as relative and becomes `www750.your-server.de.event-junkie.de.`
- **`provider::hcloud::txt_record()` chunks the DKIM key for you.** A TXT record is one or more quoted strings of at most 255 characters, and an RSA-2048 public
  key is longer than that. Hand-quoting it is the classic silent DKIM failure — the record exists, resolvers return it, and no verifier can parse it.
- **The wildcard revoke stays.** `*._domainkey` with `p=` only answers names that have no record of their own, so publishing `SELECTOR._domainkey` overrides it
  for that selector while every other selector remains revoked. That is the wanted behaviour, not a conflict to clean up.

### Applying it, in two sittings rather than one

Receiving needs only the MX; sending needs an SPF value that cannot be known until something has been sent. So:

```sh
cd infra/bootstrap
tofu plan -out=mx.tfplan            # 1 to add: the MX rrset. Nothing else.
tofu apply mx.tfplan
```

That alone ends the dead-address problem — mail arrives, and `v=spf1 -all` stays true because nothing has sent yet. **A domain that receives and does not send
is a coherent posture**, not a half-finished one; what `dns.tf` warns against is SPF drifting out of step with reality, and at this point it is exactly in step.

Then create the mailboxes (§4), run §7 step 2, read the sending IP off the headers, and only then:

```sh
tofu plan -out=mail.tfplan          # 2 to add (DKIM, and _dmarc if rua), 1 to change (SPF)
tofu apply mail.tfplan
```

**Stop if either count differs.** `bootstrap/` holds the zones, `delete_protection` and `prevent_destroy` — a plan that proposes to destroy anything here is a plan
that ends with an unresolvable domain, because a re-created zone gets a new DNSSEC key and the DS record at INWX stops matching.

## 6. Reading the mail

| Setting  | Value                                              |
| -------- | -------------------------------------------------- |
| IMAP     | `mail.your-server.de`, port **993**, SSL/TLS       |
| SMTP     | `mail.your-server.de`, port **465**, SSL/TLS       |
| Username | the **full address**, e.g. `hello@event-junkie.de` |
| Webmail  | <https://webmail.your-server.de/>                  |

The client hostname is the generic `mail.your-server.de`; the **MX target is the account's own server** from §3 step 3. They are different names for a reason
and swapping them produces an authentication failure that reads like a wrong password.

## 7. Proving it works

```sh
dig +short MX  event-junkie.de @1.1.1.1
dig +short TXT event-junkie.de @1.1.1.1              # -all until the second apply, include: after
dig +short TXT SELECTOR._domainkey.event-junkie.de @1.1.1.1
dig +short TXT _dmarc.event-junkie.de @1.1.1.1
```

Then the part DNS cannot answer, in this order — step 2 is what makes the SPF value in §5 a measurement rather than a guess:

1. **Send a message to each address from an outside account** and confirm it arrives — in the mailbox _and_ at the forward. This works with the MX alone.
2. **Reply from each to a Gmail or Outlook address, and read the `Received:` chain** for the IP the message actually left from. Expect `spf=fail` at this point:
   the domain still publishes `v=spf1 -all`, and that is the correct answer to a question nobody had asked yet. **The failure does not hide the header** — the
   sending IP is right there, and it is the input to the second apply.
3. **Apply the SPF and DKIM records** (§5, second sitting), wait out the TTL, and send again. Now `Authentication-Results` should read `spf=pass`, `dkim=pass`,
   `dmarc=pass`. Anything less means §5 is not finished, and it is far cheaper to find here than after the first message to a stranger.
4. **Watch for the first Let's Encrypt notice.** Certificates renew on their own schedule, so this is a slow signal — but it is the one that proves the address
   is reachable by a real sender rather than by you.

**There is no monitoring on any of this.** A mailbox that stops receiving looks exactly like a quiet week, which is the same blindness
[#618](https://github.com/enorm-labs/event-junkie/issues/618) records for importers and [OPENOBSERVE.md](OPENOBSERVE.md) records for dropped metrics. If these
addresses matter, a periodic test message is the cheap version of watching them.

## 8. What else has to change once they exist

Ordering the mailbox is the small half. These are the edits that close #274 rather than merely paying for it:

| File                                 | Change                                                                                                                |
| ------------------------------------ | --------------------------------------------------------------------------------------------------------------------- |
| `SECURITY.md`                        | Says _"once `event-junkie.de` is registered"_ — it has been since **2026-08-10**. The blocker was always the mailbox  |
| `.github/ISSUE_TEMPLATE/config.yml`  | The advisory-form link is labelled for security but really carries privacy requests; #274 asks for it to be revisited |
| `docs/LEGAL.md` §7.3a and §14 item 6 | Email as a processed category; the item closes                                                                        |
| `docs/CREDENTIALS.md` row 7          | Provider chosen, mailbox credentials stored                                                                           |
| `docs/LINKS.md`                      | The "do not exist yet" paragraph                                                                                      |
| `docs/ops/COSTS.md`                  | A new line — **after the first invoice**, with a real number                                                          |
| `events-frontend/src/lib/legal.ts`   | Nothing, if Hetzner. **`PROCESSOR_CONTRACTS_PENDING = true`, if not**                                                 |

## Sources

Read 2026-08-21. Prices and product boundaries change; the lookups in §5 and §7 are the ones that verify themselves.

- [Hetzner Webhosting](https://www.hetzner.com/webhosting/) and the [2025 package announcement](https://www.hetzner.com/de/pressroom/new-webhosting-2025/)
- [Hetzner Docs — Webhosting overview](https://docs.hetzner.com/de/konsoleh/general/webhosting/overview/), where the €0.76 external-domain fee is stated
- [Hetzner Docs — Mailsicherheit (SPF, DKIM, DMARC)](https://docs.hetzner.com/de/konsoleh/account-management/email/mailsecurity/)
- [Hetzner Docs — email account configuration](https://docs.hetzner.com/konsoleh/account-management/email/setting-up-an-email-account/)
- [Hetzner Docs — MX records](https://docs.hetzner.com/networking/dns/record-types/mx-record/)
- [`hcloud_zone_rrset`](https://registry.terraform.io/providers/hetznercloud/hcloud/latest/docs/resources/zone_rrset) for the record value format and the
  `txt_record` helper
