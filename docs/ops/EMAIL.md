# The role mailboxes — ordering them at Hetzner and wiring up the DNS

`hello@event-junkie.de`, `security@event-junkie.de` and `alerts@event-junkie.de` are **live**, on Hetzner Webhosting S. This page is how they got there and how to rebuild them. What to order, what
it costs, which DNS records carry them, and what breaks if those drift apart.

## The short version

- **Hetzner Webhosting S, €1.90/month**, plus €0.76/month for the domain slot. Chosen for the **processor count**, not the price — §2.
- **konsoleH calls a domain an _Account_.** Select the domain, never the hosting name, or the mail menus do not appear. §4 is where this stalls.
- **DKIM lives in konsoleH, not in OpenTofu.** Whoever holds the private key owns the public record, so `dns.tf` deliberately does not carry it. §5.
- **Test with mail-tester, not by mailing yourself.** Under `p=reject` a failing message is refused mid-conversation, so there are no headers to read. §7.

**This is [#274](https://github.com/enorm-labs/event-junkie/issues/274).** The imprint, the privacy notice, `SECURITY.md` and `CODE_OF_CONDUCT.md` each name one
of these addresses as a reporting route. Every one of them was dead until these mailboxes existed. Both directions are proven: mail arrives, and replies
authenticate `spf=pass` / `dkim=pass` / `dmarc=pass` against a `p=reject` policy (§7).

**Read it as a build log as much as an instruction.** Three of the things below were asserted, checked, and found wrong — the SPF include, konsoleH's navigation,
and the verification method. Each correction is kept in place rather than tidied away, because the wrong version is the one a reader is likely to arrive with.

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

A GDPR request that bounces is not a request that failed politely. The month starts running when it is sent. That is the sharp end of this issue.

**`hello@event-junkie.de` is also the OpenObserve root login** ([SECRETS.md](SECRETS.md)). That is a username, not a mailbox, and creating the real address
changes nothing about it. The two look identical in a password manager, so they are worth a note.

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
PHP resources. **All four carry unlimited mailboxes**, so nothing about two role addresses argues for anything above S.

_List prices read 2026-08-21 from Hetzner's own pages, gross, 19% VAT included — the same convention as [COSTS.md](COSTS.md). Confirm them in the order form
rather than trusting this table. It is a snapshot, and it is not derived from an invoice._

**This adds a line to [COSTS.md](COSTS.md) once it is actually ordered**, not before. That page reports measured charges, and a plan is not a charge.

### Why Hetzner rather than a mail specialist

Not price. **Processor count.**

`PROCESSOR_CONTRACTS_PENDING` in `events-frontend/src/lib/legal.ts` is `false` because the Hetzner Art. 28 contract is concluded, at
<https://accounts.hetzner.com/account/dpa>. That contract is **account-level**, so a web-hosting package ordered under the same account is covered by the
agreement that already exists. Mailbox.org, Fastmail, Migadu or Google would each be a **second processor**. Each means a new DPA, a new row in §5 of both
privacy notices, and `PROCESSOR_CONTRACTS_PENDING` back to `true` until the paperwork lands. The constant's own comment says exactly that.

A mail specialist is the better product. For two role mailboxes that receive a handful of messages, the better product is not worth a second Art. 28
relationship — and if one is ever wanted, this decision is €1.90/month deep and reversible.

**Choosing Hetzner avoided the contract work, not the disclosure work.** §7.3a of [LEGAL.md](../LEGAL.md) records what the mailboxes hold, and §5 of
the notice states how long a mail is kept.

## 3. Ordering it

1. Sign in at <https://accounts.hetzner.com/> — the same account that holds Cloud and the DPA. Do **not** create a second account. That is what would split the
   contract.
2. Order **Webhosting S** from <https://www.hetzner.com/webhosting/>. Do not order a domain with it — `event-junkie.de` stays at INWX.
3. Wait for the provisioning mail. It names the **web-hosting server the account landed on**, and that name is the MX target in §5. **This account is on
   `www750.your-server.de`**: `167.235.121.178`, `2a01:4f8:1061:21e6::2`, account name `event-junkie`. The name is account-specific. Copying one out of a blog
   post is how mail ends up delivered to a stranger's machine. **If Hetzner ever migrates the account, the MX and the SPF in §5 both move with it.**
4. Sign in to **konsoleH** at <https://konsoleh.hetzner.com/>.

## 4. Adding the domain and the two mailboxes in konsoleH

**There is no "add domain" button and no "create mailbox" button.** Looking for either is how this stalls. In konsoleH, the word for a domain is **Account**,
and every mail menu is contextual. A menu appears only once a _domain_ is selected. Straight after ordering, the only thing in the list is the hosting package, so the
left-hand menu is nearly empty and correctly so.

1. **Sign in to konsoleH at <https://konsoleh.hetzner.com/>** with the account name from the provisioning mail (`event-junkie`) and its own password. This is a
   separate credential from the Hetzner Console login, even though both hang off the same customer number.
2. **Select the hosting package** from the list. konsoleH's functions all act on _the currently selected account_. Nothing useful is on offer until one is
   picked.
3. **Accountverwaltung → Neuer Account.** This is the "add a domain" step under a name that does not say so. Enter `event-junkie` in the domain field **without
   the TLD** and choose `.de` from the list. Pick the option for **an account without a linked domain registration**. That is konsoleH's phrasing for _the domain
   is registered somewhere else_. This is the €0.76/month line. konsoleH notes that it cannot manage the nameservers. **That is correct and expected**,
   because the zone lives in Hetzner Cloud DNS and is owned by OpenTofu (§5).
4. **Now select `event-junkie.de` in the list — not the hosting name.** Hetzner's own documentation puts it in bold:
   _"Wichtig ist, dass du **nicht** den Hosting-Namen auswählst."_ With the domain selected, `E-Mail` appears in the left menu. With the hosting selected, it does not. This one distinction is the
   whole puzzle.
5. **E-Mail → Mailboxen → Neue Mailbox** for `hello`. Enter only the part before the `@` and set a generated password. Store it in the password manager under
   `Accounts/mail`, per [CREDENTIALS.md](../CREDENTIALS.md).
6. **Create `security@event-junkie.de`** the same way — a **separate mailbox, not an alias**. They have different audiences and different retention
   expectations. An alias cannot be handed to someone else later without handing over `hello@` too.
   **Create `alerts@event-junkie.de` the same way.** OpenObserve sends the alert mail as this mailbox and to it (#877). The password also goes into the
   `openobserve-smtp` Secret on both clusters ([SECRETS.md](SECRETS.md) §openobserve-smtp).
7. **Read `hello@` and `security@` over IMAP, and forward only `alerts@`.** Add the two mailboxes as IMAP accounts in a mail client on your device.
   Do not forward them and do not add them through the Gmail app. Both routes give the mail to Google. Google has no Art. 28 contract with us
   ([LEGAL.md](../LEGAL.md) §7.3a). Give `alerts@` a forward to the address you read every day. It is _Kopie an_, edited on the mailbox after it exists.
   An alert mail names a rule and a cluster, and no person. #274's "done when" is not _the mailbox exists_. It is _a test message arrives somewhere a human
   reads_. A mailbox that only fills up quietly fails that in a way nobody notices for months.
8. **Enable DKIM** for the domain (_E-Mail → Mailsicherheit_), with the domain still selected rather than the hosting. The key pair is generated by konsoleH,
   which **also publishes the public key into the zone itself**. It can, because the hosting and the DNS zone hang off the same Hetzner account. Nothing is left for you to
   copy anywhere. The selector is `default2608`, and it goes live and authoritative within minutes.

## 5. The DNS half — and it is not clicking in a console

**`event-junkie.de` is delegated to Hetzner's nameservers, and the zone is OpenTofu's** — `infra/bootstrap/dns.tf`, `hcloud_zone` and `hcloud_zone_rrset`.
Everything below is a code change.

**With exactly one exception, and it is deliberate: the DKIM record belongs to konsoleH.** `default2608._domainkey` is the only name in the zone OpenTofu does
not manage. The rule is _whoever holds the private key owns the public record_. The key pair is generated and rotated by konsoleH, so a rotation has to reach
DNS without a commit. Importing it would look tidier, and would create a way to break signing silently. The next apply would revert a rotated key to whatever the
repository last saw. That is the same failure shape as `O2_BASIC_AUTH_HEADER` in [OPENOBSERVE.md](OPENOBSERVE.md) — a derived value going stale
with nothing to notice.

An unmanaged rrset is **not** at risk from `tofu apply`. OpenTofu removes what is in its state, and this never enters it. The cost is that `tofu plan` cannot tell you
the record is wrong. That is why §7 tests DKIM against a real message rather than against the zone file.

**The records change together, and the file says so.**

<!-- ste-lint: allow a verbatim quotation from dns.tf, which cannot be split without misquoting it -->

`dns.tf` describes the SPF/DMARC/DKIM triple this way: _"all three are true today and stay true until #274 gives the project real mailboxes — at which point
all three have to change together"_. This is that moment.

| Record        | Today                                  | After                                           | Why                                                                       |
| ------------- | -------------------------------------- | ----------------------------------------------- | ------------------------------------------------------------------------- |
| `@ MX`        | _absent_                               | `10 www750.your-server.de.`                     | Without it nothing is delivered anywhere                                  |
| `@ TXT` (SPF) | `v=spf1 -all`                          | `v=spf1 include:www750.your-server.de -all`     | `-all` means _this domain sends no mail_, and it is about to send replies |
| `default2608` | `*._domainkey` → `v=DKIM1; p=`         | **already done by konsoleH**, not by us         | The wildcard revokes every selector; the specific name overrides it       |
| `_dmarc TXT`  | `p=reject; sp=reject; adkim=s; aspf=s` | unchanged, **`rua=` only if somebody reads it** | Strict alignment still holds — see below                                  |

**The obvious SPF include is the wrong one, and this page said so before it was checked.** `include:_spf.hetzner.com` looks right and is not. `_spf4`/`_spf6` under
it are explicit lists of ~19 IPs in `213.133.*`, `78.46.*`, `85.10.*`, `88.198.*` and `213.95.*`. Those are **Hetzner's own corporate relays**, and
`167.235.121.178` is not among them. Publishing it would authorise machines this account never sends from, and fail to authorise the one it does.

The hosting server publishes its own policy, which is the thing to inherit:

```console
$ dig +short TXT www750.your-server.de
"v=spf1 a:www750.your-server.de a:mail.www750.your-server.de -all"
```

`include:www750.your-server.de` therefore tracks whatever Hetzner puts there. Three lookups, well inside SPF's limit of ten, and self-maintaining in a way a
hard-coded `ip4:` is not. Keep the trailing `-all`.

**That was a hypothesis, and a message settled it on 2026-08-21.** The worry was konsoleH's shared submission path: mail composed in Horde leaves
`webmail.your-server.de` and crosses `sslproxy05.your-server.de` (`78.46.172.2`), neither of which this include covers. The `Received` chain shows both are
**internal hops** — the machine that speaks to the outside world is the account's own:

```
[192.168.0.34] helo=webmail.your-server.de      internal
  -> sslproxy05.your-server.de (78.46.172.2)    internal
    -> www750.your-server.de
      -> the receiver, client-ip=167.235.121.178
```

So the tight include is the correct one and the shared hosts need no authorisation. **Do not widen SPF to cover a submission host you can see in the headers.**
Those hops never touch the recipient's SMTP conversation, and `sslproxy05` is shared with every other Hetzner webhosting customer.

**DMARC's strict alignment survives this** and should not be loosened reflexively. Mail sent through konsoleH carries an envelope sender and a DKIM `d=` of
`event-junkie.de`, so `aspf=s` and `adkim=s` both align. If a test lands in spam, read the `Authentication-Results` header before touching the policy. DMARC is
the last thing to weaken and the first thing people weaken.

**`rua=` is still optional and still a judgement call.** `dns.tf` left it out deliberately: _"a report address that nobody reads is worse than none"_. A mailbox
existing does not make that untrue. Add it when someone will actually open the aggregate reports. Point it at a third address rather than at
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

- **The MX priority lives inside the value string.** `hcloud_zone_rrset` records take a single `value`. There is no separate priority field. The **trailing dot
  is required** — without it the target is treated as relative and becomes `www750.your-server.de.event-junkie.de.`
- **If you ever do publish a TXT value by hand, `provider::hcloud::txt_record()` chunks it for you.** A TXT record is one or more quoted strings of at most 255
  characters, and an RSA-2048 public key is longer than that. The record konsoleH publishes is six strings, for exactly this reason. Hand-quoting is the classic silent
  DKIM failure: the record exists, resolvers return it, and no verifier can parse it.
- **The wildcard revoke stays, and it does not shadow the real key.** `*._domainkey` with `p=` only answers names that have no record of their own, so
  `default2608._domainkey` returns the key while every other selector stays revoked. Verified against the authoritative nameserver on 2026-08-21 — it was the
  one piece of this that was reasoning rather than measurement.

### Applying it, in two sittings rather than one

Receiving needs only the MX. Sending needs an SPF value nobody can know until something was sent. So:

```sh
cd infra/bootstrap
tofu plan -out=mx.tfplan            # 1 to add: the MX rrset. Nothing else.
tofu apply mx.tfplan
```

That alone ends the dead-address problem. Mail arrives, and `v=spf1 -all` stays true because nothing sent yet. **A domain that receives and does not send
is a coherent posture**, not a half-finished one. What `dns.tf` warns against is SPF drifting out of step with reality, and at this point it is exactly in step.

Then create the mailboxes (§4), run §7 step 2, read the sending IP off the headers, and only then:

```sh
tofu plan -out=spf.tfplan           # 1 to change: the SPF rrset. DKIM is konsoleH's, _dmarc unchanged
tofu apply spf.tfplan
```

**Stop if either count differs.** `bootstrap/` holds the zones, `delete_protection` and `prevent_destroy`. A plan that proposes to destroy anything here ends with an
unresolvable domain. A re-created zone gets a new DNSSEC key, and the DS record at INWX stops matching.

## 6. Reading the mail

| Setting  | Value                                              |
| -------- | -------------------------------------------------- |
| IMAP     | `mail.your-server.de`, port **993**, SSL/TLS       |
| SMTP     | `mail.your-server.de`, port **465**, SSL/TLS       |
| Username | the **full address**, e.g. `hello@event-junkie.de` |
| Webmail  | <https://webmail.your-server.de/>                  |

The client hostname is the generic `mail.your-server.de`. The **MX target is the account's own server** from §3 step 3. They are different names for a reason
and swapping them produces an authentication failure that reads like a wrong password.

## 7. Proving it works

```sh
dig +short MX  event-junkie.de @1.1.1.1
dig +short TXT event-junkie.de @1.1.1.1              # -all until the second apply, include: after
dig +short TXT default2608._domainkey.event-junkie.de @1.1.1.1   # konsoleH's, not ours
dig +short TXT _dmarc.event-junkie.de @1.1.1.1
```

Then the part DNS cannot answer:

1. **Send a message to each address from an outside account.** Confirm it arrives in the mailbox. This works with the MX alone.
2. **Send _from_ each address to <https://www.mail-tester.com/>**, then read its report. It gives the external sending IP, the HELO, and SPF, DKIM and DMARC
   separately. Three free checks a day per IP. An empty message scores badly for unrelated reasons, so write two real sentences.
3. **Only then send to a normal mailbox**, and check the _thread_ rather than the inbox. A reply carries `In-Reply-To`, so Gmail folds it into the existing
   conversation instead of surfacing it as new mail. That is a genuinely easy way to conclude a delivered message is missing.
4. **Watch for the first Let's Encrypt notice.** Certificates renew on their own schedule, so this is a slow signal. It is also the one that proves the address
   is reachable by a real sender rather than by you.

**Step 2 is a mail-tester run and not "read the `Received` chain of a reply", and the difference is the whole lesson of this page.** Under `p=reject` a message
that fails authentication is refused during the SMTP conversation. There is no delivered copy, so there are no `Received` or `Authentication-Results` headers
to read. **That method can confirm success and cannot diagnose failure**, which is precisely backwards from what you need while setting this up. The mail-tester
service accepts everything and reports, which is why it works in both states. The only header a rejected attempt leaves behind is the bounce in the sending mailbox.

Measured on 2026-08-21, from both addresses: `spf=pass` (`client-ip=167.235.121.178`, `helo=www750.your-server.de`), `dkim=pass` (2048-bit, `s=default2608`,
`d=event-junkie.de`), `dmarc=pass (p=reject dis=none)`, SpamAssassin `-0.2`. **`dmarc=pass` while `p=reject` is displayed is the outcome to want**: the receiver
read the strict policy, evaluated against it, and delivered anyway.

## 7a. Monitoring — what is watched, and what is not

**A mailbox that stops receiving looks exactly like a quiet week.** That is the blindness
[#618](https://github.com/enorm-labs/event-junkie/issues/618) records for importers, and [OPENOBSERVE.md](OPENOBSERVE.md) for dropped metrics.
[#637](https://github.com/enorm-labs/event-junkie/issues/637) closed it for these two addresses.

`.github/workflows/mail-probe.yml` runs `scripts/mail-probe.py` once a day, once per mailbox. Each run sends a message to the mailbox through Hetzner's
SMTP. It then waits for that message to arrive over IMAP, deletes it, and only then pings healthchecks.io. **The ping is conditional**
([#271](https://github.com/enorm-labs/event-junkie/issues/271)). A bare login passes while the MX points nowhere, which is half of what can break here.

|                       |                                                                                                                                           |
| --------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| **Checks**            | `mail-hello` and `mail-security`, period `24h`, grace `24h` — the same numbers and the same reasoning as the site probe                   |
| **Secrets**           | `MAIL_PROBE_HELLO_PASSWORD` · `MAIL_PROBE_SECURITY_PASSWORD` · `HEALTHCHECKS_MAIL_HELLO_PING_URL` · `HEALTHCHECKS_MAIL_SECURITY_PING_URL` |
| **Passwords**         | The mailbox passwords from §4, which the password manager holds under `Mailboxes/` ([CREDENTIALS.md](../CREDENTIALS.md) row 7a)           |
| **It runs on GitHub** | The mail is on Hetzner Webhosting, not on the node. A check inside the cluster would report cluster death as mail death                   |

**`alerts@` has no probe.** The alert mail itself is its test: a rule tripped on purpose must produce a mail at the forward target
([OPENOBSERVE.md](OPENOBSERVE.md)). A quiet `alerts@` is the normal state, so silence does not show a broken forward.

**It sends through Hetzner's SMTP with the mailbox credential, and that is not a convenience.** SPF authorises the hosting server and nothing else. A
message sent from a CI runner as `hello@` fails under `p=reject` and never arrives. That false alarm has the shape of the outage itself.

### When the alert fires

The check goes red for one of two reasons. A probe pinged `/fail`, which is a delivery failure the job log names hop by hop. Or nothing pinged at all, which
is GitHub itself. Read the run first. The step summary names the mailbox and links this section.

1. **Send a message from an outside account** and see whether it arrives. That separates a broken mailbox from a broken probe.
2. **Check the MX and SPF records** with the four `dig` commands in §7. A stale `var.mail_host` breaks both at once.
   2a. **Do not retry a failed login.** Hetzner blocks the service for that source address after one wrong password.
   Measured on 2026-09-09: one deliberate failure left port 465 refusing this machine. Ports 25, 587 and 993 still
   answered from the same address. A refusal on 465 alone is that block, not an outage. Wait it out, or test the
   credential on 587 with STARTTLS.
3. **Check konsoleH is paid.** The package and the €0.76 external-domain add-on fail as a billing problem, not a technical one. Nothing here can see that.
4. **Check the mailbox is not full.** Mail counts against the 10 GB the whole package shares.

### A probe left in the mailbox is the failure

The probe deletes its own message after it arrives. `hello@` and `security@` have no forward, so nothing keeps a copy. A `[mail-probe]` message in the
**role mailbox itself** is the failure the body describes. The prefix is `SUBJECT_PREFIX` in `scripts/mail-probe.py` and does not change between runs.

### What it does not prove

**That the IMAP client from §4 step 7 puts the mail in front of a person.** The probe stops at the mailbox. To prove the last hop automatically, we
must forward every message to a monitoring service. That mailbox carries Art. 15 requests, Code of Conduct reports and security disclosure. Such content must
not gain a processor for the sake of testing a rule.

So it is a manual step, on the restore-drill cadence. **Send one message to each address from an outside account.
Confirm that it shows in the mail client you read.** Record the date here.

| Date       | Checked by     | Result                                              |
| ---------- | -------------- | --------------------------------------------------- |
| 2026-08-21 | the maintainer | Both addresses delivered to the mailbox and forward |
| 2026-09-24 | the maintainer | Both addresses shown in the IMAP client, no forward |

## 8. What else changes, and what is still open

Ordering the mailbox was the small half. These are the edits that close #274 rather than merely paying for it:

| File                                 | Change                                                                                                   | State                    |
| ------------------------------------ | -------------------------------------------------------------------------------------------------------- | ------------------------ |
| `SECURITY.md`                        | Named registration as the blocker; it never was. `security@` now works and is offered as the alternative | **Done** 2026-08-21      |
| `docs/LINKS.md`                      | The "do not exist yet" paragraph                                                                         | **Done** 2026-08-21      |
| `docs/CREDENTIALS.md` row 7          | Provider chosen, konsoleH login stored — its own credential, not the Cloud one                           | **Done** 2026-08-21      |
| `events-frontend/src/lib/legal.ts`   | Nothing to change: Hetzner adds no processor, so `PROCESSOR_CONTRACTS_PENDING` stays `false`             | **Done** — by not acting |
| `.github/ISSUE_TEMPLATE/config.yml`  | The advisory link is labelled for security but really carries privacy requests. #274 asks for a revisit  | **Open**                 |
| `docs/LEGAL.md` §7.3a and the notice | Email as a processed category, with its retention. `hello@` and `security@` carry no forward             | **Done** 2026-09-24      |
| `docs/ops/COSTS.md`                  | A new line — **after the first invoice**, with a real number rather than the order form's                | **Open**, deliberately   |

## Sources

Prices and product boundaries change. The lookups in §5 and §7 are the ones that verify themselves.

- [Hetzner Webhosting](https://www.hetzner.com/webhosting/) and the [2025 package announcement](https://www.hetzner.com/de/pressroom/new-webhosting-2025/)
- [Hetzner Docs — Webhosting overview](https://docs.hetzner.com/de/konsoleh/general/webhosting/overview/), where the €0.76 external-domain fee is stated
- [Hetzner Docs — Mailsicherheit (SPF, DKIM, DMARC)](https://docs.hetzner.com/de/konsoleh/account-management/email/mailsecurity/)
- [Hetzner Docs — email account configuration](https://docs.hetzner.com/konsoleh/account-management/email/setting-up-an-email-account/)
- [Hetzner Docs — MX records](https://docs.hetzner.com/networking/dns/record-types/mx-record/)
- [`hcloud_zone_rrset`](https://registry.terraform.io/providers/hetznercloud/hcloud/latest/docs/resources/zone_rrset) for the record value format and the
  `txt_record` helper
