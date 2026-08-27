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
- a **Legal** column — imprint, privacy, open-source notices
- the copyright and licence line (§3)
- the version (§4)

Legal content lives under `/{locale}/legal/*` — `imprint`, `privacy`, `notices` — nested so later additions (an
accessibility statement, a data-sources page) have an obvious home. Each of the four long-form pages, including About,
is a **separate component per language** rather than translated strings. A legal page is a document, reviewed as a
document, possibly by someone who does not read Vue. The reasoning is recorded in `views/localisedView.ts`, and the
rule that follows from it — _edit both language versions or neither_ — in `events-frontend/AGENTS.md`.

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

The site publishes English and German. **The German versions of the imprint and privacy notice are authoritative.**
That is stated on each page in both languages, through a `LegalPage` prop, so it cannot be forgotten on one of the
four.

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

The site sets **no cookies**. It stores a `theme` key and a locale hint. § 25 TDDDG covers _storage on terminal
equipment_, not cookies specifically. Both items are strictly necessary for a setting the visitor chose, so § 25 (2) 2
applies and **no consent banner is required**.

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

| #   | Decision                                                | Answer                                                                                       |
| --- | ------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| 1   | Do Traefik and the nginx container log real client IPs? | **Traefik: no, it logs nothing at all.** **nginx: it did, and no longer does**               |
| 2   | Is any logged IP truncated?                             | **Moot — none is logged.** Truncation was rejected as the weaker lever                       |
| 3   | What is the retention period per stream?                | **A size bound, not a duration**: `container-log-max-size=10Mi`, `container-log-max-files=3` |
| 4   | Where is retention actually enforced?                   | **The kubelet, today.** OpenObserve's bucket policy once #271 deploys it (ADR-015)           |

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
not perform, which §7.2 rules out. Not yet done (§14).

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
| 11  | Version source         | Build-stamped from `gradle.properties`, never the GitHub API                                                      | §4.1                 |
| 12  | Role mailboxes         | Hetzner Webhosting S, **not** a mail specialist — the account-level AVV already covers it, so no second processor | `ops/EMAIL.md`       |
| 13  | Art. 28 contracts      | One: Hetzner's AVV, concluded. No third-country transfer to name at all                                           | §14                  |
| 14  | Event-data retention   | Kept while the calendar operates, no deletion by age; erased on Art. 21 objection — #362                          | §7.3a                |

## 14. Open items — what is **not** signed off

The site cannot go live until these are closed. They are tracked as issues in the `v0.3 — Launch-ready` and
`v1.0 — Go-live` milestones, with the deployment-blocked ones labelled `needs-deployment`. This section says what each
one _means_.

**Blocking:**

1. **`INFRASTRUCTURE_IS_PROPOSED = true`** (`events-frontend/src/lib/legal.ts`) — the notice still describes an
   _intended_ deployment. The platform now exists, so this is actionable rather than blocked. Re-check §5 of both
   notices against what actually runs, then clear the flag. Never clear it in advance of that check. The flag is the
   only machine-readable record that the notice and the infrastructure were compared.
2. **Log retention must not state a number of days** for server logs until OpenObserve's retention policy exists
   ([#271](https://github.com/enorm-labs/event-junkie/issues/271)). A period the notice claims and nothing enforces is
   the precise defect §7.5 names. It is worse than an honest longer one.
3. **§7.3a does not cover the role mailboxes.** An email address is a category of personal data the processing
   inventory was not written against. So is the body of whatever someone writes to `hello@` or `security@`. That is a
   disclosure question wanting a legal read, not a mechanical edit.
4. **A qualified review of the German privacy notice.** The drafts are careful and test-covered, and neither makes
   them _reviewed_. This is the item no amount of engineering substitutes for.
5. **The DSGVO-generator cross-check** (§7.8), as a second opinion.

**Not blocking, recorded so it is not rediscovered:**

6. **`1.0.0` and dropping the beta badge** — one decision, deliberately deferred (§4.7).
7. **An accessibility statement** — only publishable once conformance is actually measured (§12).
8. **`FUNDING.yml`** — deliberately absent until donations are wanted (§8.4).

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
