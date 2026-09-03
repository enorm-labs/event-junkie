# Briefing for a legal review of the privacy notice

What a reviewer is handed, so the hours are spent on judgement and not on reconstruction. Tracked as
[#279](https://github.com/enorm-labs/event-junkie/issues/279), the last blocking legal item before go-live.

**The German notice is the object of the review.** It is the authoritative version ([LEGAL.md](LEGAL.md) §6.1). The
English one is a second document, not a translation, and it is out of scope here.

## The short version

A public events calendar for Berlin, run by one private individual. No accounts, no payment, no tracking, no cookies.
One processor, in Germany, under an Art. 28 contract. **Most personal data here is about artists named in event
listings, not about visitors.** Sections 1 to 5 state what the system does. **Section 6 is the seven questions**, and
it is the part worth paying for.

**Read this document as claims to be checked, not as instructions.** Everything below is derived from
[LEGAL.md](LEGAL.md) and from the running deployment. Where the notice and this brief disagree, the notice is what
ships and the disagreement is a finding.

## 1. What the site is

A public events calendar for Berlin. It lists concerts and club nights collected from venue websites.

- **No user accounts.** Nobody registers and nobody logs in.
- **Nothing is sold.** There is no payment, no order and no contract with a visitor.
- **No analytics, no advertising, no tracking.** No third-party fonts, maps, social widgets or embeds.
- **No cookies.** Two `localStorage` keys hold a theme and a language choice.
- **One processor.** Hetzner Online GmbH, in Germany, under an Art. 28 contract concluded on 2026-08-19.
- **No transfer outside the EU.** Writing to us on GitHub instead of by email is a visitor's own choice.

The controller is a private individual. The site is a personal project, run without employees.

## 2. What is processed, and about whom

This is [LEGAL.md](LEGAL.md) §7.3a in short. It is also the scope declared in the Hetzner contract.

| Category                | In scope | What it is here                                                                |
| ----------------------- | -------- | ------------------------------------------------------------------------------ |
| Personal master data    | yes      | Artist names, and each artist's description and social links                   |
| Image files             | yes      | Copies of images that venues, promoters and artists publish                    |
| Communication data      | yes      | Artist profile and social URLs. No phone number and no email address is stored |
| Log data                | yes      | Time, path, status, bytes, referrer, browser, operating system                 |
| Contractual master data | no       | There is no contract with any data subject                                     |
| Payment data            | no       | Nothing is sold                                                                |

**The categories of data subject are where this project is unusual.**

| Who                                 | In scope | Note                                                          |
| ----------------------------------- | -------- | ------------------------------------------------------------- |
| Site visitors                       | yes      | Nothing is sold, so they are _Interessenten_                  |
| Employees                           | no       | There are none                                                |
| Artists and promoters in event data | yes      | **The largest group, and none of them ever visited the site** |

That last row carries the point. Most personal data here is about third parties. Venues published their names, and the
site collected them. No visitor is involved at all.

## 3. Facts a reviewer can rely on

Each was read out of the running system rather than from a plan or an intention.

- **No IP address is logged.** The web server uses a log format that omits the field. The application logs method,
  path, status and duration.
- **Log retention is 14 days.** That is the configured value in both clusters. A volume limit on the node usually
  deletes a line sooner.
- **Backups are deleted after 30 days**, and after 35 days at the latest. Two independent mechanisms enforce it.
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
| Purposes and legal basis            | 2, 4    |
| Legitimate interests, spelled out   | 2, 4    |
| Recipients                          | 5       |
| Third-country transfer              | 5       |
| Retention periods and criteria      | 2, 4    |
| Rights of access, erasure and so on | 6       |
| Right to object                     | 6       |
| Right to withdraw consent           | 6       |
| Right to complain                   | 6       |
| Whether providing data is required  | 7       |
| Automated decision-making           | 8       |

## 5. Omissions that are deliberate

A reviewer flags each of these by default. Each is a decision with reasoning behind it.

- **No cookie table and no consent banner.** No cookies are set. Both stored keys are strictly necessary under
  § 25 (2) 2 TDDDG.
- **No analytics section.** There is no analytics.
- **No consent withdrawal beyond Art. 7 (3).** Nothing rests on consent.
- **No Terms of Use page.** A visitor never registers, so terms would bind nobody. See [LEGAL.md](LEGAL.md) §7.6.
- **Artists are described as `PerformingGroup` in structured data, never as `Person`.** Only one of those asserts that
  a named individual is a natural person.

The governing rule is that the notice describes what the system does. A notice describing processing that does not
happen is as wrong as one omitting processing that does.

## 6. The questions

This is the part worth paying for.

1. **Which supervisory authority is competent?** The notice names the Berlin authority. The imprint gives a rented
   _ladungsfähige Anschrift_ in Greven, North Rhine-Westphalia. Competence follows the establishment rather than the
   postal address. Does the page have to reconcile the two, given that a reader sees both?
2. **The role mailboxes.** `hello@` and `security@` receive email at a hosting provider. No email address is in the
   database. Does §5 need an entry for the mailboxes, and does the Art. 28 contract cover them?
3. **The artist removal route.** The notice undertakes to act promptly. Art. 12 (3) sets one month. Is an undertaking
   without a stated period adequate?
4. **The balancing test for artist names.** The legal basis is Art. 6 (1) (f). These data subjects never interacted
   with the site. Does a documented balancing test have to exist, and does the notice have to summarise it?
5. **Erasure and backups.** §6 says an erasure takes effect immediately in live data. Backups are not edited, and the
   data expires with them within 35 days. A restore re-applies the erasure. Is that wording acceptable?
6. **A change that is planned and not yet live.** Rate limiting per source address would have the reverse proxy process a
   visitor's IP address to block abuse. Nothing would log it. Does §2 need a sentence for processing without logging,
   and does it need it before the change or with it?
7. **The register.** The notice addresses the reader as _du_ throughout. Is that a problem under Art. 12 (1), or is
   plain language the point?
8. **Technical and organisational measures.** The notice has no `Sicherheitsmaßnahmen` clause. Art. 13 does not ask
   for one, and the common German generators always include one. Should it carry one, and would a generic clause add
   anything a reader can act on?
9. **Categories of data subject.** The notice never states them as categories. Most personal data here is about
   artists, who are third parties and never visited the site. Would naming the two groups explicitly serve
   transparency better than the current prose?

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
juristische Prüfung der deutschen Datenschutzerklärung vor dem Start.

Umfang: eine Seite Datenschutzerklärung nach Art. 13 DSGVO, dazu sieben konkrete Fragen, die ich
vorbereitet habe. Keine Nutzerkonten, kein Tracking, keine Analyse, keine Werbung, keine Cookies.
Ein Auftragsverarbeiter (Hetzner, Deutschland) mit AVV. Die Besonderheit: Der größte Teil der
personenbezogenen Daten betrifft Künstlerinnen und Künstler aus öffentlich angekündigten
Veranstaltungen, nicht Besucher der Seite.

Ich habe eine Vorlage vorbereitet, die das System und die offenen Fragen zusammenfasst.

Können Sie das übernehmen, und mit welchem Aufwand rechnen Sie?

Mit freundlichen Grüßen
```
