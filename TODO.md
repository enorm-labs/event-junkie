# TODO — Backlog

Single source of truth for planned work. Longer-form context lives in
[docs/VISION_ROADMAP_IDEAS.md](docs/VISION_ROADMAP_IDEAS.md) and
[docs/BRANDING.md](docs/BRANDING.md); this file is the actionable backlog.

Rough priority: **Now** → **Next** → grouped backlog → **Someday / Vision**. Public name is **Event Junkie**; internal/repo name stays **Event Checker**
(see the BRANDING naming rule — don't "fix" internal identifiers).

---

## 🔴 Now (path to go-live)

- [ ] Choose a cloud platform / runtime environment — the evaluation is **complete**: 18 platforms across IaaS, CaaS and PaaS (incl. European PaaS, AWS Elastic
  Beanstalk and App Engine) are costed and scored in [ADR-012](docs/adr/ADR-012_CLOUD_PLATFORM.md), which recommends **Hetzner Cloud + k3s** (~€30/month for
  prod *and* staging) with a ranked fallback list — Sliplane/Coolify, then Clever Cloud or Scalingo, then Cloud Run, then Beanstalk. Nothing below can be built
  until this is settled: **decide and move the ADR from Proposed to Accepted**
- [ ] Register the domain **event-junkie.de** (ADR-012 puts Cloudflare in front for DNS/TLS/CDN/rate limiting on the free plan)
- [ ] Infrastructure as code (Terraform / OpenTofu) — provision the cloud environment reproducibly instead of by hand. Per ADR-012: the `hetznercloud/hcloud`
  provider for servers, networks, firewalls and volumes, with state in Hetzner Object Storage (S3 API) or Terraform Cloud
- [ ] Write the Helm chart — `events-bff` (N replicas), `events-importer` (**`replicas: 1`, `strategy: Recreate`** so a rolling deploy never runs two
  schedulers, per ADR-008) and the frontend, behind one ingress that routes `/` → frontend and `/api` → BFF and **does not route the importer's admin API
  publicly**
- [ ] Create release + deploy workflows (CI/CD) — note ADR-012's known step down: GitHub Actions cannot use OIDC against Hetzner, so deploys authenticate with a
  scoped kubeconfig or deploy key held as a repository secret, rotated deliberately
- [ ] A non-public test/staging stage, separate from production — ADR-012 treats its cost as a first-class criterion and budgets ~€7/month for it on Hetzner
- [ ] Deploy to the chosen cloud platform
- [ ] **PostgreSQL backups + a rehearsed restore** — the load-bearing mitigation of ADR-012's "we own the database" trade: `wal-g` or `pgBackRest` streaming WAL
  and base backups to a Hetzner Storage Box, plus server snapshots. **A restore drill is part of the go-live checklist and repeats on a schedule** — an untested
  backup is not a backup. Highest-risk item created by the ADR; it disappears only if a fallback with managed Postgres is chosen instead
- [ ] Monitoring + alerting, self-hosted or SaaS — ADR-012 makes observability ours on Hetzner (no CloudWatch/Cloud Ops equivalent): either
  `kube-prometheus-stack` + Grafana (same surface as the data dashboard below) or an external free tier. **Alerting must exist before launch, not after the
  first outage**
- [ ] Fix Dependabot security issues → https://github.com/enorm-labs/event-checker/security/dependabot
- [ ] Go-live checklist: legal, security, SEO, monitoring, alerting, dashboards, backups, recovery (incl. the restore drill above). The parts that **cannot be
  done before there is a deployment** are listed separately in [§At go-live & after](#-at-go-live--after-needs-a-live-deployment) — they are not blocked on
  effort, they are blocked on a live origin, so they need to be tracked where they will not read as neglected work

## 🟠 Next

- [ ] Add Authentication & Authorization (best practice for Spring? Keycloak, at least locally for testing? Support Passkey?)
- [ ] Caching (BFF)
- [ ] Protect the public BFF API (rate limiting, DDoS; API gateway?) — ADR-012 gets part of the way there with Cloudflare's free plan in front (proxied DNS,
  edge caching, rate limiting), leaving application-level limits to decide. Residency nuance: Cloudflare terminates TLS at its edge, so strictly German-only
  processing means dropping proxy mode or buying the EU data-localisation add-on
- [ ] Create a test data set — reusable as test fixtures **and** to populate the local DB

---

## UI / UX / Branding

- [ ] Full frontend UX pass — what's missing / improvable? (cross-check the vision + branding docs)
- [ ] **Fix `heading-order` on the list pages** — `/events` and `/venues` go `h1` → `h3`, because `EventCard` / `VenueCard` render an `h3`, which is correct on
  the home page where an `h2` section heading sits above them. Needs a decision about the shared card component (a `level` prop, or a visually-hidden `h2` on
  the list pages), not a local edit. Surfaced by the informational axe `best-practice` pass, which reports it without gating on it
- [ ] **Manual accessibility passes before go-live** — a keyboard-only walkthrough and a screen-reader pass. axe reliably finds roughly a third of WCAG issues,
  so the two automated checks cannot certify WCAG 2.1 AA no matter how thorough they get. Required if a conformance statement is ever wanted for the live site
  (LEGAL.md §12)
- [ ] Improve **branding & UI/visual design** — a dedicated visual pass beyond the UX audit (colour, type, spacing, components, logo, iconography, imagery,
  motion), aligned with [BRANDING.md](docs/BRANDING.md)
- [ ] Verify responsive design + look on mobile
- [ ] **Add a hero screenshot to the README** (events list or calendar, dark mode). The single biggest thing the front page is still missing — a screenshot does
  more than any paragraph currently there. Deliberately sequenced *after* the branding/visual pass above, so it does not have to be retaken immediately
- [ ] Audit that all **user-facing** surfaces read "Event Junkie" (internal stays "Event Checker")
- [ ] Decide on a display/hero typeface vs. staying all-Geist (BRANDING §5.3)
- [ ] "Venue or event missing? Let us know" form (→ GitHub issues?)
- [ ] Feedback form (or link to GitHub issues)
- [ ] Evaluate design tools & AI models for UI + branding — which best generate/refine a site design? Candidates: Google Stitch, v0, Lovable, Figma (+ AI /
  Make), plus image models (e.g. Midjourney) for visuals/mood boards. Explore the "trainspotting"-inspired direction.

## Frontend & BFF

- [ ] Add map to venues overview page — and consider plotting **today's events** on it (pins showing what's on tonight, not just where the venues are)
- [x] Add venue description to the venue detail page — `venue.description` column + full API/UI plumbing; seeded with hand-written blurbs in `dev-seed.http`.
  **Follow-up:** scrape descriptions from each venue's own website (see "Enrich venues" under Importer / Data) and consider other detail-page metadata.
- [ ] Add capacity / venue size to the venue detail page
- [ ] Filter events by venue type
- [ ] Filter venues by venue type and genre
- [ ] Browse/see past events — an archive view (decide retention + UX; ties into the housekeeping delete policy under Importer / Data)
- [ ] Reduce or group the displayed genres — too many distinct tags; needs a grouping/taxonomy decision (UX + data)
- [ ] Decide whether to display event **descriptions** and **source images** — copyright/licensing plus traffic to small sites; if images: store/cache/proxy vs.
  hotlink vs. omit (see the Legal/Compliance copyright item)
