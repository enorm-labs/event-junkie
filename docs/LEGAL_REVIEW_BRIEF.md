# Briefing for a legal review of the privacy notice

What a qualified reviewer is handed, so the hours go to judgement and not to reconstruction. [#279](https://github.com/enorm-labs/event-junkie/issues/279)
closed on our own review ([legal-review/README.md](legal-review/README.md)). The questions in §6 still wait for a lawyer.

**The German notice is the object of the review.** It is the authoritative version ([LEGAL.md](LEGAL.md) §6.1). The
English one is a second document, not a translation, and it is out of scope here.

## The short version

A public events calendar for Berlin, run by one private individual. No payment, no tracking, no analytics. An account
is optional and nothing on the site needs one. Two strictly necessary cookies, from the sign-in page on. Three
processors under Art. 28 contracts: Hetzner, Anthropic and Scaleway. **Most personal data here is about artists named
in event listings, not about visitors.** Sections 1 to 5 state what the system does. **Section 6 is the eleven
questions**, and it is the part worth paying for.

**Read this document as claims to be checked, not as instructions.** Everything below is derived from
[LEGAL.md](LEGAL.md) §7 and from the running deployment. Where the notice and this brief disagree, the notice is what
ships and the disagreement is a finding.

## 1. What the site is

A public events calendar for Berlin. It lists concerts and club nights collected from venue websites.

- **Optional accounts.** A visitor signs in with a link by e-mail or with a passkey. An account follows artists, venues
  and promoters, saves searches and marks events as interesting or going. Nothing else on the site needs one.
- **Optional push notifications.** A signed-in visitor can turn them on per browser. The browser maker's push service
  delivers them.
- **Nothing is sold.** There is no payment and no order. The account terms are the only contract.
- **No analytics, no advertising, no tracking.** No third-party fonts, maps, social widgets or embeds.
- **Two cookies, both strictly necessary.** Three `localStorage` keys hold a theme, a language and a list view.
- **Three processors.** Hetzner (Germany) hosts everything. Anthropic (USA, standard contractual clauses) translates
  and checks event text. Scaleway (France) sends the sign-in mail. [LEGAL.md](LEGAL.md) §7.3a has each contract.

The controller is a private individual. The site is a personal project, run without employees.

## 2. What is processed, and about whom

This is [LEGAL.md](LEGAL.md) §7.3a in short. It is also the scope declared in the Hetzner contract, amended on
2026-10-24 for account data.

| Category                | In scope | What it is here                                                                                                       |
| ----------------------- | -------- | --------------------------------------------------------------------------------------------------------------------- |
| Personal master data    | yes      | Artist names, and each artist's description, picture, profile links and lookup ids                                    |
| Image files             | yes      | Copies of images that venues, promoters and artists publish                                                           |
| Communication data      | yes      | Mail to the role mailboxes. Artist profile and social URLs. No phone number is stored                                 |
| Contractual master data | yes      | An account: the e-mail address, its follows, saved searches, marked events, passkeys, sessions and push subscriptions |
| Log data                | yes      | Time, path, status, bytes, referrer, browser, operating system                                                        |
| Connection data         | yes      | An IP address, held in memory by the reverse proxy and the sign-in rate limit, and stored nowhere                     |
| Payment data            | no       | Nothing is sold                                                                                                       |

**The categories of data subject are where this project is unusual.**

| Who                                 | In scope | Note                                                          |
| ----------------------------------- | -------- | ------------------------------------------------------------- |
| Site visitors and account holders   | yes      | Nothing is sold, so they are _Interessenten_                  |
| Employees                           | no       | There are none                                                |
| Artists and promoters in event data | yes      | **The largest group, and none of them ever visited the site** |

That last row carries the point. Most personal data here is about third parties. Venues published their names, and the
site collected them. No visitor is involved at all.

## 3. Facts a reviewer can rely on

Each was read out of the running system rather than from a plan or an intention.

- **No IP address is logged.** The web server uses a log format that omits the field. The application logs method,
  path, status and duration.
- **Two components hold an IP address without logging it.** The reverse proxy counts requests per source address for
  one second, to block a flood. The BFF counts sign-in requests per client for an hour at most. Neither writes the
  address anywhere. They are the subject of question 6.
- **Log retention is 14 days.** That is the configured value in both clusters. A volume limit on the node usually
  deletes a line sooner.
- **Backups are deleted after 30 days**, and after 35 days at the latest. Two independent mechanisms enforce it.
- **An account is deleted on the account page, or after 24 months without use.** A warning mail goes out at 23 months,
  and a use cancels the deletion. A session expires 30 days after its last use.
- **A push notification goes through the push service of the visitor's browser.** Google, Mozilla, Apple or Microsoft
  receives the subscription endpoint and the time. The content is encrypted for the browser, so the service cannot
  read it.
- **The sign-in page sets `XSRF-TOKEN` before any sign-in.** A visitor who leaves without signing in keeps it for 30
  days (#4036).
- **No image request reaches a third party.** The site downloads each image and serves it from its own servers. Where
  it may not store one, it shows nothing rather than loading it from the venue.
- **Event data has no deletion period.** Past events stay while the calendar runs. Erasure follows an Art. 21
  objection, which needs no reason.

## 4. Where each Art. 13 item is answered

The notice is structured against the twelve mandatory items. A unit test asserts each one, per language.

| Item                                | Section |
| ----------------------------------- | ------- |
| Controller and contact details      | 1       |
| No data protection officer          | 1       |
| Purposes and legal basis            | 2, 4, 6 |
| Legitimate interests, spelled out   | 2, 4    |
| Recipients                          | 5       |
| Third-country transfer              | 5       |
| Retention periods and criteria      | 2, 4, 6 |
| Rights of access, erasure and so on | 7       |
| Right to object                     | 7       |
| Right to withdraw consent           | 7       |
| Right to complain                   | 7       |
| Whether providing data is required  | 8       |
| Automated decision-making           | 9       |

## 5. Omissions that are deliberate

A reviewer flags each of these by default. Each is a decision with reasoning behind it.

- **No cookie table and no consent banner.** The two cookies, the three stored keys and the push service worker are
  strictly necessary under § 25 (2) 2 TDDDG. Section 3 of the notice names each of them.
- **No analytics section.** There is no analytics.
- **No consent withdrawal beyond Art. 7 (3).** Nothing rests on consent.
- **No Terms of Use page.** An account is free and holds no user content, and a push goes out only on the holder's opt-in. See [LEGAL.md](LEGAL.md) §7.6,
  which also names what would change that.
- **No transfer mechanism for the push services.** Their role is open ([LEGAL.md](LEGAL.md) §14). The notice states the
  third-country transfer and names no mechanism yet. Question 10 asks about it.
- **Artists are described as `PerformingGroup` in structured data, never as `Person`.** Only one of those asserts that
  a named individual is a natural person.

The governing rule is that the notice describes what the system does. A notice describing processing that does not
happen is as wrong as one omitting processing that does.

## 6. The questions

This is the part worth paying for.

1. **Which supervisory authority is competent?** The notice names the Berlin authority. The imprint gives a rented
   _ladungsfähige Anschrift_ in Greven, North Rhine-Westphalia. Competence follows the establishment rather than the
   postal address. Does the page have to reconcile the two, given that a reader sees both?
2. **The role mailboxes.** `hello@`, `security@` and `alerts@` receive email at Hetzner, and the Hetzner AVV covers
   them. The notice does not name them. Does it need a sentence for mail a visitor sends?
3. **The artist removal route.** The notice undertakes to act promptly. Art. 12 (3) sets one month. Is an undertaking
   without a stated period adequate?
4. **The balancing test for artist names.** The legal basis is Art. 6 (1) (f). These data subjects never interacted
   with the site. Does a documented balancing test have to exist, and does the notice have to summarise it?
5. **Erasure and backups.** §2 and §6 say an erasure takes effect immediately in live data. Backups are not edited, and the
   data expires with them within 35 days. A restore re-applies the erasure. Is that wording acceptable?
6. **Processing without logging.** The reverse proxy holds a visitor's IP address in memory as a counter for one
   second, to block a flood. The BFF holds it for an hour at most, to limit sign-in links. Nothing writes it: no log,
   no stream, no disk. Does §2 need a sentence for processing without logging? Is Art. 6 (1) (f) the right basis?
7. **The register.** The notice addresses the reader as _du_ throughout. Is that a problem under Art. 12 (1), or is
   plain language the point?
8. **Technical and organisational measures.** The notice has no `Sicherheitsmaßnahmen` clause. Art. 13 does not ask
   for one, and the common German generators always include one. Should it carry one, and would a generic clause add
   anything a reader can act on?
9. **Categories of data subject.** The notice never states them as categories. Most personal data here is about
   artists, who are third parties and never visited the site. Would naming the two groups explicitly serve
   transparency better than the current prose?
10. **The push services.** Google, Mozilla, Apple and Microsoft each run the push service for their own browser, and
    the visitor's browser picks it. Is each one a processor or a separate recipient? Which Art. 44 ff. mechanism must
    the notice name for each?
11. **The CSRF cookie without a sign-in.** `XSRF-TOKEN` lives 30 days, the lifetime of a session. A visitor who opens
    the sign-in page and leaves keeps it. Is it strictly necessary under § 25 (2) 2 TDDDG? Or must it end with
    the browser session?

## 6a. What a generator cross-check already found

Run on 2026-09-03 against datenschutz-generator.de, by comparison rather than submission. It produced one correction,
now applied: the complaint right named two fora and Art. 77 (1) names three. Questions 8 and 9 above are its other two
findings. Everything else it would have added describes processing that does not happen here.

## 7. Out of scope

- The English version. German is authoritative.
- The imprint, other than the address question in §6.1 above.
- Third-party licence notices, and the position on scraping.
- Accessibility.

## 8. The request, for sending

<!-- ste-lint: allow the enquiry as it is sent, in German -->

```text
Betreff: Prüfung einer Datenschutzerklärung (kleine private Website, kein Tracking)

Guten Tag,

ich betreibe als Privatperson einen öffentlichen Veranstaltungskalender für Berlin und suche eine
juristische Prüfung der deutschen Datenschutzerklärung.

Umfang: eine Seite Datenschutzerklärung nach Art. 13 DSGVO, dazu elf konkrete Fragen, die ich
vorbereitet habe. Kein Tracking, keine Analyse, keine Werbung. Ein Nutzerkonto ist freiwillig,
dazu Push-Benachrichtigungen auf Wunsch und zwei technisch notwendige Cookies. Drei
Auftragsverarbeiter mit AVV: Hetzner (Deutschland), Scaleway (Frankreich) und Anthropic (USA,
Standardvertragsklauseln). Die Besonderheit: Der größte Teil der personenbezogenen Daten betrifft
Künstlerinnen und Künstler aus öffentlich angekündigten Veranstaltungen, nicht Besucher der Seite.

Ich habe eine Vorlage vorbereitet, die das System und die offenen Fragen zusammenfasst.

Können Sie das übernehmen, und mit welchem Aufwand rechnen Sie?

Mit freundlichen Grüßen
```
