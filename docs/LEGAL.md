# Legal, compliance and site-chrome policy

> **Not signed off.** This is what the site _does_, and what future changes must keep true. Several decisions here are
> provisional, and the site cannot go live until §14 is closed. Read that section before treating anything here as
> final.
>
> Related: [ADR-012 (cloud platform)](adr/ADR-012_CLOUD_PLATFORM.md) · [ADR-013 (localisation)](adr/ADR-013_LOCALISATION.md) ·
> [ADR-014 (rendering)](adr/ADR-014_RENDERING_STRATEGY.md) · [the `v1.0 — Go-live` milestone](https://github.com/enorm-labs/event-junkie/milestones) · [BRANDING.md](BRANDING.md)

## The short version

**Four rules that catch almost every change:**

1. **Update the privacy notice in the same PR, in both languages**, for any change of three kinds. They are: adding a
   third-party request, storing anything on the visitor's device, or altering what is logged.
   [AGENTS.md §Privacy & GDPR](../AGENTS.md) is the trigger list.
2. **Legal pages are documents, not translated strings.** One component per language under `/{locale}/legal/*`. Edit both or neither.
3. **German is authoritative** where the two versions could be read differently (§6.1).
4. **The three flags in `events-frontend/src/lib/legal.ts` are the machine-readable record** of what is still provisional. Never clear one ahead of the thing it
   describes — that is what §14 exists to prevent.

**The site is not signed off and cannot go live until §14 is closed.**

## 1. Scope and how to use this

This covers the site's legal surface and the chrome around it: the footer, the imprint and privacy notice, and
third-party licence attribution. It also covers how the running version is exposed, the beta marker, and the
accessibility target.

**Section numbers are inherited deliberately.** Around fifty references across the codebase cite sections of the
document this replaces — `§9.2`, `§6.1`, `§4.4` and the rest. Keeping them valid was worth more than a tidy sequence.
§10 and §11 are absent because they described _work to do_ rather than policy. §2 is a description rather than the
proposal it was.

**Operational rules are not repeated here.** They live where the person changing the code will actually see them:

| Rule                                                  | Where it lives                                                                                        |
| ----------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| Privacy/GDPR re-check triggers                        | [`AGENTS.md` §Privacy & GDPR](../AGENTS.md)                                                           |
| Version in `gradle.properties`, `package.json` mirror | [`AGENTS.md` §Versioning](../AGENTS.md) · [`events-frontend/AGENTS.md`](../events-frontend/AGENTS.md) |
| Accessibility rules                                   | [`events-frontend/AGENTS.md` §Accessibility](../events-frontend/AGENTS.md)                            |
| Localisation and SEO rules                            | [`events-frontend/AGENTS.md`](../events-frontend/AGENTS.md)                                           |
| Open-source notice generation                         | [`events-frontend/AGENTS.md` §Open-source notices](../events-frontend/AGENTS.md)                      |

## 2. The footer and the legal pages, as built

The footer appears on every route (`AppFooter.vue`). It carries:

- the brand line and tagline
- the data disclaimer (§7.6)
- a **Project** column — source, issues, contributing, changelog
- a **Legal** column — imprint, privacy, open-source notices, and the venue opt-out page
- the copyright and licence line (§3)
- the version (§4)

Legal content lives under `/{locale}/legal/*`: `imprint`, `privacy`, `notices` and `for-venues`. The nesting means
later additions, such as an accessibility statement, have an obvious home. `for-venues` is the first of those, and it
publishes the opt-out route that [SCRAPING_POSITION.md](SCRAPING_POSITION.md) §5 defines. Each of the five long-form
pages, including About, is a **separate component per language** rather than translated strings. A legal page is a
document, reviewed as a document, possibly by someone who does not read Vue. The reasoning is recorded in
`views/localisedView.ts`, and the rule that follows from it — _edit both language versions or neither_ — in
`events-frontend/AGENTS.md`.

German practice expects the imprint within a couple of clicks from any page, which the footer satisfies. An e2e test
holds that property.

**Deliberately not in the footer:**

- a language switcher duplicate — the header and footer each carry one, with distinct landmark names
- social icons for accounts that do not exist
- a newsletter signup, which would introduce user data the site does not otherwise process

## 3. Copyright and licence line

The footer states `© <year> Event Junkie` and that the code is under **Apache-2.0**, linking the licence text in the repository.

The distinction that matters, and which the imprint repeats: **our code is Apache-2.0, and the event data is not ours
to license.** Event descriptions, images and
other material originating from venues, promoters and artists remain their rights holders' property. Conflating the two would be a licensing claim over other
people's material.

## 4. Version and commit exposure

**`version` in the root `gradle.properties` is authoritative.** Nothing else may declare it independently. `springBoot { buildInfo }` writes it plus the commit
into `META-INF/build-info.properties` at build time, which Spring exposes as a `BuildProperties` bean.

### 4.4 Two consumers, one bean

`/actuator/info` is for operators and is **not** publicly routed. `GET /meta` is the public endpoint the frontend
calls. An actuator endpoint on the public ingress is a larger surface than one JSON route.

**Never fetch the version from the GitHub API.** It sends every visitor's IP to GitHub. That makes GitHub a recipient
in the privacy notice, and adds a third-party request to a page that otherwise makes none. It also reports what was
_released_, not what is _running_. That is the question a version in the footer exists to answer.

**`package.json`'s `version` mirrors the Gradle version by hand**, without the `-SNAPSHOT` suffix. Automating it was
considered and rejected as more machinery than the problem deserves. The convention is recorded in both `AGENTS.md`
files.

### 4.7 Versioning scheme

First public version **`0.1.1`**, and `main` carries `0.1.1-SNAPSHOT`. Reaching `1.0.0` and dropping the beta badge
(§5) are **one decision, not two**.

**The footer links a version to its release page only when it is a released `X.Y.Z` triple.** It shows every other
version as plain text: the number is always displayed, and only the link is withheld. That is stated positively on
purpose, matching the `semverFilter` production's `OCIRepository` uses to answer the same question. The negative form
(`-SNAPSHOT`) is a spelling that exists only in `gradle.properties`, while a deployed build reports a computed version
(`0.1.1-snapshot.20260817180146.g787d7d0`). Snapshots are published to GHCR and never tagged in git, so there is
nothing for those links to point at.

`0.1.0` was skipped and never published. It was spent on snapshots, and the base version had to move past them for
ordering reasons. docs/DEVELOPMENT.md §Versions has the SemVer rule. Nothing depends on the first public number being
`.0`.

## 5. The beta marker

The header carries a `beta` badge linking to `/{locale}/about#beta`. That page explains what beta does and does not
mean. Incomplete coverage, possibly stale details, and explicitly _not_ "a trial you get charged for" or "we sell your
data". The anchor id stays `beta` in both languages.

It comes off with `1.0.0` (§4.7).

## 6. Language

### 6.1 Both languages, German authoritative

The site publishes English and German. **The German versions of the imprint, the privacy notice and the venue page are
authoritative.** That is stated on each page in both languages, through a `LegalPage` prop, so it cannot be forgotten
on one of the six.

The history is worth keeping, because it constrains future changes. The legal pages shipped English-only first, on the
explicit condition that German ship _in the same release as the German UI_. An English-only imprint on a site
presenting itself in German is the configuration where the Art. 12 GDPR "clear and plain language" argument turns
against us. That condition was met on 2026-08-08. It applies again to **any third locale**: publishing a locale means
publishing its legal pages in the same release.

### 6.2 Localisation

Delivered — see [ADR-013](adr/ADR-013_LOCALISATION.md). Locale-prefixed routes, per-locale legal pages, a switcher in
both header and footer, `hreflang`, `og:locale` and structured data.

## 7. GDPR / DSGVO

### 7.1 What actually applies

A German controller running a public site with no accounts, no analytics, no advertising and no third-party embeds.
The processing that exists is server logs (Art. 6 (1) (f)), one `localStorage` key for the theme preference and one
for the locale hint, and publicly announced event data (§7.3).

### 7.2 The Art. 13 checklist

The notice is structured against the twelve mandatory items. Omitting one is the usual defect, and it is invisible
without a list, so a test enforces it. `views/legal/__tests__/legalViews.spec.ts` runs the checklist **against each
language separately**, because a section missing from one version is invisible from the other.

The notice deliberately does **not** describe processing that does not happen: no cookie table, no consent withdrawal,
no analytics section. A notice describing imaginary processing is as inaccurate as one omitting real processing, and
generators produce exactly that (§7.8).

### 7.3 Artists are people

Event data is mostly not personal data. But **where a performing artist is a natural person, their name and their
billing in a line-up are**. The notice says so, and offers a removal route requiring no reason and no public
discussion. This is the most likely real complaint the site will ever receive.

It also governs machine-readable output. Structured data describes performers as `PerformingGroup`, never `Person`. Of
the two types Google accepts, only one asserts that a named individual is a natural person.

### 7.3a What is processed, and about whom — the answer every processor form asks for

Written down here because it is asked repeatedly, and answered from memory otherwise. Hetzner's AVV asks for it as
tick-boxes, an Art. 30 record needs it as prose, and §5 of the notice is the same facts written for a visitor. **Three
documents, one set of facts.** The only way to notice when they stop agreeing is to keep the facts in one place and
derive the rest.

**This is also the scope actually declared in the Hetzner AVV concluded on 2026-08-19**, not merely an analysis of
what could have been declared. That makes it the record of what the contract covers, which has a consequence worth
stating in advance. **Processing a category not listed below means the AVV needs revisiting, not just the notice.** A
contact form, a newsletter, or any stored email address or phone number would each do it. Updating the notice is the
change everyone remembers, and the contract behind it is the one nobody does.

**Categories of personal data**, mapped to the vocabulary these forms use:

| Category                        | Applies                      | What it actually is here                                                                                                                                                                                               |
| ------------------------------- | ---------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Personal master data**        | **yes**                      | Artist names, and each artist's `description`, `imageUrl`, `websiteUrl`, `facebookUrl`, `instagramUrl`, `youtubeUrl`. The largest category by far, and see §7.3 for why it counts                                      |
| **Image files**                 | **yes**                      | Copies of the images venues, promoters and artists publish, stored in `event-junkie-images` at Hetzner (ADR-019, #833). An artist photograph shows an identifiable person, so this is personal data in its own right   |
| **Communication data**          | **yes, on a strict reading** | No phone numbers and no email addresses are stored anywhere. The artist profile and social URLs are what a strict reading catches. Declared deliberately: the cost was nil and omitting it would have left a scope gap |
| Contractual master data         | no                           | There is no contract with any data subject                                                                                                                                                                             |
| **Log data**                    | **yes**                      | Timestamp, requested path, HTTP status, bytes transferred, referrer, browser and OS. **No IP address** since §7.5 was settled on 2026-08-19. Retention is a size bound, not a period — see §7.5.1                      |
| Contract, invoicing and payment | no                           | Nothing is sold and no payment is processed                                                                                                                                                                            |

**Log data was declared even though §7.5 is open**, and the reasoning generalises. A processor agreement should cover
the maximum that might be processed. Narrowing it later is trivial, and discovering that something was processed
outside its scope is not.

**Categories of data subject**, which is where this project is unusual:

| Who                                                        | Applies                                |                                                                                                                                                                         |
| ---------------------------------------------------------- | -------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Customers and interested parties                           | **yes**                                | Site visitors. Nothing is sold, so they are _Interessenten_ rather than customers, but that is the box these forms offer                                                |
| Employees                                                  | no                                     | There are none                                                                                                                                                          |
| **Artists and promoters named in the imported event data** | **yes — and it must be added by hand** | Neither customers nor employees, and **the largest group of data subjects in the system**. A form filled in with only the two standard boxes would omit almost everyone |

That last row is the one to get right. The personal data here is overwhelmingly about **third parties whose names
were published by venues**, not about anyone who ever visited the site. That is also why §7.3's removal route matters
more than a contact form would.

**Event-data retention is a criterion, not a period, and §4 of the notice states it (#769).** Past events are kept for
as long as the calendar operates. Nothing deletes them by age, and #350's housekeeping policy does not change that.
Art. 13 (2) (a) accepts the criteria used to determine the period in place of a duration, which is what this is.
Erasure is on objection under Art. 21, with no reason required — the same route §7.3 already offers. Decided in #362,
jointly with the archive: keeping past events reachable is deciding not to delete them.

**A stored image is a different question from a stored URL, and the row above is new because of it.** Until ADR-019
the site embedded the venue's URL and held no file. It now downloads the file and keeps it, which is a reproduction
under § 16 UrhG and a processing operation under the DSGVO. Two consequences follow.

- **The bucket is inside the AVV scope, and it is a third one.** `event-junkie-images` sits beside `event-junkie-o2`
  and `event-junkie-backups` under the Hetzner contract of 2026-08-19. It adds no processor, which is what
  [ADR-019](adr/ADR-019_VENUE_IMAGE_DELIVERY.md) weighed. It is a new bucket all the same.
- **Erasure has to reach the file, not only the row.** A database row deletes its own image and an object does not.
  The takedown route in `SCRAPING_POSITION.md` §5 deletes the objects, and an orphan sweep finds what nothing
  references. Art. 17 is answerable here only because that endpoint exists.
- **An artist photograph is erased through §7.3, not through the venue opt-out.** An artist plays many venues. The
  takedown deliberately covers a venue's own image and its events' images, and stops there. Deleting a performer's
  photograph on one venue's request would remove it from every other listing.

**`images.serving.enabled` is a published claim, the way `ZO_COMPACT_DATA_RETENTION_DAYS` is.** With it on, the API
hands out our own URL and the visitor's browser contacts no venue. §5 of the notice says so, and `legalViews.spec.ts`
pins the sentence. With it off the API hands out the venue's own URL, and every card is a third-party request. That
sentence is then false, so **turning it off is a notice change rather than a rollback.** Production served its own
images from 2026-08-30. The notice went on warning about venue requests until #279 — a warning about a request the
browser could not make.

**The images carry no retention period either**, for the same reason event data carries none. They live as long as the
event they belong to. What removes one is an objection, a venue opting out, or the sweep finding that nothing
references it.

**Two places this has to stay in step.** The backups in Hetzner Object Storage hold the same database, so they carry
the same categories rather than being a separate question. And a processor's sub-processor annex is worth reading
against §5. If any of them sits outside the EU for a service in use, the notice's "no third-country transfer" sentence
stops being true.

**Backup retention is two numbers, and the notice states both (#586).** A nightly `wal-g` sweep on the node deletes at
**30 days**. A lifecycle rule on the bucket deletes at **35**, regardless of whether anything of ours is running. The
gap is not slack. The sweep uses `delete before FIND_FULL`, which deliberately keeps a backup older than the window
whenever the rest of the window depends on it. A bucket rule set at exactly 30 would therefore break the restore chain
rather than enforce the promise. Before #586 only the sweep existed, which meant the stated period held only while the
node did. That is the defect §7.5 names for log retention, one system over. **If either number moves, both notices
state them and both must move with it.**

### 7.4 Device storage — `localStorage`, not just cookies

The site sets **no cookies**. It stores exactly two keys: **`theme`** (`App.vue`) and **`locale`**
(`i18n/locales.ts`). § 25 TDDDG covers _storage on terminal equipment_, not cookies specifically. Both items are
strictly necessary for a setting the visitor chose, so § 25 (2) 2 applies and **no consent banner is required**.

**§3 of the notice names both keys, and it said "exactly one" until 2026-09-03.** The site wrote `locale` from the day
the second language shipped. Nothing caught it. `legalViews.spec.ts` asserts that a required element is _present_, and
never that a sentence is _true_ — the defect class §7.2 is about. The assertion added with #279 pins both names, so a
third stored key fails the build rather than a complaint.

That is a property worth defending deliberately. The first non-essential stored item makes a banner mandatory. It is a
product decision, not an implementation detail, so escalate rather than implement.

### 7.5 Logging

> Log data is declared to processors as in scope regardless of how this lands — see §7.3a. A processor agreement
> should cover the maximum that might be processed. Narrowing it later is trivial, and discovering something was
> processed outside its scope is not.

#### 7.5.1 The four decisions, and their answers

The notice must state truthfully which logs hold personal data, what is in them, and for how long. All four were open
until 2026-08-19. They were closed by **reading the running system rather than the configuration**. A k3d rehearsal,
requests driven through the real ingress, and the resulting log lines read out of the pods.

| #   | Decision                                                | Answer                                                                                             |
| --- | ------------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| 1   | Do Traefik and the nginx container log real client IPs? | **Traefik: no, it logs nothing at all.** **nginx: it logged an address field, and no longer does** |
| 2   | Is any logged IP truncated?                             | **Moot — none is logged.** Truncation was rejected as the weaker lever                             |
| 3   | What is the retention period per stream?                | **14 days** in the log store; a size bound on the node, which usually bites first                  |
| 4   | Where is retention actually enforced?                   | The kubelet on the node, and OpenObserve's compactor in **both** clusters (#271, #880)             |

**On 1 — the mechanism was not the one this section predicted, and the difference matters.** The earlier text reasoned
that removing Cloudflare left no proxy between visitor and origin, so nginx would write real addresses from the first
request. Traefik is still a proxy, so `$remote_addr` was never the visitor. It was Traefik's own pod address. The leak
was one field further along. **The base image's default `main` log format ends with `"$http_x_forwarded_for"`.**
Traefik populates X-Forwarded-For with the immediate client, which on the public internet is the visitor. Observed
directly:

```
10.42.1.5 - - [19/Aug/2026:19:54:32 +0000] "GET / HTTP/1.1" 200 1212 "-" "curl/8.7.1" "10.42.1.1"
└─ Traefik, not the visitor                                                           └─ the visitor
```

That distinction is worth keeping because it changes the fix. A proxy-shaped problem would be solved by trusting or
not trusting a header. This one is solved by choosing a log format. `events-frontend/docker/nginx.conf` now defines
`ej_no_ip` — time, request, status, bytes, referrer, user agent — and drops `$remote_addr` as well as X-Forwarded-For.
`$remote_addr` is harmless only while a proxy sits in front, which is a property of the topology rather than of the
file. Logging no address is the version that stays true if that changes. **Verified after the change: zero IP
addresses in the pod's entire log stream.**

**One sentence above is wrong, and #268 measured it.** It claims X-Forwarded-For holds the visitor on the public
internet. That was reasoning, not an observation. k3s exposes Traefik through ServiceLB. Its `klipper-lb` container
installs `iptables -t nat -I POSTROUTING -d <clusterIP> -j MASQUERADE`, read out of the `svclb-traefik` pod's own
startup log on both clusters. Every packet is rewritten before Traefik reads it. Traefik's peer is therefore the
`svclb` pod for every visitor, and X-Forwarded-For inherits that address. The `10.42.1.1` annotated above as "the
visitor" was the load balancer.

**Staging now carries a real visitor address, and it took two attempts to get there (#1013).**
`externalTrafficPolicy: Local` was the documented fix and was not enough. The controller did aim klipper-lb at the
node and its NodePort. Its container still installed the MASQUERADE, so the source was rewritten before the packet
arrived. What worked
is a **hostPort** on Traefik, with the Service dropped to `ClusterIP`. That removes ServiceLB from the path.

**Measured rather than inferred.** Traefik's access log was turned on for one request and removed the same day. It
recorded `"ClientHost":"10.10.1.2"`, the operator's own tunnel address. **Production does not have this yet.**

**That makes this section's conclusion stronger, and changes nothing in the notice.** The address field nginx used to
write held a cluster address rather than a visitor's. Less was logged than this section feared. The decision stands for
the reason it already gave: a log format that writes no address survives the topology changing. **Do not read this as
permission to write the field again.** On staging that protection is now the only one left. On production the change
that would remove the second one is the same hostPort, not the traffic policy.

**Rows 3 and 4 changed on 2026-09-02: the duration is now real, and the notice states it (#278).** This section used
to end by forbidding a number of days until OpenObserve's retention policy existed. It exists. #271 shipped it and
**#880 gave production the same stack on 2026-08-31**, so both clusters run `ZO_COMPACT_DATA_RETENTION_DAYS: "14"`.

**Both bounds are real, and the notice states both.** The collector's `filelog` agent reads every container's stdout
and stderr from `/var/log`, so nginx's access log reaches OpenObserve like everything else. On the node the kubelet
rotates by volume, and a line often disappears sooner. In the log store nothing survives 14 days. Art. 13 (2) (a)
needs the maximum, so the notice leads with 14 days. The rotation comes second.

**`ZO_COMPACT_DATA_RETENTION_DAYS` is a published claim.** Its own comment in `openobserve.yaml` says so. Change it
without changing the privacy notice and the notice becomes false. `legalViews.spec.ts` asserts the number in both
languages, so the two cannot drift apart quietly.

**The trap this replaces, recorded because it nearly shipped.** #877's body says production does not run OpenObserve.
That was true when written, and #880 closed it a day later. Reading the issue instead of the tree gave a notice that
claimed no fixed period. That is a second false retention statement in place of the first.

**On 3 and 4 — the honest answer is a size, and it must not be rounded into a duration.** The notice previously
stated an _intended_ seven days, enforced by nothing. `k3s.sh` set no container-log limits, so the kubelet defaults
applied and the real answer was "until the disk fills". It now sets `container-log-max-size=10Mi` and
`container-log-max-files=3`, which is a bound but not a period. **The notice must therefore not claim a number of days
for server logs** until OpenObserve's retention policy exists (#271). At that point the duration becomes real and this
row changes.

**Two caveats on that, both load-bearing:**

- **The kubelet limits reach a node only when it is provisioned**, because they are cloud-init. Production does not
  exist yet (#285), so it will be born with them. **The running staging node predates the change and does not have
  them.** It needs re-provisioning, or a manual edit plus a k3s restart, before any claim about staging's retention is
  true.
- The `10Mi × 3` figures are reasoned, not measured. The number to revisit is **the duration they buy**, once there is real traffic to measure against.

What is settled and must not drift:

- **The Spring applications log no IP addresses.** `RequestLoggingFilter` logs `METHOD /path -> status (Nms)` — confirmed by observation on 2026-08-19, not
  only by reading the code. **Never add the client IP to it.**
- **nginx's access log must never regain an address field.** `ej_no_ip` exists to be the thing that is edited, and
  the base image's `main` format is what it overrides. A future base-image bump that changed `main` would now be inert
  here, which is the point of defining our own.
- **Do not claim server logs contain no personal data** if any address is ever reintroduced. A dynamic IP held by the
  operator of an online service is personal data — _Breyer_ (C-582/14). Today the claim is available because no
  address is logged. It is a consequence of a decision, not a permanent property.
- **The retention period in the notice must be the one actually configured.** A stated period that rotation does not
  enforce is a worse defect than a longer honest one. That is exactly the defect that existed here until this issue.

### 7.6 The disclaimer

Two registers, deliberately not literal translations of each other:

<!-- ste-lint: allow the published wording of the disclaimer, quoted verbatim in both languages -->

- **Footer** (brand voice): _"Event data is aggregated from public sources and provided without warranty — always
  check with the venue before you go."_ / _"Die Event-Daten stammen aus öffentlichen Quellen — alle Angaben ohne
  Gewähr. Frag im Zweifel bei der Location nach, bevor du losziehst."_
- **Imprint** (formal): _"…provided without warranty as to accuracy, completeness or timeliness."_ / _"Alle Angaben
  erfolgen ohne Gewähr für Richtigkeit, Vollständigkeit und Aktualität."_

Treat these as a translation unit, not a legal constant: translate from intent, never word for word.

**These two registers are the whole answer. There is no Terms of Use page** (#478). A visitor who never registers agrees
to nothing, so terms would bind nobody. A third copy of the disclaimer would be drift risk, not protection. The
database-maker's right (§§ 87a ff. UrhG) applies whether or not a page claims it. What limits bulk access is rate
limiting (#268) and `robots.txt`, not a document. **This reverses when submissions or accounts land** (#399, #398).
Then there is a contract, and user content the site hosts rather than links to.

### 7.7 Keeping the notice true

A privacy notice describes what the system _actually does_, so it becomes false the moment the system changes. The
change that breaks it is rarely labelled "privacy work". The standing list of triggers lives in
**[`AGENTS.md`](../AGENTS.md)**, with a checkbox in the PR template. Both are part of the mechanism, not documentation
about it.

The review date on the privacy page (`LAST_REVIEWED` in `lib/legal.ts`) is the visible half. An undated notice cannot
be audited.

### 7.8 DSGVO generator — as a cross-check, not a source

Running the German notice past a generator such as [datenschutz-generator.de](https://datenschutz-generator.de/) is
worthwhile _as a second opinion_. The German it produces is the idiom a German reader expects. It is **not** a
substitute for the notice being written from what the system does. A generator emits boilerplate for processing you do
not perform, which §7.2 rules out.

**Done on 2026-09-03 (#279), against the generator's own published output and its Art. 13 checklist.** Comparison, not
submission: nothing of ours was entered into the form. Three things came out of it.

**One correction, applied.** Our Beschwerderecht named two fora — habitual residence and place of work. Art. 77 (1)
names three. The notice now also names the place of the alleged infringement, in both languages.

**Two structural differences, deliberately kept, and both are now reviewer questions.** The generator always carries a
`Sicherheitsmaßnahmen` clause and an `Übersicht der Verarbeitungen` table. Neither is an Art. 13 requirement. The
second is the more interesting one here. It states _categories of data subject_. This site's largest such category is
artists who never visited it, and §7.3a holds that fact while the notice does not.

**What the generator would have produced is the argument for not using one as a source.** Its defaults include a
cookie section, a contact-management section, newsletters and social media — none of which happen here. Its hosting
clause states **"maximal 30 Tage"** for logfiles as boilerplate. Ours states 14 days because 14 days is configured.
That is precisely the defect §7.5 names, handed to you pre-written. Its deletion clause is framed on withdrawn
consent, and nothing here rests on consent.

**The generator's own guidance agrees with §7.2**: it warns against listing purposes _"auf Vorrat"_, and says to
disclose only processing actually planned.

## 8. Imprint (§ 5 DDG)

The imprint names the provider, a reachable postal address, an email address, and the person responsible for
editorial content under § 18 (2) MStV. The last is there because an events guide that curates third-party content can
fall under the journalistic-editorial test. Naming someone costs one line and removes the question.

It states explicitly what does _not_ apply. No commercial register entry, no VAT ID under § 27a UStG, no supervisory
authority, no regulated professional title, and no participation in consumer arbitration.

### 8.3 The address

§ 5 DDG requires a _ladungsfähige Anschrift_, and a private individual has no company address to use. The decision was to **rent one from
[Postflex](https://www.postflex.de/)** — done on **2026-08-21**, €39.90/year ([ops/COSTS.md](ops/COSTS.md)). It is in `CONTROLLER` in `lib/legal.ts` and
renders on all four legal pages.

There is no way around this by omitting the imprint — Art. 13 GDPR requires the controller's identity and contact details in the privacy notice regardless.

**`careOf` is its own field, and that is not cosmetic.** German postal convention puts the `c/o` line between the name
and the street, and the customer number in it is what routes the post. An envelope carrying the street but not the
number may never arrive. Folded into `street` it would render as one line, and read as an address that is _almost_
right. That is the worst kind: reachable-looking and undeliverable. A unit test asserts the number's shape for the
same reason.

**The guard now holds the other way round.** `CONTACT_DETAILS_ARE_PROVISIONAL` is `false`, and the banner no longer
claims the details are placeholders. The test in `legal.spec.ts` fails if the flag and the address ever disagree in
**either** direction. **Set it back to `true` if the rental lapses.** An imprint naming an address that no longer
forwards fails § 5 DDG while looking entirely finished. Nothing in code can observe a missed renewal.

That the tripwire works is not a claim here. Replacing the address broke a view test that asserted the pages call the
contact details placeholders. It caught a page and a test disagreeing about reality, which is what it was for.

### 8.4 Donations

Possible later, not now. When the time comes, `FUNDING.yml` goes first, with zero site impact. On the site,
**link out, never embed**: an embedded payment widget would introduce a processor and a third-party request. A
commercial change also alters the § 5 DDG analysis, not only the privacy notice.

## 9. Third-party licences and notices

### 9.1 Tooling — not ORT

[ORT](https://github.com/oss-review-toolkit/ort) was evaluated and judged disproportionate for this project. What runs instead:

- `npm run generate:notices` produces the committed `src/assets/notices.json` behind `/legal/notices` (642
  components). It covers the JVM runtime tree and the frontend production tree.
- **Two allow-list gates**, because one tool sees only one ecosystem. `./gradlew checkLicense` covers the JVM tree, and
  `npm run check:licenses` covers npm. The npm half was missing at first, so the notices page disclosed frontend
  licences that nothing audited.
- `dependency-review.yml` enforces a deny-list on PRs.

The policy files are `config/allowed-licenses-jvm.json` and `config/allowed-licenses-npm.json`. Note that the JVM one
lists **prose licence names**, not SPDX ids. The Gradle plugin's normaliser emits `The 2-Clause BSD License`, and using
an SPDX id there produces a false failure.

### 9.2 Which licences to avoid

The question is not "compatible with Apache-2.0" in the abstract, but **"compatible with a publicly reachable network service whose source is public under
Apache-2.0"**.

| Category                   | Examples                                         | Verdict                                                                                                                                                  |
| -------------------------- | ------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Permissive                 | MIT, BSD-2/3, Apache-2.0, ISC, Unlicense, Zlib   | ✅ Use freely. Attribution only — the notices page satisfies it.                                                                                         |
| Weak copyleft              | MPL-2.0, EPL-2.0, CDDL, LGPL                     | ⚠️ Acceptable for unmodified library use; file-level or relinking obligations. Prefer an alternative; record why if used.                                |
| Strong copyleft            | GPL-2.0, GPL-3.0                                 | ❌ Avoid. GPL-2.0-only is outright incompatible with Apache-2.0.                                                                                         |
| **Network copyleft**       | **AGPL-3.0**                                     | ❌ **The one to watch.** § 13 fires on _network interaction_, not distribution — this project is the trigger case. It would relicense the combined work. |
| Source-available           | BUSL/BSL, SSPL, Elastic 2.0, FSL, Commons Clause | ❌ Not OSI-approved; several forbid exactly "offer this as a service".                                                                                   |
| Unknown / missing / custom | no metadata, bespoke terms                       | ❌ Treat as a build failure until resolved. The largest real category in npm trees.                                                                      |

Two specifics. Prefer **OpenSearch** (Apache-2.0) over Elasticsearch if that step is ever taken. And keep
**FullCalendar's premium plugins** out: the standard packages are MIT, and the premium ones are commercially
licensed.

## 12. Accessibility (WCAG 2.1 Level AA)

**Target: WCAG 2.1 Level AA** — the level German and EU law reference (BFSG / EN 301 549).

What enforces it: `eslint-plugin-vuejs-accessibility` on source, and an `@axe-core/playwright` sweep over every static route **in both locales and both
themes**. German is reliably longer than English, so it is where an overflow or contrast regression actually appears.
The sweep already caught two real contrast failures, and both were fixed at the design tokens rather than at the call
sites.

**Do not claim conformance publicly** until something measures it end to end. axe finds roughly a third of WCAG
issues. An accessibility statement is a _claim_, so it can only be published once the target is actually met.

The rules themselves are in [`events-frontend/AGENTS.md` §Accessibility](../events-frontend/AGENTS.md) — the frontend file, because that is what an agent
editing a `.vue` file loads.

## 13. Decision log

Settled questions, so they are not re-litigated. The reasoning is in the section named.

| #   | Question               | Decision                                                                                                          | Where                |
| --- | ---------------------- | ----------------------------------------------------------------------------------------------------------------- | -------------------- |
| 1   | Imprint address        | A rented _ladungsfähige Anschrift_ from Postflex, €39.90/yr. `c/o` is its own field                               | §8.3                 |
| 2   | Legal-page language    | English and German, German authoritative                                                                          | §6.1                 |
| 3   | First public version   | `0.1.1`; `main` carries `0.1.1-SNAPSHOT`; only a released `X.Y.Z` links; `0.1.0` skipped                          | §4.7                 |
| 4   | `package.json` version | Mirrors the Gradle version, kept in step by hand, without `-SNAPSHOT`                                             | §4.6                 |
| 5   | Actuator               | `/actuator/info` internally **and** `GET /meta` publicly — same bean, different consumers                         | §4.4                 |
| 6   | Code of Conduct        | Contributor Covenant **3.0** (not GitHub's built-in 2.1 template)                                                 | `CODE_OF_CONDUCT.md` |
| 7   | Donations              | Possible later. `FUNDING.yml` first; on the site link out, never embed                                            | §8.4                 |
| 8   | Localisation           | English + German — [ADR-013](adr/ADR-013_LOCALISATION.md)                                                         | §6.2                 |
| 9   | Accessibility          | Target **WCAG 2.1 AA**, with linting and an axe sweep enforcing it                                                | §12                  |
| 10  | Licence tooling        | Not ORT: generated notices plus two allow-list gates and a PR deny-list                                           | §9.1                 |
| 11  | Version source         | Build-stamped from `gradle.properties`, never the GitHub API                                                      | §4                   |
| 12  | Role mailboxes         | Hetzner Webhosting S, **not** a mail specialist — the account-level AVV already covers it, so no second processor | `ops/EMAIL.md`       |
| 13  | Art. 28 contracts      | One: Hetzner's AVV, concluded. No third-country transfer to name at all                                           | §14                  |
| 14  | Event-data retention   | Kept while the calendar operates, no deletion by age; erased on Art. 21 objection — #362                          | §7.3a                |
| 15  | Terms of Use           | None. No accounts and no contract with the visitor, so the §7.6 disclaimer is the whole answer — #478             | §7.6                 |

## 14. Open items — what is **not** signed off

The site cannot go live until these are closed. They are tracked as issues in the `v0.3 — Launch-ready` and
`v1.0 — Go-live` milestones, with the deployment-blocked ones labelled `needs-deployment`. This section says what each
one _means_.

**Blocking:**

1. **§7.3a does not cover the role mailboxes.** An email address is a category of personal data the processing
   inventory was not written against. So is the body of whatever someone writes to `hello@` or `security@`. That is a
   disclosure question wanting a legal read, not a mechanical edit.
2. **A qualified review of the German privacy notice.** The drafts are careful and test-covered, and neither makes
   them _reviewed_. This is the item no amount of engineering substitutes for.
   [`LEGAL_REVIEW_BRIEF.md`](LEGAL_REVIEW_BRIEF.md) is what a reviewer is handed, and carries the questions.

**Not blocking, recorded so it is not rediscovered:**

3. **`1.0.0` and dropping the beta badge** — one decision, deliberately deferred (§4.7).
4. **An accessibility statement** — only publishable once conformance is actually measured (§12).
5. **`FUNDING.yml`** — deliberately absent until donations are wanted (§8.4).

### Two rules this section exists to enforce

**A notice naming processors without a DPA in place is worse than one naming none.** `PROCESSOR_CONTRACTS_PENDING` is
the machine-readable record of the Art. 28 position, because nothing in code can observe a signed PDF. It is `false`
today: Hetzner's AVV is concluded, §5 names Hetzner in Germany, and there is no transfer outside the EU to disclose.
**Set it back to `true` if that contract lapses, is superseded, or a second processor is added without one.** Filing
matters as much as signing. Concluding a contract and not filing the countersigned copy is the same position as not
concluding it, the day somebody asks.

**A blocker outlives the thing that blocked it.** This section twice carried an item whose stated reason was false for
days. When you close something here, delete the item rather than annotating it. What survives a closed item is at most
one sentence of reasoning, moved into the section it constrains.

**healthchecks.io needs no entry in §5, and the assessment is recorded rather than assumed.** The ping is a bare HTTPS
`GET` to an opaque random UUID, with no body and no query string. No personal data, no database contents, nothing
identifying a visitor. What it reveals is our server's public IP and the timing of the pings — an address of ours, not
of a data subject. That is not processing on our behalf, so there is no Art. 28 relationship and no entry. **Re-open
this the moment a ping gains a body**, because `/fail` and `/log` accept one, and a payload is where this stops
holding.

**Better Stack needs no entry in §5 either.** The reasoning differs from healthchecks.io's. The monitor makes an
HTTPS `GET` to a public page of ours every three minutes. It asserts the status and one string in the body
([ADR-021](adr/ADR-021_PUBLIC_SITE_MONITORING.md)). It sends us a request. We send it no data. What it holds about us
is a public URL, our server's IP, response timings and the account email. That is not processing on our behalf, so
there is no Art. 28 relationship, no DPA and no entry.

**Better Stack, Inc. is a Delaware corporation, and it says it processes personal data primarily in the European
Union.** The US incorporation is recorded here because it was weighed and not skipped. It changes nothing, for one
reason. The monitor receives no personal data at all. So no transfer of personal data occurs, and a transfer mechanism
has nothing to protect. An EU-incorporated vendor was preferred at first and rejected on the evidence. Two of the
candidates run on Hetzner, which is the infrastructure this layer exists to outlive.

**The privacy notice does not change, and that is a decision rather than an omission.** §Rule 1 requires a notice
update for a new third-party request. That rule means a request the visitor's browser makes. This is a request made
**to** us, by a machine, for a page that is already public. No visitor is involved and nothing reaches the visitor's
device. **Re-open this if the monitor is ever pointed at a page behind a login, or at a URL carrying a query string
with visitor data.**

**Processor forms are answered from
[§7.3a](#73a-what-is-processed-and-about-whom--the-answer-every-processor-form-asks-for)** — categories of data, and
categories of data subject. Every agreement, this document and §5 of the notice therefore describe the same
processing. The next form is filled in from there rather than from memory.

## 15. References

- [GDPR](https://gdpr-info.eu/) — esp. [Art. 6](https://gdpr-info.eu/art-6-gdpr/), [Art. 13](https://gdpr-info.eu/art-13-gdpr/),
  [Art. 21](https://gdpr-info.eu/art-21-gdpr/), [Art. 28](https://gdpr-info.eu/art-28-gdpr/)
- [Impressumspflicht nach § 5 DDG (eRecht24)](https://www.e-recht24.de/artikel/datenschutz/209.html) ·
  [ladungsfähige Anschrift ≠ Meldeadresse](https://zerodox.de/blog/ladungsfaehige-anschrift-meldeadresse)
- [WCAG 2.1](https://www.w3.org/TR/WCAG21/) · [How to Meet WCAG (quick reference)](https://www.w3.org/WAI/WCAG21/quickref/)
- [FSF licence list](https://www.gnu.org/licenses/license-list.html) · [Contributor Covenant 3.0](https://ethicalsource.dev/projects/contributor-covenant-3/)
- [Spring Boot Actuator — info endpoint](https://reflectoring.io/spring-boot-info-endpoint/) · [OSS Review Toolkit](https://github.com/oss-review-toolkit/ort)