- [ ] Always show the number of displayed / found events in list views (verify — may already be the case in places)
- [ ] Make the home page a real entry point into the data — a prominent link to "Browse events", or filtering/searching directly from the home page
- [ ] **BFF-served sitemap for detail routes** — events, venues, artists, promoters. Belongs in the BFF because it holds the data and can leave out events that
  have already happened; the frontend build cannot enumerate them without giving up its independence from the database. (The static-route sitemap, `robots.txt`,
  `hreflang`, canonical URLs, per-page head tags and `schema.org` structured data are all built — see [ADR-014](docs/adr/ADR-014_RENDERING_STRATEGY.md) and
  `events-frontend/AGENTS.md` §SEO surfaces. The remaining SEO work is the meta injector, which needs a deployment and is tracked below.)
- [ ] RSS feed for newly imported events
- [ ] **Enforce that `events-frontend/src/api/schema.d.ts` is current**, rather than relying on the developer remembering. It is generated from the BFF's
  `/v3/api-docs` and committed, and nothing checks it: change a BFF response DTO without regenerating and the frontend keeps type-checking cleanly against an
  API that no longer exists, failing only at runtime. A CI job would have to boot Postgres, run the importer for the Flyway migrations, start the BFF,
  regenerate and fail on a non-empty diff — a JVM in a frontend workflow that currently has none, plus a coupling between the two pipelines. **Deliberately
  deferred until the BFF's public API stops changing daily**; the failure mode and the manual step are documented in `events-frontend/AGENTS.md` §API
  Communication until then
- [ ] **TypeScript 7 — blocked on `vue-tsc`, not on us.** TS 7 (the native Go port) restructured the package and no longer exports `typescript/lib/tsc`, which
  `vue-tsc@3.3.9` requires: `npm run type-check` and `npm run build` both die with `ERR_PACKAGE_PATH_NOT_EXPORTED`. There is no newer `vue-tsc` — 3.3.9 is
  latest — and its peer range (`typescript >=5.0.0`) is simply stale, so npm installs the combination happily and it fails at run time. **Recheck when
  `@vue/language-tools` ships TS 7 support**; the upgrade itself is a one-line version bump once it does. Related: `openapi-typescript` declares
  `peer typescript@^5.x`, which is why `generate:api` runs through `npx` in isolation rather than as a devDependency
- Note: `GET /artists`, `GET /venues`, `GET /promoters` list endpoints exist and are smoke-tested, but only their `/{slug}` detail counterparts have UI pages
  yet.

## Importer / Data

**Bugs:**

Importer defects with a known fix. Accepted limitations — a field the venue simply doesn't publish, or a trade-off a parser makes deliberately — live in that
scraper's own KDoc instead, so only what should actually be *repaired* is listed here.

- [ ] **A late-night club event is dropped at midnight while it is still running.** `EventUpsertService.dropPastEvents` compares dates only, so a night the
  venue lists as `31/07 23:00` — which actually runs until ~06:00 the next morning — disappears from the app at 00:00, hours before it ends. It hits every
  late-opening club (OHM, Berghain, Tresor, Renate, …), and OHM feels it hardest because its whole horizon is one to three nights. Needs a cutoff that accounts
  for the start time (e.g. keep an event until `eventDate + 1 day 06:00` when it starts after ~22:00) rather than a per-importer workaround.
- [ ] **A show cannot play twice in one day.** `ScrapedEvent.toEventEntity` builds the stored slug from date + venue slug + title, and `event.slug` is `UNIQUE`,
  so two sessions of the same production on the same date collide on insert — a duplicate-key error that fails the *whole* import, not just that row. Velomax
  hits this (Disney On Ice plays three sessions on one day, Berlin Tattoo two), and its importer works around it by collapsing same-day sessions to the
  earliest; Uber Arena hits it too and simply loses the second session of a double bill (3 of 88 at capture — Feuerwerk der Turnkunst, CAVALLUNA twice), as does
  the Uber Eats Music Hall on the same platform (the 23 December Nutcracker matinee and evening, 1 of 66); Theater im Delphi is the worst hit, losing 4 of 24 to
  matinee/evening pairs; Tempodrom loses four a year to the same shape. Bar jeder Vernunft and Heimathafen sidestep it because their `sourceId`s carry a time.
  Fix at the boundary — include the start time in the event slug when one is known — rather than per importer.
- [ ] **Derive the lineup after the event type is final, not before.** `ScrapedEvent.toEventEntity` promotes a `CONCERT`/`OTHER` title to `FESTIVAL`
  (`isFestivalTitle`), but every scraper has already built its artists from its *own* type inference, so a festival title still mints headliners — `ELLE & L's
  Festival` → `Elle` (Columbia Theater), plus the same shape at Clash and Gretchen. Fix once at the boundary (drop the artists when the resolved type is
  `FESTIVAL`/`PARTY`) rather than per importer.
- [ ] **DJ lineup entries keep their performance-format suffix.** `C3D-E (live)` and `Avangelic (DJ-Set)` are stored verbatim from a lineup list, so they
  resolve to different artist rows than the plain name. `stripArtistSuffix` already handles exactly this tail but is only applied to headliners derived from a
  *title*
  ([`headlinersFromTitle`](events-importer/src/main/kotlin/de/norm/events/scraper/ArtistNameMapping.kt)), never to lineups — consistently across AMT, ÆDEN,
  Renate, Duncker and OHM. Applying it to lineups too is a one-line change per scraper but a cross-cutting data change: it needs a full re-seed and a decision
  on whether the "(live)" distinction is worth preserving elsewhere first (the model has no `LIVE` `ArtistRole`).
- [ ] **A concert-series name appended to a title with an en dash stays on the act.** silent green bills three autumn shows as `Current 93 – Sonic Morgue`,
  `Current 93 – Sonic Morgue – Zusatzshow` and `Anja Huwe / Xmal Deutschland – Sonic Morgue`: `Sonic Morgue` is the series and `Zusatzshow` marks the extra
  date, but `splitHeadlinerTitle` cuts only on `/`, `+` and conjunctions, and `stripArtistSuffix` recognises a ` - ` tail only when it names a tour, a year or a
  release — so both tails are stored as part of the performer and will never resolve to the plain `Current 93` / `Xmal Deutschland` imported from another house.
  Neither an en-dash split nor a blanket tail strip is safe on its own (an act may legitimately carry either), so this needs the series names themselves — the
  same curated-vocabulary question as `NON_ARTIST_NAMES` below. Morphine Raum bills its whole programme this way and shows the shape at its worst:
  `Raphael Rogiński – Qırım` and `Alister Spence – Within Without` fuse the album onto the act (3 of 11 events), and where the tail is a *member list*
  the conjunction split then fires inside it — `PICI - Clémence Manachère & Polina Pohozha` becomes `Pici - Clémence Manachère` plus `Polina Pohozha`, so the
  first artist row is neither the duo nor either member.
