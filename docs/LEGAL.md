# Legal, compliance and site-chrome policy

> **Status: current state, not a plan.** This replaces `FOOTER_AND_LEGAL_PLAN.md`, whose implementation is complete — every phase shipped between 2026-08-07 and
> 2026-08-08. What is written here is what the site *does*, and what future changes must keep true.
>
> **It is not signed off.** Several decisions this document records are provisional, and the site cannot go live until §14 is closed. Read that section before
> treating anything here as final.
>
> Related: [ADR-012 (cloud platform)](adr/ADR-012_CLOUD_PLATFORM.md) · [ADR-013 (localisation)](adr/ADR-013_LOCALISATION.md) ·
> [ADR-014 (rendering)](adr/ADR-014_RENDERING_STRATEGY.md) · [the `v1.0 — Go-live` milestone](https://github.com/enorm-labs/event-junkie/milestones) · [BRANDING.md](BRANDING.md)

## 1. Scope and how to use this

This covers the site's legal surface and the chrome around it: the footer, the imprint and privacy notice, third-party licence attribution, how the running
version is exposed, the beta marker, and the accessibility target.

**Section numbers are inherited deliberately.** Around fifty references across the codebase cite sections of the document this replaces (`§9.2`, `§6.1`, `§4.4`
…), and keeping them valid was worth more than a tidy sequence. §10 and §11 are absent because they described *work to do* rather than policy; §2 has been
rewritten from a proposal into a description.

**Operational rules are not repeated here.** They live where the person changing the code will actually see them:

| Rule                                                  | Where it lives                                                                                        |
|-------------------------------------------------------|-------------------------------------------------------------------------------------------------------|
| Privacy/GDPR re-check triggers                        | [`AGENTS.md` §Privacy & GDPR](../AGENTS.md)                                                           |
| Version in `gradle.properties`, `package.json` mirror | [`AGENTS.md` §Versioning](../AGENTS.md) · [`events-frontend/AGENTS.md`](../events-frontend/AGENTS.md) |
| Accessibility rules                                   | [`events-frontend/AGENTS.md` §Accessibility](../events-frontend/AGENTS.md)                            |
| Localisation and SEO rules                            | [`events-frontend/AGENTS.md`](../events-frontend/AGENTS.md)                                           |
| Open-source notice generation                         | [`events-frontend/AGENTS.md` §Open-source notices](../events-frontend/AGENTS.md)                      |

## 2. The footer and the legal pages, as built

The footer appears on every route (`AppFooter.vue`) and carries: the brand line and tagline, the data disclaimer (§7.6), a **Project** column (source, issues,
contributing, changelog), a **Legal** column (imprint, privacy, open-source notices), the copyright and licence line (§3), and the version (§4).

Legal content lives under `/{locale}/legal/*` — `imprint`, `privacy`, `notices` — nested so later additions (an accessibility statement, a data-sources page)
have an obvious home. Each of the four long-form pages, including About, is a **separate component per language** rather than translated strings: a legal page
is a document, reviewed as a document, possibly by someone who does not read Vue. The reasoning is recorded in `views/localisedView.ts`, and the rule that
follows from it — *edit both language versions or neither* — in `events-frontend/AGENTS.md`.

German practice expects the imprint within a couple of clicks from any page, which the footer satisfies; an e2e test holds that property.

**Deliberately not in the footer:** a language switcher duplicate (the header and footer each carry one, with distinct landmark names), social icons for
accounts that do not exist, and a newsletter signup — which would introduce user data the site does not otherwise process.

## 3. Copyright and licence line

The footer states `© <year> Event Junkie` and that the code is under **Apache-2.0**, linking the licence text in the repository.

The distinction that matters, and which the imprint repeats: **our code is Apache-2.0; the event data is not ours to license.** Event descriptions, images and
other material originating from venues, promoters and artists remain their rights holders' property. Conflating the two would be a licensing claim over other
people's material.

## 4. Version and commit exposure

### 4.1 Why the version does not come from the GitHub API

The obvious implementation — fetch the latest release from `api.github.com` — was rejected. It sends every visitor's IP address to GitHub, which makes GitHub a
recipient in the privacy notice and adds a third-party request to a page that otherwise makes none (see the standing reminder in `AGENTS.md`). It also reports
what was *released*, not what is *running*, which is the question a version in the footer exists to answer.

### 4.2 Single source of truth

`version` in the root **`gradle.properties`** is authoritative. Nothing else may declare it independently.

### 4.3 Getting it into the artifact

`springBoot { buildInfo }` writes the version plus the commit into `META-INF/build-info.properties` at build time, which Spring exposes as a `BuildProperties`
bean. The build timestamp is kept: `bootBuildInfo` stays `UP-TO-DATE` between builds, so it costs nothing, and it is served as `buildTime`.

### 4.4 Two consumers, one bean

`/actuator/info` is for operators and is **not** publicly routed; `GET /meta` is the public endpoint the frontend calls. Same bean, different audiences — an
actuator endpoint on the public ingress is a larger surface than one JSON route.

### 4.6 The frontend mirror

`package.json`'s `version` mirrors the Gradle version **by hand**, without the `-SNAPSHOT` suffix. Automating it was considered and rejected as more machinery
than the problem deserves; the convention is recorded in both `AGENTS.md` files, which is what keeps it honest.

### 4.7 Versioning scheme

First public version **`0.1.0`**; `main` carries `0.1.0-SNAPSHOT`, which renders unlinked in the footer because there is no release to link to. Reaching
`1.0.0` and dropping the beta badge (§5) are **one decision, not two**.

## 5. The beta marker

The header carries a `beta` badge linking to `/{locale}/about#beta`, where the page explains what beta does and does not mean — incomplete coverage, possibly
stale details, and explicitly *not* "a trial you get charged for" or "we sell your data". The anchor id stays `beta` in both languages.

It comes off with `1.0.0` (§4.7).

## 6. Language

### 6.1 Both languages, German authoritative

The site publishes English and German. **The German versions of the imprint and privacy notice are authoritative**, stated on each page in both languages via a
`LegalPage` prop so it cannot be forgotten on one of the four.

The history is worth keeping, because it constrains future changes: the legal pages shipped English-only first, on the explicit condition that German ship *in
the same release as the German UI*. An English-only imprint on a site presenting itself in German is the configuration where the Art. 12 GDPR "clear and plain
language" argument turns against us. That condition was met on 2026-08-08 — and it applies again to **any third locale**: publishing a locale means publishing
its legal pages in the same release.

### 6.2 Localisation

Delivered; see [ADR-013](adr/ADR-013_LOCALISATION.md). Locale-prefixed routes, per-locale legal pages, a switcher in both header and footer, `hreflang`,
`og:locale` and structured data.

## 7. GDPR / DSGVO

### 7.1 What actually applies

A German controller running a public site with no accounts, no analytics, no advertising and no third-party embeds. The processing that exists: server logs
(Art. 6 (1) (f)), one `localStorage` key for the theme preference and one for the locale hint, and publicly announced event data (§7.3).

### 7.2 The Art. 13 checklist

The notice is structured against the twelve mandatory items. Omitting one is the usual defect and is invisible without a list, so it is enforced by test:
`views/legal/__tests__/legalViews.spec.ts` runs the checklist **against each language separately**, because a section missing from one version is invisible from
the other.

The notice deliberately does **not** describe processing that does not happen — no cookie table, no consent withdrawal, no analytics section. A notice
describing imaginary processing is as inaccurate as one omitting real processing, and generators produce exactly that (§7.8).

### 7.3 Artists are people

Event data is mostly not personal data, but **where a performing artist is a natural person, their name and their billing in a line-up are**. The notice says so
and offers a removal route requiring no reason and no public discussion. This is the most likely real complaint the site will ever receive.

It also governs machine-readable output: structured data describes performers as `PerformingGroup`, never `Person`, because of the two types Google accepts only
one asserts that a named individual is a natural person.

### 7.4 Device storage — `localStorage`, not just cookies

The site sets **no cookies**. It stores a `theme` key and a locale hint. § 25 TDDDG covers *storage on terminal equipment*, not cookies specifically — both
items are strictly necessary for a setting the visitor chose, so § 25 (2) 2 applies and **no consent banner is required**.

That is a property worth defending deliberately: the first non-essential stored item makes a banner mandatory. It is a product decision, not an implementation
detail — escalate rather than implement.

### 7.5 Logging — **still open**

#### 7.5.1 The four decisions

The notice must state truthfully which logs hold personal data, what is in them, and for how long. Four decisions remain — whether Traefik and the nginx
container log real client IPs at all, whether any logged IP is truncated, the retention period per log stream, and where retention is actually enforced. They
depend on infrastructure that does not exist yet; see §14. **They became more load-bearing on 2026-08-10**: with Cloudflare removed from the architecture there
is no proxy between the visitor and the origin, so these four are the *only* thing standing between a request and a real IP address on disk.

What is already settled and must not drift:

- **The Spring applications log no IP addresses.** `RequestLoggingFilter` logs `METHOD /path -> status (Nms)`. **Never add the client IP to it.** It is IP-free
  today by design; this is exactly the class of change the `AGENTS.md` reminder exists to catch.
- **"Do nothing" is not a neutral default, and since 2026-08-10 it is the *unsafe* default.** Traefik's access log is *off* by default; nginx's is *on*. This
  paragraph used to add that the origin only ever saw a Cloudflare proxy IP, so the exposure arrived by an explicit "restore the real client IP" change.
  [ADR-012's amendment](adr/ADR-012_CLOUD_PLATFORM.md) removed Cloudflare, so **there is no proxy and the origin sees the visitor's real address**. nginx will
  therefore write real client IPs to disk from the first request unless its access log is configured not to. Nobody has to change anything for that to happen —
  which is the reverse of the situation this note was originally written for.
- **Do not claim server logs contain no personal data.** A dynamic IP address held by the operator of an online service is personal data — *Breyer* (C-582/14) —
  so a log line carrying one needs no correlation argument to qualify. The earlier version of this bullet reasoned that a timestamp plus request line could be
  correlated with Cloudflare's own records; that route is gone with Cloudflare, and it has been replaced by the more direct problem of holding the address
  itself. Truncation is now the lever that decides the answer, which is why it is one of the four open decisions rather than an implementation detail.
- **The retention period in the notice must be the one actually configured.** A stated period that rotation does not enforce is a worse defect than a longer
  honest one.

### 7.6 The disclaimer

Two registers, deliberately not literal translations of each other:

- **Footer** (brand voice): *"Event data is aggregated from public sources and provided without warranty — always check with the venue before you go."* / *"Die
  Event-Daten stammen aus öffentlichen Quellen — alle Angaben ohne Gewähr. Frag im Zweifel bei der Location nach, bevor du losziehst."*
- **Imprint** (formal): *"…provided without warranty as to accuracy, completeness or timeliness."* / *"Alle Angaben erfolgen ohne Gewähr für Richtigkeit,
  Vollständigkeit und Aktualität."*

Treat these as a translation unit, not a legal constant: translate from intent, never word for word.

### 7.7 Keeping the notice true

A privacy notice describes what the system *actually does*, so it becomes false the moment the system changes — and the change that breaks it is rarely labelled
"privacy work". The standing list of triggers lives in **[`AGENTS.md`](../AGENTS.md)**, with a checkbox in the PR template. Both are part of the mechanism, not
documentation about it.

The review date on the privacy page (`LAST_REVIEWED` in `lib/legal.ts`) is the visible half: an undated notice cannot be audited.

### 7.8 DSGVO generator — as a cross-check, not a source

Running the German notice past a generator such as [datenschutz-generator.de](https://datenschutz-generator.de/) is worthwhile *as a second opinion*, because
the German it produces is the idiom a German reader expects. It is **not** a substitute for the notice being written from what the system does — generators emit
boilerplate for processing you do not perform, which §7.2 rules out. Not yet done (§14).

## 8. Imprint (§ 5 DDG)

The imprint names the provider, a reachable postal address, an email address, and the person responsible for editorial content under § 18 (2) MStV — the last
because an events guide that curates third-party content can fall under the journalistic-editorial test, and naming someone costs one line and removes the
question.

It states explicitly what does *not* apply: no commercial register entry, no VAT ID under § 27a UStG, no supervisory authority, no regulated professional title,
and no participation in consumer arbitration.

### 8.3 The address

§ 5 DDG requires a *ladungsfähige Anschrift*, and a private individual has no company address to use. The decision: **rent one from
[Postflex](https://www.postflex.de/)**, ordered once `event-junkie.de` is registered.

There is no way around this by omitting the imprint — Art. 13 GDPR requires the controller's identity and contact details in the privacy notice regardless.

Until then the address is a **guarded placeholder**: `CONTACT_DETAILS_ARE_PROVISIONAL` in `lib/legal.ts` is `true`, a banner says so on the page, and a unit
test fails if the flag and the placeholder ever disagree. That guard is what stops a false address going live quietly.

### 8.4 Donations

Possible later, not now. When the time comes: `FUNDING.yml` first (zero site impact), and on the site **link out, never embed** — an embedded payment widget
would introduce a processor and a third-party request. Commercial changes also alter the § 5 DDG analysis, not just the privacy notice.

## 9. Third-party licences and notices

### 9.1 Tooling — not ORT

[ORT](https://github.com/oss-review-toolkit/ort) was evaluated and judged disproportionate for this project. What runs instead:

- `npm run generate:notices` produces the committed `src/assets/notices.json` behind `/legal/notices` (642 components), covering the JVM runtime tree and the
  frontend production tree.
- **Two allow-list gates**, because one tool sees only one ecosystem: `./gradlew checkLicense` for the JVM tree and `npm run check:licenses` for npm. The npm
  half was missing at first — the notices page disclosed frontend licences that nothing audited.
- `dependency-review.yml` enforces a deny-list on PRs.

The policy files are `config/allowed-licenses-jvm.json` and `config/allowed-licenses-npm.json`. Note the JVM one lists **prose licence names**, not SPDX ids —
the Gradle plugin's normaliser emits `The 2-Clause BSD License`, and using SPDX ids there produces false failures.

### 9.2 Which licences to avoid

The question is not "compatible with Apache-2.0" in the abstract, but **"compatible with a publicly reachable network service whose source is public under
Apache-2.0"**.

| Category                   | Examples                                         | Verdict                                                                                                                                                  |
|----------------------------|--------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| Permissive                 | MIT, BSD-2/3, Apache-2.0, ISC, Unlicense, Zlib   | ✅ Use freely. Attribution only — the notices page satisfies it.                                                                                         |
| Weak copyleft              | MPL-2.0, EPL-2.0, CDDL, LGPL                     | ⚠️ Acceptable for unmodified library use; file-level or relinking obligations. Prefer an alternative; record why if used.                                |
| Strong copyleft            | GPL-2.0, GPL-3.0                                 | ❌ Avoid. GPL-2.0-only is outright incompatible with Apache-2.0.                                                                                         |
| **Network copyleft**       | **AGPL-3.0**                                     | ❌ **The one to watch.** § 13 fires on *network interaction*, not distribution — this project is the trigger case. It would relicense the combined work. |
| Source-available           | BUSL/BSL, SSPL, Elastic 2.0, FSL, Commons Clause | ❌ Not OSI-approved; several forbid exactly "offer this as a service".                                                                                   |
| Unknown / missing / custom | no metadata, bespoke terms                       | ❌ Treat as a build failure until resolved. The largest real category in npm trees.                                                                      |

Two specifics: prefer **OpenSearch** (Apache-2.0) over Elasticsearch if that step is ever taken, and keep **FullCalendar's premium plugins** out — the standard
packages are MIT, the premium ones are commercially licensed.

## 12. Accessibility (WCAG 2.1 Level AA)

**Target: WCAG 2.1 Level AA** — the level German and EU law reference (BFSG / EN 301 549).

What enforces it: `eslint-plugin-vuejs-accessibility` on source, and an `@axe-core/playwright` sweep over every static route **in both locales and both
themes**. German is reliably longer than English, so it is where an overflow or contrast regression actually appears. The sweep has already caught two real
contrast failures, which were fixed at the design tokens rather than at the call sites.

**Do not claim conformance publicly** until something has measured it end to end; axe finds roughly a third of WCAG issues. An accessibility statement is a
*claim*, so it can only be published once the target is actually met.

The rules themselves are in [`events-frontend/AGENTS.md` §Accessibility](../events-frontend/AGENTS.md) — the frontend file, because that is what an agent
editing a `.vue` file loads.

## 13. Decision log

| #  | Question               | Decision                                                                                                     | Where                |
|----|------------------------|--------------------------------------------------------------------------------------------------------------|----------------------|
| 1  | Imprint address        | Rent a *ladungsfähige Anschrift* from Postflex after domain registration; guarded placeholder until then     | §8.3                 |
| 2  | Legal-page language    | English first, German in the same release as the German UI and authoritative from then — **done 2026-08-08** | §6.1                 |
| 3  | First public version   | `0.1.0`; `main` carries `0.1.0-SNAPSHOT`; `-SNAPSHOT` renders unlinked                                       | §4.7                 |
| 4  | `package.json` version | Mirrors the Gradle version, kept in step by hand, without `-SNAPSHOT`                                        | §4.6                 |
| 5  | Actuator               | `/actuator/info` internally **and** `GET /meta` publicly — same bean, different consumers                    | §4.4                 |
| 6  | Code of Conduct        | Contributor Covenant **3.0** (not GitHub's built-in 2.1 template)                                            | `CODE_OF_CONDUCT.md` |
| 7  | Donations              | Possible later. `FUNDING.yml` first; on the site link out, never embed                                       | §8.4                 |
| 8  | Localisation           | English + German — **done**, see [ADR-013](adr/ADR-013_LOCALISATION.md)                                      | §6.2                 |
| 9  | Accessibility          | Target **WCAG 2.1 AA**, with linting and an axe sweep enforcing it                                           | §12                  |
| 10 | Licence tooling        | Not ORT: generated notices plus two allow-list gates and a PR deny-list                                      | §9.1                 |
| 11 | Version source         | Build-stamped from `gradle.properties`, never the GitHub API                                                 | §4.1                 |

## 14. Open items — what is **not** signed off

The site cannot go live until these are closed. They are tracked as issues in the `v0.3 — Launch-ready` and `v1.0 — Go-live` milestones, the deployment-blocked ones labelled `needs-deployment`; this section says what each one *means*.

**Blocking, and dependent on infrastructure:**

1. **The four logging decisions** (§7.5) — whether Traefik and the nginx container log real client IPs, truncation, retention period, and where retention is
   enforced. The notice currently states an *intended* seven days.
2. **`INFRASTRUCTURE_IS_PROPOSED = true`** — [ADR-012](adr/ADR-012_CLOUD_PLATFORM.md) is `Accepted` as of 2026-08-10, but accepting it deployed nothing, so the
   notice still describes an intended deployment. It must be re-checked against what actually runs once the platform is provisioned
   ([#260](https://github.com/enorm-labs/event-junkie/issues/260)), and the flag cleared then — not now.
3. **Art. 28 contracts** — now a single one: **Hetzner's AVV**. The 2026-08-10 amendment to
   [ADR-012](adr/ADR-012_CLOUD_PLATFORM.md) removed Cloudflare, so there is no second DPA to accept and **no third-country transfer to name at all** — the
   placeholder sentence about a transfer mechanism comes out rather than getting filled in. *A notice naming processors without a DPA in place is worse than one
   naming none.* Tracked as [#275](https://github.com/enorm-labs/event-junkie/issues/275).
4. **Backup retention** as its own line — it is a separate period from log retention, and if logs are captured by backups the effective retention is the backup
   window, not the rotation one. Check rather than assume.

**Blocking, and dependent on the domain:**

5. **The Postflex address** (§8.3), and clearing `CONTACT_DETAILS_ARE_PROVISIONAL`.
6. **The role mailboxes.** `hello@event-junkie.de` and `security@event-junkie.de` appear in the imprint, the privacy notice, `SECURITY.md` and
   `CODE_OF_CONDUCT.md` — **none of them exists**, because the domain is not registered. Every published reporting route is currently a dead address.

**Blocking, and dependent on a person:**

7. **A qualified review of the German privacy notice.** The drafts are careful and test-covered; neither makes them *reviewed*. This is the item no amount of
   engineering substitutes for.
8. **The DSGVO-generator cross-check** (§7.8) as a second opinion.

**Not blocking, recorded so it is not rediscovered:**

9. **`1.0.0` and dropping the beta badge** — one decision, deliberately deferred (§4.7).
10. **An accessibility statement** — only publishable once conformance is actually measured (§12).
11. **`FUNDING.yml`** — deliberately absent until donations are wanted (§8.4).

## 15. References

- [GDPR](https://gdpr-info.eu/) — esp. [Art. 6](https://gdpr-info.eu/art-6-gdpr/), [Art. 13](https://gdpr-info.eu/art-13-gdpr/),
  [Art. 21](https://gdpr-info.eu/art-21-gdpr/), [Art. 28](https://gdpr-info.eu/art-28-gdpr/)
- [Impressumspflicht nach § 5 DDG (eRecht24)](https://www.e-recht24.de/artikel/datenschutz/209.html) ·
  [ladungsfähige Anschrift ≠ Meldeadresse](https://zerodox.de/blog/ladungsfaehige-anschrift-meldeadresse)
- [WCAG 2.1](https://www.w3.org/TR/WCAG21/) · [How to Meet WCAG (quick reference)](https://www.w3.org/WAI/WCAG21/quickref/)
- [FSF licence list](https://www.gnu.org/licenses/license-list.html) · [Contributor Covenant 3.0](https://ethicalsource.dev/projects/contributor-covenant-3/)
- [Spring Boot Actuator — info endpoint](https://reflectoring.io/spring-boot-info-endpoint/) · [OSS Review Toolkit](https://github.com/oss-review-toolkit/ort)