- [ ] **A venue's seating information has nowhere to go.** Kulturhaus Peter Edel badges every one of its 39 events with two facts a ticket buyer decides on —
  whether the room is seated (`Bestuhlt` / `Teilbestuhlt` / `Unbestuhlt`) and whether a seat is guaranteed (`Freie Platzwahl` / `Keine Sitzplatzgarantie` /
  `Mit Sitzplatzreservierung`) — and `PeterEdelOverviewPageScraper` drops both because `Event` has no field for them. It is not a Peter-Edel-only signal: Bar
  jeder Vernunft, Admiralspalast, Theater im Delphi and the arena-scale rooms all seat some shows and not others, and the same distinction shows up in their
  prose. Needs a decision on the shape first — a `seating` enum on `event` plus a boolean, or a single free-text column — before any scraper starts filling it.
- [ ] **Promoter display names lose genuine acronyms.** `PromoterNormalizer.deshout` is a bare title-caser, without the `ACRONYMS` / short-initialism guards
  `ArtistNormalizer` already has, so `TV Noir` → `Tv Noir` and `Bossa FM` → `Bossa Fm`. Share one de-shout between the two normalizers. Same change should fold
  Zitadelle's `tip Berlin` / `Tip` onto one spelling via `NAME_CORRECTIONS`. Display-only — slugs are case-insensitive and unaffected — but existing rows keep
  their casing until re-created. The two steps compound where the descriptor strip runs first: Gärten der Welt's `HB Music` loses `Music` and is then de-shouted
  to the unreadable `Hb`, which no longer names anything.
- [ ] **A `feat.` co-bill is stored as one artist.** `splitHeadlinerTitle` cuts a title on `/`, `+` and conjunctions, and `ROLE_LABEL_PREFIX` recognises
  `feat.` only where it *opens* a segment — so Gärten der Welt's `Stereoact: Ich liebe das Leben Party 2027 feat. Lena Marie Engel` becomes a single
  63-character
  "artist" instead of `Stereoact` plus a guest. The marker is already spelled out in `ROLE_LABEL_PREFIX`; splitting on it mid-title (guest → `SUPPORT`) is the
  fix. Cross-cutting, so it needs a `--full` re-seed and a diff.
- [ ] **An event name minted as a headliner because the venue typed the night `CONCERT`.** `buildArtistsForEventType` trusts a `CONCERT` category to mean the
  title names the act, but a venue that has no better bucket files non-musical shows there too: Gärten der Welt labels `Drone Art Show: Harry Potter` and
  `Taschenlampenweihnachtskonzert` "Konzerte", and both become artist rows. `isNonArtistName` already rejects the festival family; catching these needs the same
  curated vocabulary the `NON_ARTIST_NAMES` item below calls for — a format-word denylist (`… show`, `…konzert` with no person in the title) is the shape, but
  it must not swallow an act genuinely named that way.
- [ ] **Huxleys' genre and promoter are stored de-slugified.** Both are read from WordPress taxonomy slugs on the `article` element, so a stylised genre loses
  its punctuation (`kpop` → `Kpop`, not `K-Pop`) and a legal form comes back title-cased word by word (`Concert Concept Veranstaltungs Gmbh`). Needs a
  corrections map for the known slugs, in the same place as the promoter fix above.
- [ ] **Arcanoa's recurring open stage becomes two artists and two slugs.** The venue hand-types its Monday night both `ARCANOA-Open Stage` and
  `ARCANOA- Open Stage`; only the second has a dash the parser pads, so the two normalize differently. Collapse the whitespace around the dash before
  normalizing.
- [ ] **gART.n drops the guests named in a `<sup>` cast line.** `GartnOverviewPageScraper.billingLines` discards a lineup line built only from a `<sup>`,
  because such a line annotates the line above it rather than billing an act — but the venue uses it to name that slot's cast (`Live Podcast "Heisse Platten"` /
  `mit Judith van Waterkant und Ruede Hagelstein`), so Ruede Hagelstein is stored nowhere. Reading a `mit …` / `w/ …`
  annotation as a cast line and splitting it via `splitSegmentOnConjunctions` would recover it; the split needs the conjunction guardrails, since an act name
  may legitimately contain `und`.
- [ ] **The screening keyword misses German compounds.** `SCREENING_TITLE_WORD_PATTERN` (`EventTypeMapping.kt`) anchors on `\bkino\b` to protect real act names
  ("Alkinoos Ioannidis"), so Kater's monthly `Nomadenkino` film night is typed `PARTY` instead of `SCREENING`. A suffix-anchored match (`\w+kino\b`) keeping the
  act-name guard would catch the compounds; cross-cutting, so it needs a re-seed and a diff.
- [ ] **The football keywords cannot tell a match screening from a football talk.** `SCREENING_TITLE_KEYWORDS` (`EventTypeMapping.kt`) carries `fussball` and
  `11freunde` for Lido's and Astra's public viewings, so Colosseum's `Der Fussball mein Leben & Ich` — an on-stage evening with Thomas Schaaf, ticketed through
  the 11Freunde shop — is typed `SCREENING` rather than left `OTHER`. The signal that separates the two is the screening verb, not the sport: a public viewing
  says *Public Viewing* / *Live-Screening* / *Übertragung* or names a fixture (`EM Italien - Albanien`), while a talk names people. Narrowing the sport words to
  those contexts is cross-cutting — it touches Lido and Astra — so it needs a re-seed and a diff.
- [ ] **Astra's dateless featured teaser is dropped whenever its detail fetch fails** — `11FREUNDE WM-QUARTIER` drops on every run. The teaser carries no date
  of its own, so one failed fetch loses the event entirely; a retry, or reusing the last-known date for that `sourceId`, would keep it. Lido runs on the same
  Kulturhäuser platform and has the same teaser, so fix it once for both.
- [ ] **Heimathafen stores no genre only because the taxonomy is unresolved.** The venue *does* tag its events, but the REST payload carries term **ids** and
  the `class_list` slugs are lossy (`rb` for R&B). Resolving the 560-term `events_tag` vocabulary once per import and caching it — plus a stop-list, since the
  vocabulary mixes real genres with formats and access notes (Konzert, Premiere, Gebärdensprache) — is what unblocks the genre field for all 95 events.
- [ ] **A country/origin tag stays attached to the act name.** `Ipkiss (NL)`, `ANEMONE (NL)`, `NIGHT NAIL (Dark Wave US/DE)` and `Apichat Pakwan (Thailand-
  Live)` are stored verbatim, so they never resolve to the bare spelling of the same act imported from another venue (7 artist rows affected today, 5 of them
  VOID Club's). `arkaoda` already strips a trailing all-caps code group locally — lift that into the shared `stripArtistSuffix`, extend it to spelled-out
  countries, and keep the existing carve-out for a parenthesised *alias* (`Sickboyrari (Black Kray)`). Cross-cutting, so it needs a `--full` re-seed and a diff.
- [ ] **Only concerts get an artist, so a solo bill outside `CONCERT` loses its performer.** `buildArtistsForEventType` mints a headliner from the title for a
  `CONCERT` and stays silent otherwise. That is right for a production title (`DIE KLIMA-MONOLOGE`) but wrong for the `"<performer> – <show>"` idiom every
  variety and comedy house uses — Admiralspalast stores an artist for 66 of 201 events and loses `Bülent Ceylan` from *Bülent Ceylan – Diktatürk*; Heimathafen
  stores one for 30 of 95. Cosmic Comedy already proves the split works (it derives the act from exactly that idiom for its `Comedy Special` nights), so the
  rule can be shared rather than reinvented per venue.
- [ ] **There is no event-level room, so a multi-room venue loses which space a show plays in.** `ScrapedArtist.stage` is the only home for it, so the room
  survives only where there are acts to hang it on: VOID Club drops `VOID CLUB` / `VOID HALL` on its two `TBA` nights, Heimathafen parses `(Saal)` /
  `(Studio)` out of its doors-time note and discards it, and silent green keeps `Kuppelhalle` / `Betonhalle` / `Atelier 2+3` only on its 33 concerts — the 54
  exhibitions, talks and screenings it publishes a hall for have no lineup to carry one. Needs an event-level field plus a decision on how it relates to the
  per-artist stage.
- [ ] **There is no event end time.** Kater publishes a full `Sa. 01.08 22:00 — So. 02.08 10:00` span and Heideglühen a "bis Sonntag, 6 Uhr" tail; both are kept
  as prose because the model stores only a start. The same missing field is why the late-night drop above needs a start-time heuristic instead of simply asking
  whether the event has ended.
- [ ] **Eschschloraque's doors/start split is published in prose and dropped.** The venue's date field carries one time, which the importer stores as the
  start — but where a night actually has two, only the description says so: the Buletten Bingo openair writes `Einlass: 19:00` / `Beginn: 19:30` (and again as
  `Doors:` / `Starts:` in its English half), and the MissVergnügen anniversary `DJs ab 21 Uhr, Showtime ab 22 Uhr`. So the stored 19:00 is really the doors time
  and the 19:30 start is lost, on 2 of 6 events at capture. Reading the labelled pair out of the description and letting `orderDoorsBeforeStart` place them is
  the fix; it needs a decision first on whether prose may override the venue's own structured date field, and the unlabelled `ab … Uhr` / `Showtime` phrasing
  needs its own pattern.

**Data quality — normalize, validate, enrich:**

Strategy & sequencing: [docs/DATA_QUALITY_STRATEGY.md](docs/DATA_QUALITY_STRATEGY.md)
(Measure → Prevent → Fix → Systematize).

- [ ] **(Pillar 1 — Measure)** Data-quality report: `GET /api/admin/data-quality` + scheduled summary log + Micrometer gauges — per-source counts of artist-less
  concerts,
  `OTHER`-typed events, and missing genre/promoter/price/start-time. Plus a `/worklist`
  endpoint (offending events per metric) so stewards fix via the existing Event API — no bespoke frontend yet. Persist daily metric snapshots
  (`data_quality_snapshot`) so trends are chartable in an external BI tool (see the *Dashboard* item under Operations).
  Plan: [docs/DATA_QUALITY_PILLAR_1_PLAN.md](docs/DATA_QUALITY_PILLAR_1_PLAN.md).
- [ ] **(Pillar 2 — Prevent)** Golden fixture tests from real scraped HTML for all four normalizers, plus a boundary validation gate that flags obviously-bad
  output (empty artist after stripping, artist == non-artist pattern, genre == event title)
  into the curation queue instead of persisting it silently.
- [x] **(Pillar 3 — Fix)** Title-as-headliner extraction for venues without a `Support:` signal (Privatclub, Cassiopeia, Badehaus) — recovers the ~40% of
  concerts previously stored with no artist. Done via `buildArtistsForEventType` / `headlinersFromTitle`; Cassiopeia's ambiguous titles are guarded by a widened
  `isNonArtistName` festival filter. **Still TODO: a one-off backfill re-scrape** — existing rows keep no artist until re-imported.
- [ ] **(Pillar 4 — Systematize)** AI-assisted data quality in the importer (one capability, several uses): detect/extract artist names from titles, validate
  event types, enrich missing fields (genres, event types), and fix bad values (artist names, promoter names, …) — cross-checking the event source page and the
  wider web where useful. Runs *after* the deterministic normalizers, human-in-the-loop via the admin review UI. **Needs an ADR — *AI-Assisted Data Quality***
  — new external dependency, cost/latency, non-deterministic output. (Unnumbered on purpose: this ADR has been pre-assigned a number twice and lost it twice, to
  the cloud-platform and localisation decisions. It gets one when it is written.)
- [ ] **(Decision — ADR candidate)** Curated-vocabulary storage: code vs. data. Move the denylists / synonym maps / corrections (`NON_ARTIST_NAMES`,
  `NAME_CORRECTIONS`, genre synonyms, `ACRONYMS`) from hardcoded Kotlin to steward-editable DB tables so fixes land without a redeploy — vs. keeping them as
  tested code fixed via PR. Spike + ADR before Pillar 4's human-in-the-loop needs live editing; blocks nothing in Pillars 1–3. (Strategy §6.)
- [ ] **Resolve a venue per event, so promoter sources become importable.** An event's venue comes from its `event_source` row
  (`EventUpsertService.upsertAndCleanup(events, venueId, …)`), one venue for the whole source — so a promoter that books across houses cannot be imported at
  all. Puschen, Trinity Music and Landstreicher Booking are deferred on exactly this (see EVENT_DATA_SOURCES.md § Blocked); their listings are clean and name
  the venue per event. Needs a venue resolved per event (matched by name against existing venues, auto-created otherwise) **and** de-duplication against the
  venue-level sources — ~30 of Puschen's 35 shows are at venues already imported. ADR-sized; also the general answer to a show that moves between houses
  (Huxleys' relocations).
- [ ] **List promoters in their own table** in [docs/EVENT_DATA_SOURCES.md](docs/EVENT_DATA_SOURCES.md), separate from the venue rows — they are a different
  kind of source (cross-venue listings, no own house) and mixing them into the venue tables hides that. Same place to record the duplicate-events question: a
  promoter's listing largely repeats the shows the venues already publish, so importing one needs de-duplication against the venue-level sources (see the
  per-event venue resolution item above).
- [ ] Enrich venues: type (club/bar/concert hall), description, image/photo, genres, event types
- [ ] Enrich promoters: description, image, and corrected display names
- [ ] Check & fix venue districts, addresses, and geo-coordinates

**Importer coverage & parsing:**

- [ ] Scrape events in multiple languages (English + German) where the source offers it (e.g. Berghain) — first audit which event sources are actually
  multi-language
- [ ] Update importers to scrape/parse **all** available events via the site's navigation/pagination (not just the first page). migas is the cheapest concrete
  case and the one that needs new plumbing: its "Load More" button POSTs `action=load_events&paged=<n>` to `wp-admin/admin-ajax.php` and returns the same markup
  fragment (a GET ignores `paged`), while the button's `data-pages` states the total page count up front — so the loop is bounded and terminating, but
  `HtmlFetcher` is GET-only and would need a form-POST fetch first. 10 of 12 upcoming events at capture.
- [ ] Review events typed `OTHER` — should we add new values to the event-type enum? Four formats have no type of their own today and are filed under a
  neighbour: comedy (Cosmic Comedy's whole 57-event programme reads as `SHOW`), dance and theatre (Theater im Delphi's `Tanz`/`Theater`, the AEG venues'
  ballet), lectures and panels (Urania's, filed `READING`), and sport (not imported at all — see the coverage question below).
- [ ] Expand Elfsight **monthly** recurrence rules (`repeatPeriod: nthDayInMonth`, `repeatFrequency: monthly`). Humboldthain expands the weekly rules its
  resident night uses; Neue Zukunft's recurring entries are monthly and are still imported once, at their start date only (4 of 44 entries). Fixing it also
  needs `NeueZukunftApiScraper`'s `sourceId` to carry the occurrence date (Humboldthain already does), which re-mints every existing Neue Zukunft event — so do
  it as one change, not two.
- [ ] **Two shared title-parsing rules are too literal, and each currently needs a per-importer workaround.** Both surfaced at LARK, which works around them
  locally; fixing them centrally changes classification for every venue, so it needs a `--full` re-seed and a diff, not a drive-by edit.
    - `PARTY_TITLE_KEYWORDS` matches a bare `club` as a substring, so a tour named "… CLUB TOUR" is typed `PARTY` — and a party title mints no artists, so the
      headliner is lost too. Word-anchor it (as `\brave\b` and `\bkino\b` already are), or drop the bare entry and keep `club night` / `clubnight`.
      **Note which of the two:** the worst case found so far is a *band whose name ends in the word* — Columbiahalle bills `Two Door Cinema Club`, which is
      typed `PARTY` and consequently stores no artist at all. Word-anchoring does not help there (nor for `CLUB TOUR`); only dropping the bare entry does. It
      is the single recoverable lineup the `PARTY`/`FESTIVAL` investigation above turned up across 3166 events, which is the measure of how much this one
      keyword costs.
    - `ARTIST_SUFFIX_PATTERN` and `stripShoutedTourTail` only recognise the **ASCII hyphen** as the act/tour boundary, so an en- or em-dash tour tail ("Greg
      Mendez – BEAUTY LAND TOUR") survives into the artist name. Accept `[-–—]` in both.
    - `w/` is not treated as a co-bill separator, and a comma suppresses conjunction splitting, so LARK's `FEUCHT w/ BELLA, Agua con gas & SENERGI` is stored as
      one long "artist". Add `w/` to the splitter.
- [ ] **Cover venues that will never have an automatic import** — no website at all, or a programme published only via Instagram / Facebook / Resident Advisor.
  Needs three things: a recorded list of those venues (in EVENT_DATA_SOURCES.md, with a link to wherever their programme *is* visible), a low-friction way to
  enter their events by hand (see the admin frontend items below), and a reminder mechanism so checking them doesn't get forgotten.

**Admin tooling & maintenance:**

- [ ] **Admin frontend** — one place to operate the importers and curate the data. Start with Importer API endpoints + an admin IntelliJ HTTP Client collection;
  the UI can follow. (`EventSourceController` already exposes per-source status + retry — build on it.)
    - [ ] Pick an admin dashboard template/kit rather than building the shell from scratch
    - [ ] **Imports status & control** — see per-source import states, especially **failed** imports, and trigger an import on demand
    - [ ] **Import configuration** — manage sources and their schedules
    - [ ] **Data-quality overview** — per-metric and per-source view plus a trend chart, fed by the Pillar 1 endpoint + `data_quality_snapshot` snapshots. Two
      questions to answer first: which fields actually matter for the site (probably titles and everything used for filtering), and which sources have the worst
      quality / most missing important fields.
    - [ ] **Data review & fixing** — sort/filter events by missing fields; edit artist/promoter names, event types, genres, …
    - [ ] **AI-assisted checking & fixing** — cross-check stored data against the event's source page and propose fixes (Spring AI); human-in-the-loop review.
      Open question: local LLM vs. an API/subscription. Same capability as Pillar 4 above — decide it once, in ADR-013.
    - [ ] **Manual event entry** — a fast form for venues with no importer, plus reminders/nudges so those venues actually get checked (see the
      "venues that will never have an automatic import" item above)
- [ ] Improve importer Swagger UI (match the BFF)
- [ ] Housekeeping: policy for when to delete old events from the DB

**More importers:**

- [ ] Implement more importers/scrapers (see EVENT_DATA_SOURCES.md)
    - [ ] Strategy to implement the remaining importers fast — but still clean, robust, fully tested
    - [ ] Standardize/simplify existing importers + scrapers where it helps
    - [ ] Find venues we may have missed — cross-check [theclubmap.com](https://www.theclubmap.com/music-style/), Resident Advisor, and the web
    - [ ] Evaluate bars that host DJs / live music / other events ("music bars", "event bars") as sources — start by adding **Minimal Bar** to
      EVENT_DATA_SOURCES.md, then search Berlin for comparable ones
    - [ ] Check promoters already in the DB and scan their sites for events
    - [ ] Cover events at special/one-off locations (e.g. Durchlüften Festival @ Humboldtforum, Tempelhofer Feld, Olympiastadion)
    - [ ] Radio-station event listings (RadioEins, FluxFM, StarFM, …)
    - [ ] Consider importing from Resident Advisor — confirm legality first (probably not allowed)

**Coverage scope — decided 2026-08-08.** Full reasoning and cost in [docs/EVENT_SCOPE.md §5](docs/EVENT_SCOPE.md). **None of these may be reopened in an
importer PR.**

- [x] **Decided — comedy clubs: yes.** Cosmic Comedy is already imported, so this is more venues in a category that exists (Comedy Café Berlin, Quatsch Comedy
  Club, …). No model change, no ADR. → *actionable work below*
- [x] **Decided — theatres: yes.** Theater im Delphi, Heimathafen and Bar jeder Vernunft are already imported; the remaining houses (Volksbühne, Schaubühne,
  Berliner Ensemble, …) are coverage, not a new category. → *actionable work below*
- [x] **Decided — sport: no.** Different venues, different audience, past the point where this is a music app. The exclusions already in
  `AegOverviewPageScraper.isSport` and Velomax's type map **are** this decision's implementation — the Velomax halls drop 32 of 85 listed entries and Uber Arena
  40 of 128 rather than burying their concerts under `OTHER`. Reopening this means reopening that code, not just a doc.
- [ ] **Deferred — classical concerts / orchestras (wanted, blocked on the artist model).** Berliner Symphoniker, RBB Sendesaal, Konzerthaus, Philharmonie. Fits
  the existing `CONCERT` type, but the data shape differs — orchestra/ensemble + conductor + soloists rather than headliner + support — so **`ArtistRole` and
  the genre vocabulary must be extended first**, with an ADR. Do **not** import an orchestral house by flattening it into headliner-plus-support; the data would
  be wrong in a way that is expensive to unpick. RBB Sendesaal's scraping is already solved (server-rendered ROC calendar, `.ConcertListItem-location` is the
  only filter needed) and it stays in Blocked purely on this.
- [ ] **Deferred — exhibitions as first-class runs (blocked on the time model).** A run of weeks/months rather than a start time on one evening needs a date
  range in the schema plus a display decision. Note the related honesty gap: `EXHIBITION` today means an *opening* (a `vernissage` has a start time), not a
  run — see EVENT_SCOPE.md §2.

**Actionable now that comedy and theatres are settled:**

- [ ] Move the comedy and theatre venues currently sitting in [Blocked](docs/EVENT_DATA_SOURCES.md) on the scope question into Ready, and scaffold them like any
  other source — prioritised by programme richness as usual
- [x] **Investigated — `PARTY` and `FESTIVAL` discard artists on purpose, and the measurement says keep it (2026-08-08).** `buildArtistsForEventType`
  early-returns `emptyList()` for both. Measured against the whole seeded database (3166 events): 335 events are typed `PARTY`/`FESTIVAL` with no lineup, but
  only ~96 of them (56 distinct titles) reach this function at all — the rest come from scrapers that never derive an artist from a title, so the rule is not
  their cause. A party is not artist-less by nature either: **302 of the 611 parties do have a lineup**, read from a billing list rather than from the title.
  Of the 56 titles, exactly **one** hides a recoverable act, and it is a misclassification, not a lineup bug (see the `club` keyword item below). Against that,
  removing the guard would mint ~95 fictional artists, several of them real: Frannz's `Friday I'm in Love – A Tribute to Post-Punk · Dark 80s + Nick Cave`
  splits on the `+` and stores **Nick Cave** as a performer, on a row that resolves by slug onto the real artist. The trade is documented with the numbers in
  `buildArtistsForEventType`'s KDoc and pinned by tests in `ArtistNameMappingTest`; **the rule stays unconditional.**
    - **Correction to the `CLUB_NIGHT` context.** Retiring `CLUB_NIGHT` into `PARTY` was rejected on 2026-08-08 because it "would silently delete the artist
      link from all 8 migas events". That reason does not hold: `MigasOverviewPageScraper` builds its lineup by calling `headlinersFromTitle` directly and never
      calls `buildArtistsForEventType`, and `ArtistNameMapping.kt:923` is the only place in the importer where an event type suppresses a lineup — so a migas
      night typed `PARTY` would keep its artists. Whether to merge the two types is therefore back to a pure **semantics** question (`CLUB_NIGHT` = *a DJ set
      where the booked act is the draw*, [docs/EVENT_SCOPE.md §2](docs/EVENT_SCOPE.md), and `PARTY` misdescribes migas' seated listening bar), not a data-loss
      one. Not reopened here — it needs a decision, not a fix
- [ ] **Recover the act from a `"<night> curated by / invites / hosted by <act>"` title.** The one genuinely recoverable seam the investigation above found, and
  a different one from the rule it went looking at: `FOREVER 25 curated by Mila Stern & Esther Silex` and `FOREVER 25 curated by Enorm in Form` (Kater),
  `Sesh Clara Cuve invites` (Club OST), `Moritz Biebl Invites` (AMT), `Tresor New Faces hosted by Secret Keywords` (Tresor),
  `Antina's Spookhouse by Antina Christ` (Renate) all name a booked DJ that is stored nowhere. Roughly 8 events at capture, so small — but unlike the party
  titles around them these are unambiguous, and the marker words are a closed set. **None of those five venues route through `buildArtistsForEventType`**, so
  this is a shared title-parsing rule (`ArtistNameMapping`) plus a call from each scraper, not a change to the `PARTY` guard. Needs the usual `--full` re-seed
  and diff

## Operations & Hardening

- [ ] Containerise `events-frontend` (multi-stage Node build → nginx serving `dist/`): history-mode `try_files` fallback, immutable caching for `/assets/*` +
  `no-cache` for `index.html`, and a relative `/api` base URL so one image serves every stage (see [ADR-012](docs/adr/ADR-012_CLOUD_PLATFORM.md)). Same-origin
  is what keeps CORS out of the picture and makes session cookies first-party for the planned auth. **Only if a PaaS fallback is taken** does this invert — a
  per-GB-RAM platform bills €25–30/month for an nginx container, so there the SPA goes to a static host/CDN and the BFF gets an explicit CORS allowlist
- [ ] Exercise the Helm chart / container images locally before deploying (k3d or kind; LocalStack for cloud services?) — on the ADR-012 recommendation this
  local k3d work *is* the production deployment path, not a rehearsal for a different target
- [ ] Maintenance mode — a downtime page for deploys and outages (frontend + BFF behaviour)
- [x] Performance tests for the BFF read API — [k6](https://k6.io) scripts in [`perf/`](perf) (`smoke.js` · `load.js` · `spike.js`), run locally on demand
- [ ] **Re-derive `perf/load.js`'s session weights from real traffic.** They are currently 55% events list / 25% calendar / 20% venues — a considered guess,
  labelled as one in the script. A load test's p95 only describes traffic that could actually occur, so a wrong mix produces a confident number about a session
  nobody has. Needs analytics or access logs, so it is blocked on a deployment
- [ ] **Automate the k6 runs**, once there is somewhere worth pointing them. Deliberately deferred — a GitHub runner is too noisy to baseline against, and a CI
  run against an empty database would only re-assert what the Testcontainers tests already cover with real data. Two follow-ups, in order:
    1. Point `perf/smoke.js` and `perf/load.js` at **staging** from a scheduled workflow, once staging exists (blocked on ADR-012)
    2. Store the results over time rather than gating on a threshold — a p95 that has drifted 40% over two months is the signal; a single red build is not.
       Prometheus remote-write into the monitoring stack above is the natural home. See [perf/README.md](perf/README.md) §Why there is no CI workflow (yet)
- [ ] Logging: always attach context (event id, artist id, …)
- [ ] Checkov scan (if it makes sense)
- [ ] Infra/tooling update checker beyond Dependabot (Renovate?)
- [x] **`notices.json` was platform-dependent.** `license-checker --production` walks the *installed* tree, so a regeneration on macOS wrote
  `@esbuild/darwin-arm64` and one on Linux CI would have written `@esbuild/linux-x64` — breaking the generator's own promise that unchanged dependencies produce
  an identical file. Platform-specific optional binaries are now dropped by `PLATFORM_SPECIFIC` in `events-frontend/scripts/generate-notices.mjs`: they are
  build-time binaries for one CPU architecture, never shipped to a browser, so never distributed and never needing attribution
- [ ] **Nothing checks that `notices.json` is current**, which is how it came to be missing `vue-i18n` — a direct production dependency, shipped in the bundle,
  absent from the attribution page for as long as localisation has been in. Regenerating it is a manual step in the `/update-dependencies` skill and easy to
  skip. **Now unblocked** by the fix above: a check can regenerate and fail on a non-empty diff without failing for the wrong reason. The one wrinkle left is
  that the file merges *both* ecosystems, so the check needs Gradle (`generateLicenseReport`) as well as npm — it belongs in the backend workflow, or its own
- [ ] Review useful security workflows → https://github.com/enorm-labs/event-checker/actions/new?category=security
- [ ] Dashboard for analysing the data (Superset, Kibana, Grafana, …?) — also the intended surface for the **data-quality metrics/trends** (Pillar 1 exposes
  them via a
  `data_quality_snapshot` table for SQL-based BI and Micrometer/Prometheus for Grafana; see [docs/DATA_QUALITY_STRATEGY.md](docs/DATA_QUALITY_STRATEGY.md) §4)
- [ ] Enable agentic workflows (continuous refactoring/docs) → https://github.github.com/gh-aw/
- [ ] **Opt in to Vite's `configLoader: 'native'`** — the config chain already carries the explicit `.ts` imports the native loader needs
  (`events-frontend/AGENTS.md` §Config-loader imports), and `vite build --configLoader native` was verified working on Node 24. The only blocker is the engine
  floor: it fails on Node 22, which has no unflagged type-stripping. **Unblocked once `engines.node` is `>=24`.** Not urgent — the current `bundle` loader
  works — but doing it deliberately beats being moved by a Vite major

## Legal / Compliance (before going live)

**Built and documented in [docs/LEGAL.md](docs/LEGAL.md)** — imprint, privacy notice, FOSS attributions, accessibility target and repo links all shipped, in
both languages. That document is now the record of what exists and what the rules are; the items that used to sit here are gone rather than ticked, because a
backlog of finished work is noise.

What is genuinely left splits in two: **verification that needs a real address, a real deployment or a qualified reader**, tracked in
[§At go-live & after](#-at-go-live--after-needs-a-live-deployment) and summarised in [LEGAL.md §14](docs/LEGAL.md) — and the two open questions below, which are
about the *data* rather than the pages.

- [ ] Confirm legality of scraping events and displaying them
- [ ] Clarify copyright/licensing of event **descriptions** and **images** per source — are we allowed to store/display them? Track a copyright/license status
  per event source (drives the description/image display decision under Frontend & BFF)

## 🚀 At go-live & after (needs a live deployment)

Work that is **not blocked on effort but on a live origin** — a real address, a real deployment, or a public URL something else can fetch. Kept separate so it
does not sit in the main backlog looking like neglected work, and so nothing here is quietly skipped on launch day.

Ordered by when it becomes possible.

### Blocking the first deploy

- [ ] **Read every page, in both languages, as a reader rather than as its author.** The legal pages and About especially — they are the longest prose on the
  site, they were written fastest, and each exists as two independent documents that no test can compare for *meaning*. Look for German that reads as translated
  English, for claims that are no longer true, and for the `du` register slipping into `Sie`
- [ ] **Review `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`, `README.md`, `SECURITY.md` and the issue templates.** These were written alongside the site and have had
  no second pass. **The concrete defect to fix: `hello@event-junkie.de` and `security@event-junkie.de` do not exist** — the domain is unregistered, so every
  published reporting route in those files is a dead address, including the Code of Conduct's enforcement contact and the security-disclosure address
- [ ] **Review and improve the German translations** (`events-frontend/src/i18n/messages/de/`). The key-parity test proves every key exists and is not a copy of
  the English one; it cannot tell you a translation is *good*. Worth one deliberate pass by someone reading only the German, not comparing it to the English
- [ ] **Replace the placeholder postal address** — [ADR-012](docs/adr/ADR-012_CLOUD_PLATFORM.md) covers the domain; the address comes from a rented Postflex
  *ladungsfähige Anschrift*. Update `events-frontend/src/lib/legal.ts`, `CODE_OF_CONDUCT.md` and `SECURITY.md`, then set `CONTACT_DETAILS_ARE_PROVISIONAL =
  false` **in the same commit** — a unit test fails if the flag and the placeholder ever disagree, which is what stops a false address going live quietly
- [ ] **Register the role mailboxes** `hello@event-junkie.de` and `security@event-junkie.de`, and check that mail actually arrives. They are already published
  in the imprint, the privacy notice, `SECURITY.md` and `CODE_OF_CONDUCT.md`, so until the domain exists **every reporting route the project advertises is a
  dead address** — including the Code of Conduct's enforcement contact and the security-disclosure address. Distinct from the read-through item above: that one
  is proofreading, this one is infrastructure
- [ ] **Conclude the Art. 28 contracts** — Hetzner's AVV (offered in their console) and Cloudflare's DPA — and name the transfer mechanism actually in force in
  the privacy notice, replacing the placeholder sentence. *A notice naming processors without a DPA in place is worse than one naming none* (LEGAL.md §14)
- [ ] **Give backup retention its own line in the privacy notice.** It is a separate period from log retention, and the two interact: if logs on disk are
  captured by `wal-g` snapshots, the effective log retention is the **backup** window, not the rotation one. Check this against the final design rather than
  assuming it — assuming it is how a notice ends up stating a period the system does not honour
- [ ] **Settle the logging decisions** (LEGAL.md §7.5.1): whether Traefik and the nginx container log real client IPs, whether they are truncated, the retention
  period, and where retention is actually enforced. The privacy notice currently states an *intended* seven days — it must state the configured one
- [ ] **Re-check the privacy notice against what actually runs**, then set `INFRASTRUCTURE_IS_PROPOSED = false` and bump `LAST_REVIEWED`. Name the real
  processors, and the transfer mechanism in force for Cloudflare rather than the placeholder sentence
- [ ] **Legal review of the German privacy notice** — plus the DSGVO-generator cross-check (§7.8) as a second pair of eyes. The drafts are careful and
  test-covered; neither makes them *reviewed*, and this is the one item on this list no amount of engineering substitutes for

### Per environment, the moment a non-production stage exists

- [ ] **Override `robots.txt` and `sitemap.xml` outside production.** The build emits an allow-all `robots.txt` and a sitemap naming the production origin, so
  any staging or preview environment serving that build invites indexing — and points crawlers at production while doing it. This is a deployment concern by
  design, so it has to be solved in the deployment

### Only verifiable once there is a public URL

- [ ] **Google Rich Results Test** on a real event page. The `schema.org` output is verified against Google's documented requirements and by unit and e2e
  tests — never against Google. This is the one place the structured data could still be wrong in a way nothing in CI catches
- [ ] **Set the site up in [Google Search Console](https://search.google.com/search-console/)** — verify ownership of `event-junkie.de` (DNS TXT record via
  Cloudflare is the least fragile method), add **both** locale trees, and submit `sitemap.xml`. Nothing below can be checked until this exists, and it is the
  only free source of truth for how Google actually sees the site
- [ ] **Confirm the sitemap and `hreflang` are accepted** — Search Console reports parse errors and unreciprocated `hreflang` pairs explicitly. Also confirm
  `robots.txt` and `sitemap.xml` are genuinely served from the origin, not just present in `dist/`
- [ ] **Watch indexing, especially detail pages.** This is the named trigger in [ADR-014](docs/adr/ADR-014_RENDERING_STRATEGY.md) §Decision 4 for reopening full
  SSR: **detail pages indexed late or not at all is the evidence**, and anything short of that is anticipation. Worth a deliberate check a few weeks after
  launch rather than a glance on day one — a brand-new site with no inbound links has low crawl priority, so early slowness is expected and proves nothing. Use
  the URL Inspection tool on one event page to see the rendered HTML Google actually holds
- [ ] **Check a real link preview** in Slack, WhatsApp and iMessage. The per-page tags exist in the DOM now, but these scrapers do not run JavaScript, so they
  still read the site-level ones out of the served HTML — the concrete defect ADR-014 exists to fix, and it only closes when the injector lands. Checking a real
  preview is the only way to know it worked
- [ ] **Lighthouse / PageSpeed against the live origin**, not a dev server — caching headers and compression are deployment properties and cannot be measured
  locally

### Once ADR-012 is executed

- [ ] **Build the meta-injection transport** ([ADR-014](docs/adr/ADR-014_RENDERING_STRATEGY.md) §Decision 3): the component that rewrites `<title>`, `og:*`,
  `twitter:*` and `canonical` per route before the response leaves our infrastructure. Leading candidate is a Cloudflare Worker using `HTMLRewriter`; the
  alternative is a small k3s sidecar, which costs more operationally and keeps all processing in Germany. **It must fail open** — a slow or failing BFF has to
  yield the unmodified shell, never an error page. If the Worker is chosen, that is a §7.7 change to raise rather than assume. *(The other half — computing the
  tags — needs no deployment and is tracked under Frontend & BFF.)*

### Deferred deliberately — decided, not forgotten

Not blocking anything. Listed so they are not rediscovered as gaps and "fixed" by someone who does not know they were choices. Mirrors
[LEGAL.md §14](docs/LEGAL.md).

- [ ] **`1.0.0`, and dropping the beta badge — one decision, not two** (LEGAL.md §4.7). The badge says the data may be incomplete or stale; it comes off when
  that stops being true, not when the code feels finished. Bumping the version and removing the badge belong in the same change
- [ ] **An accessibility statement.** Only publishable once conformance has actually been measured end to end — a statement is a *claim*, and `axe` finds
  roughly a third of WCAG issues, so passing the sweep is not evidence of AA (LEGAL.md §12). The natural home is `/legal/accessibility`, which the route
  structure already anticipates
- [ ] **`FUNDING.yml`** — absent on purpose until donations are actually wanted. When they are: `FUNDING.yml` first (zero site impact), and on the site **link
  out, never embed** — an embedded payment widget introduces a processor and a third-party request. Commercial changes also alter the § 5 DDG imprint analysis,
  not just the privacy notice (LEGAL.md §8.4)

## Tooling, AI Agents & Skills

- [ ] Multiple/path-specific instruction files (at least backend + frontend) →
  [docs](https://docs.github.com/en/copilot/how-tos/copilot-on-github/customize-copilot/add-custom-instructions/add-repository-instructions#creating-path-specific-custom-instructions)
- [ ] Create more prompts/skills/agents:
    - [ ] Feature planning + spec creation (interview → spec → plan; see [spec-kit](https://github.github.com/spec-kit/))
    - [ ] Code review agent
    - [ ] Documentation-update agent
    - [ ] Security agent
    - [ ] UI/UX agent
    - [ ] Refactoring / code-quality agent (behavior-preserving)
    - [ ] Architecture-review agent
    - [ ] ADR-authoring prompt
- [ ] Evaluate/steal ideas (don't necessarily install): [awesome-copilot](https://github.com/github/awesome-copilot),
  [superpowers](https://github.com/obra/superpowers), [get-shit-done](https://github.com/gsd-build/get-shit-done)
  — recommendation on record: keep AGENTS.md as the source of truth; add optional prompt files, don't adopt always-on ceremony.
- [ ] Try [Repomix](https://repomix.com/) (+ [GH Actions](https://repomix.com/guide/github-actions))
- [ ] Consider a `BACKLOG.md` context-engineering approach
  ([reference](https://www.codecentric.de/wissens-hub/blog/strukturierte-migration-mit-claude-code-context-engineering-statt-prompt-engineering))
- [ ] Fix `scripts/dev-env.sh diff-snapshot` when the **baseline snapshot is empty** (a fresh database). It uses the `NR == FNR` awk idiom to load the first
  file, but awk never reads a record from an empty file, so the *second* file is loaded as the baseline and every new source is reported `GONE` / "lost events"
  instead of `new`. Guard on `FILENAME == ARGV[1]` (or `ARGIND == 1`) instead.
- Note: IntelliJ Copilot Chat now supports the "Copilot CLI" provider, so global `~/.copilot/skills/` are usable there too.

## Docs, Repo & Templates

- [ ] **To consider:** rename the repo (and internal `event-checker` references) to `event-junkie` — would collapse the public/internal split. Note: this
  **reverses the current BRANDING naming rule**
  (§ "Naming rule"); if pursued, update BRANDING.md accordingly. Scope: repo name, Gradle modules, packages, DB schema, ADRs, docs.
- [ ] Clean up KDoc comments across the codebase — drop boilerplate/irrelevant comments, keep the rest meaningful
- [ ] Generate a Mermaid domain class diagram via Gradle
- [x] Community/repo health files — `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md` (Contributor Covenant 3.0), `SECURITY.md`, `SUPPORT.md`, issue templates and a PR
  template all exist. Example: [gitfolio](https://github.com/github-samples/gitfolio)
- [ ] **Still open from the above:** the contact addresses those files name are **not registered** — `security@event-junkie.de` and the removal-request address
  are both promised as "once the domain is registered", and until then the private GitHub advisory form is the only confidential channel for *three* distinct
  purposes (security reports, name-removal requests, venue opt-outs). Registering the domain unblocks all three; see the go-live review item.
- [ ] Repository best-practices pass (follow GitHub docs)
- [ ] Create a public Roadmap (seed it from the phased roadmap in [docs/VISION_ROADMAP_IDEAS.md](docs/VISION_ROADMAP_IDEAS.md))
- [ ] Create a template repository (Enterprise + private):
    - [ ] `.github/` with workflows, instructions, skills, prompts, agents
    - [ ] README, CONTRIBUTING, LICENSE, etc.
    - [ ] Check for good existing templates first; see also the OTR service template; add scaffolding

---

## 🔵 Someday / Vision

Bigger bets and post-MVP features. Details in [docs/VISION_ROADMAP_IDEAS.md](docs/VISION_ROADMAP_IDEAS.md).

- iCal support; export/import calendar to Google Calendar or file
- Chatbot and/or MCP server to find events and answer questions about events, artists, venues, districts, promoters, genres, etc.
- **Expansion stage 1 — Login / profile:** follow/favourite artists, venues, districts, promoters, genres (…) to filter events and drive notifications — two
  steps, YouTube-style: (1) follow, (2) get notified. Plus favourites (Merkliste), reminders, customizable start page, RSVP ("interested"/"going"),
  recommendations. (→ RBAC / Keycloak)
- **Expansion stage 2 — Social layer:** connect with friends to see which events they're interested in or going to (interest/attendance).
- User/venue-submitted events with review-before-publish (→ RBAC / Keycloak)
- Venue & artist profiles (with links); venues browsable by genre + location
- Rank events by popularity (RSVPs) and by artist popularity
- Integrate Spotify / Deezer / SoundCloud / Resident Advisor (notify when favourite artists play)
- Club map — events nearby
- Public API for third-party apps → API management (subscriptions, keys)
- Expand beyond Berlin to other cities
