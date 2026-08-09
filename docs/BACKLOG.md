<!-- GENERATED FILE — DO NOT EDIT.
     Source of truth: https://github.com/enorm-labs/event-checker/issues
     Regenerate: scripts/generate-backlog-snapshot.sh
     Written by .github/workflows/backlog-snapshot.yml -->

# Backlog

A read-only snapshot of the **146 open issues** in
[the tracker](https://github.com/enorm-labs/event-checker/issues), grouped by milestone.

**This file is for finding work, not for recording it.** Edits here are overwritten. To add,
change or close something, use the tracker — `/new-issue`, `/next-issue`, `/start-issue`, or
`gh` directly.

- **Board** — <https://github.com/orgs/enorm-labs/projects/1> (Status and Priority live there, not
  in labels)
- **Direction and phases** — [VISION_ROADMAP_IDEAS.md](VISION_ROADMAP_IDEAS.md)
- **State column** — `blocked` waits on another issue, `needs-decision` on a choice,
  `needs-deployment` on a live origin. The last of those is not neglected work.

## v0.2 — Deployable — 8 open

| # | Title | Type | Area | Size | State |
|---|---|---|---|---|---|
| [#258](https://github.com/enorm-labs/event-checker/issues/258) | Settle the cloud platform — move ADR-012 from Proposed to Accepted | Task | infra | S | needs-decision |
| [#259](https://github.com/enorm-labs/event-checker/issues/259) | Register event-junkie.de | Task | infra | S | blocked |
| [#260](https://github.com/enorm-labs/event-checker/issues/260) | Provision the cloud environment with Terraform / OpenTofu | Task | infra | L | blocked |
| [#261](https://github.com/enorm-labs/event-checker/issues/261) | Write the Helm chart | Task | infra | L | blocked |
| [#262](https://github.com/enorm-labs/event-checker/issues/262) | Containerise events-frontend (Node build → nginx) | Task | frontend, infra | M |  |
| [#263](https://github.com/enorm-labs/event-checker/issues/263) | Exercise the chart and images locally on k3d before deploying | Task | infra | M | blocked |
| [#264](https://github.com/enorm-labs/event-checker/issues/264) | Create the release and deploy workflows (CI/CD) | Task | infra, ci | M | blocked |
| [#265](https://github.com/enorm-labs/event-checker/issues/265) | A non-public test/staging stage, separate from production | Task | infra | M | blocked |

## v0.3 — Launch-ready — 19 open

| # | Title | Type | Area | Size | State |
|---|---|---|---|---|---|
| [#266](https://github.com/enorm-labs/event-checker/issues/266) | Clear the open Dependabot security alerts | Task | security | M |  |
| [#267](https://github.com/enorm-labs/event-checker/issues/267) | Add authentication and authorization | Feature | bff, security | L | needs-decision |
| [#268](https://github.com/enorm-labs/event-checker/issues/268) | Protect the public BFF API — rate limiting and abuse control | Task | bff, security | M |  |
| [#269](https://github.com/enorm-labs/event-checker/issues/269) | Add caching to the BFF | Task | bff | M |  |
| [#270](https://github.com/enorm-labs/event-checker/issues/270) | PostgreSQL backups plus a rehearsed restore | Task | infra | L |  |
| [#271](https://github.com/enorm-labs/event-checker/issues/271) | Monitoring and alerting, before launch rather than after the first outage | Task | infra | L |  |
| [#272](https://github.com/enorm-labs/event-checker/issues/272) | Create a reusable test data set | Task | data-quality, ci | M |  |
| [#273](https://github.com/enorm-labs/event-checker/issues/273) | Replace the placeholder postal address | Task | legal | S | blocked |
| [#274](https://github.com/enorm-labs/event-checker/issues/274) | Register the role mailboxes and confirm mail arrives | Task | infra, legal | S | blocked |
| [#275](https://github.com/enorm-labs/event-checker/issues/275) | Conclude the Art. 28 processor contracts | Task | legal | S | blocked |
| [#276](https://github.com/enorm-labs/event-checker/issues/276) | Settle the logging decisions the privacy notice depends on | Task | infra, legal | M |  |
| [#277](https://github.com/enorm-labs/event-checker/issues/277) | Give backup retention its own line in the privacy notice | Task | legal | S | blocked |
| [#278](https://github.com/enorm-labs/event-checker/issues/278) | Re-check the privacy notice against what actually runs | Task | legal | M | blocked |
| [#279](https://github.com/enorm-labs/event-checker/issues/279) | Legal review of the German privacy notice | Task | legal | M | blocked |
| [#280](https://github.com/enorm-labs/event-checker/issues/280) | Read every page in both languages, as a reader rather than as its author | Task | frontend, legal | M |  |
| [#281](https://github.com/enorm-labs/event-checker/issues/281) | Review the repo health files — CoC, CONTRIBUTING, README, SECURITY, templates | Task | documentation | M |  |
| [#282](https://github.com/enorm-labs/event-checker/issues/282) | Confirm the legality of scraping and displaying event data | Task | legal | M | needs-decision |
| [#283](https://github.com/enorm-labs/event-checker/issues/283) | Track copyright and licence status per event source | Task | data-quality, legal | M |  |
| [#284](https://github.com/enorm-labs/event-checker/issues/284) | Assemble and run the go-live checklist | Task | infra | M |  |

## v1.0 — Go-live — 14 open

| # | Title | Type | Area | Size | State |
|---|---|---|---|---|---|
| [#285](https://github.com/enorm-labs/event-checker/issues/285) | Deploy to production | Task | infra | M | blocked |
| [#286](https://github.com/enorm-labs/event-checker/issues/286) | Override robots.txt and sitemap.xml outside production | Task | infra, seo | S | needs-deployment |
| [#287](https://github.com/enorm-labs/event-checker/issues/287) | Build the meta-injection transport | Feature | infra, seo | L | needs-deployment |
| [#288](https://github.com/enorm-labs/event-checker/issues/288) | Set the site up in Google Search Console | Task | seo | S | needs-deployment |
| [#289](https://github.com/enorm-labs/event-checker/issues/289) | Confirm the sitemap and hreflang pairs are accepted | Task | seo | S | needs-deployment |
| [#290](https://github.com/enorm-labs/event-checker/issues/290) | Run the Google Rich Results Test on a real event page | Task | seo | S | needs-deployment |
| [#291](https://github.com/enorm-labs/event-checker/issues/291) | Check a real link preview in Slack, WhatsApp and iMessage | Task | seo | S | needs-deployment |
| [#292](https://github.com/enorm-labs/event-checker/issues/292) | Run Lighthouse / PageSpeed against the live origin | Task | frontend, seo | S | needs-deployment |
| [#293](https://github.com/enorm-labs/event-checker/issues/293) | Watch indexing, especially of detail pages | Task | seo | S | needs-deployment |
| [#294](https://github.com/enorm-labs/event-checker/issues/294) | Add a hero screenshot to the README | Task | documentation, frontend | S | blocked |
| [#295](https://github.com/enorm-labs/event-checker/issues/295) | Ship 1.0.0 and drop the beta badge — one decision, not two | Task | documentation, frontend | S |  |
| [#296](https://github.com/enorm-labs/event-checker/issues/296) | Maintenance mode — a downtime page for deploys and outages | Feature | frontend, infra | M |  |
| [#297](https://github.com/enorm-labs/event-checker/issues/297) | Re-derive perf/load.js session weights from real traffic | Task | ci | S | needs-deployment |
| [#298](https://github.com/enorm-labs/event-checker/issues/298) | Automate the k6 runs, once there is somewhere worth pointing them | Task | ci | M | needs-deployment |

## Phase 2 — Coverage & polish — 98 open

| # | Title | Type | Area | Size | State |
|---|---|---|---|---|---|
| [#299](https://github.com/enorm-labs/event-checker/issues/299) | A late-night club event is dropped at midnight while it is still running | Bug | importer, data-quality | M |  |
| [#300](https://github.com/enorm-labs/event-checker/issues/300) | Derive the lineup after the event type is final, not before | Bug | importer, data-quality | M |  |
| [#301](https://github.com/enorm-labs/event-checker/issues/301) | DJ lineup entries keep their performance-format suffix | Bug | importer, data-quality | M | needs-decision |
| [#302](https://github.com/enorm-labs/event-checker/issues/302) | A concert-series name appended with an en dash stays on the act | Bug | importer, data-quality | L | needs-decision |
| [#303](https://github.com/enorm-labs/event-checker/issues/303) | A venue's seating information has nowhere to go | Bug | importer, data-quality | M | needs-decision |
| [#304](https://github.com/enorm-labs/event-checker/issues/304) | Promoter display names lose genuine acronyms | Bug | importer, data-quality | S |  |
| [#305](https://github.com/enorm-labs/event-checker/issues/305) | A `feat.` co-bill is stored as one artist | Bug | importer, data-quality | S |  |
| [#306](https://github.com/enorm-labs/event-checker/issues/306) | An event name is minted as a headliner because the venue typed the night `CONCERT` | Bug | importer, data-quality | M | needs-decision |
| [#307](https://github.com/enorm-labs/event-checker/issues/307) | Huxleys' genre and promoter are stored de-slugified | Bug | importer, data-quality | S |  |
| [#308](https://github.com/enorm-labs/event-checker/issues/308) | Arcanoa's recurring open stage becomes two artists and two slugs | Bug | importer, data-quality | S |  |
| [#309](https://github.com/enorm-labs/event-checker/issues/309) | gART.n drops the guests named in a `<sup>` cast line | Bug | importer, data-quality | S |  |
| [#310](https://github.com/enorm-labs/event-checker/issues/310) | The screening keyword misses German compounds | Bug | importer, data-quality | S |  |
| [#311](https://github.com/enorm-labs/event-checker/issues/311) | The football keywords cannot tell a match screening from a football talk | Bug | importer, data-quality | M |  |
| [#312](https://github.com/enorm-labs/event-checker/issues/312) | Astra's dateless featured teaser is dropped whenever its detail fetch fails | Bug | importer | S |  |
| [#313](https://github.com/enorm-labs/event-checker/issues/313) | Heimathafen stores no genre only because the taxonomy is unresolved | Bug | importer, data-quality | M |  |
| [#314](https://github.com/enorm-labs/event-checker/issues/314) | A country/origin tag stays attached to the act name | Bug | importer, data-quality | M |  |
| [#315](https://github.com/enorm-labs/event-checker/issues/315) | Only concerts get an artist, so a solo bill outside `CONCERT` loses its performer | Bug | importer, data-quality | M |  |
| [#316](https://github.com/enorm-labs/event-checker/issues/316) | There is no event-level room, so a multi-room venue loses which space a show plays in | Bug | importer, data-quality | M | needs-decision |
| [#317](https://github.com/enorm-labs/event-checker/issues/317) | There is no event end time | Bug | importer, data-quality | M | needs-decision |
| [#318](https://github.com/enorm-labs/event-checker/issues/318) | Eschschloraque's doors/start split is published in prose and dropped | Bug | importer, data-quality | S | needs-decision |
| [#319](https://github.com/enorm-labs/event-checker/issues/319) | Pillar 1 (Measure) — a data-quality report endpoint, metrics and daily snapshots | Feature | data-quality, bff | L |  |
| [#320](https://github.com/enorm-labs/event-checker/issues/320) | Pillar 2 (Prevent) — golden fixture tests and a boundary validation gate | Feature | data-quality | L | blocked |
| [#321](https://github.com/enorm-labs/event-checker/issues/321) | Pillar 3 (Fix) — one deliberate backfill re-scrape | Task | importer, data-quality | M |  |
| [#322](https://github.com/enorm-labs/event-checker/issues/322) | Pillar 4 (Systematize) — AI-assisted data quality in the importer | Feature | importer, data-quality | XL | needs-decision |
| [#323](https://github.com/enorm-labs/event-checker/issues/323) | Decision — curated-vocabulary storage, code versus data | Task | data-quality | M | needs-decision |
| [#324](https://github.com/enorm-labs/event-checker/issues/324) | Resolve a venue per event, so promoter sources become importable | Feature | importer, data-quality | XL | needs-decision |
| [#325](https://github.com/enorm-labs/event-checker/issues/325) | List promoters in their own table in EVENT_DATA_SOURCES.md | Task | documentation, importer | S |  |
| [#326](https://github.com/enorm-labs/event-checker/issues/326) | Decision — should an event series be a first-class entity? | Task | data-quality, frontend | M | needs-decision |
| [#327](https://github.com/enorm-labs/event-checker/issues/327) | Enrich venues — type, description, image, genres, event types | Task | importer, data-quality | L |  |
| [#328](https://github.com/enorm-labs/event-checker/issues/328) | Enrich promoters — description, image, corrected display names | Task | importer, data-quality | M |  |
| [#329](https://github.com/enorm-labs/event-checker/issues/329) | Check and fix venue districts, addresses and geo-coordinates | Task | data-quality | M |  |
| [#330](https://github.com/enorm-labs/event-checker/issues/330) | Scrape events in both English and German where the source offers it | Task | importer, data-quality | L |  |
| [#331](https://github.com/enorm-labs/event-checker/issues/331) | Scrape all available events via pagination, not just the first page | Task | importer | M |  |
| [#332](https://github.com/enorm-labs/event-checker/issues/332) | Review events typed `OTHER` — should the event-type enum grow? | Task | importer, data-quality | M | needs-decision |
| [#333](https://github.com/enorm-labs/event-checker/issues/333) | Expand Elfsight monthly recurrence rules | Bug | importer | M |  |
| [#334](https://github.com/enorm-labs/event-checker/issues/334) | Cover venues that will never have an automatic import | Feature | documentation, importer | L |  |
| [#335](https://github.com/enorm-labs/event-checker/issues/335) | Deferred — classical concerts and orchestras, blocked on the artist model | Task | importer | L | blocked, needs-decision |
| [#336](https://github.com/enorm-labs/event-checker/issues/336) | Undecided — streamed and broadcast events | Task |  | S | needs-decision |
| [#337](https://github.com/enorm-labs/event-checker/issues/337) | Deferred — exhibitions as first-class runs, blocked on the time model | Task |  | L | blocked, needs-decision |
| [#338](https://github.com/enorm-labs/event-checker/issues/338) | Move the comedy and theatre venues from Blocked to Ready | Task | documentation, importer | S |  |
| [#339](https://github.com/enorm-labs/event-checker/issues/339) | Recover the act from a "curated by / invites / hosted by" title | Bug | importer, data-quality | M |  |
| [#340](https://github.com/enorm-labs/event-checker/issues/340) | Admin frontend — one place to operate the importers and curate the data | Feature | importer, frontend | XL |  |
| [#341](https://github.com/enorm-labs/event-checker/issues/341) | Pick an admin dashboard template rather than building the shell from scratch | Task | frontend | M |  |
| [#342](https://github.com/enorm-labs/event-checker/issues/342) | Imports status and control — see failures, trigger an import | Feature | importer, frontend | M |  |
| [#343](https://github.com/enorm-labs/event-checker/issues/343) | Import configuration — manage sources and their schedules | Feature | importer, frontend | M |  |
| [#344](https://github.com/enorm-labs/event-checker/issues/344) | Data-quality overview — per-metric, per-source, with a trend | Feature | data-quality, frontend | M | blocked |
| [#345](https://github.com/enorm-labs/event-checker/issues/345) | Data review and fixing — sort by missing fields, edit values | Feature | data-quality, frontend | L |  |
| [#346](https://github.com/enorm-labs/event-checker/issues/346) | AI-assisted checking and fixing, human-in-the-loop | Feature | data-quality, frontend | L | needs-decision |
| [#347](https://github.com/enorm-labs/event-checker/issues/347) | Manual event entry — a fast form for venues with no importer | Feature | frontend | M |  |
| [#348](https://github.com/enorm-labs/event-checker/issues/348) | Submission review queue with plausibility checks | Feature | data-quality, frontend | L |  |
| [#349](https://github.com/enorm-labs/event-checker/issues/349) | Improve the importer's Swagger UI to match the BFF's | Task | documentation, importer | S |  |
| [#350](https://github.com/enorm-labs/event-checker/issues/350) | Housekeeping — a deletion policy for old events and orphaned artists | Task | data-quality | M | needs-decision |
| [#351](https://github.com/enorm-labs/event-checker/issues/351) | Expand importer coverage toward the full Berlin venue list | Feature | importer | XL |  |
| [#352](https://github.com/enorm-labs/event-checker/issues/352) | Find venues we may have missed | Task | documentation, importer | M |  |
| [#353](https://github.com/enorm-labs/event-checker/issues/353) | Evaluate music bars and event bars as sources | Task | documentation, importer | M |  |
| [#354](https://github.com/enorm-labs/event-checker/issues/354) | Radio-station event listings | Task | importer | M |  |
| [#355](https://github.com/enorm-labs/event-checker/issues/355) | Standardize and simplify the existing importers where it helps | Task | importer | L |  |
| [#356](https://github.com/enorm-labs/event-checker/issues/356) | Decide whether importing from Resident Advisor is permissible | Task | importer, legal | S | needs-decision |
| [#357](https://github.com/enorm-labs/event-checker/issues/357) | Add a map to the venues overview, plotting tonight's events | Feature | frontend | L | blocked |
| [#358](https://github.com/enorm-labs/event-checker/issues/358) | "Near me" — filter events by distance from the user | Feature | bff, frontend, legal | L | blocked |
| [#359](https://github.com/enorm-labs/event-checker/issues/359) | Related / similar events on detail pages | Feature | bff, frontend, seo | M |  |
| [#360](https://github.com/enorm-labs/event-checker/issues/360) | Let list views be sorted, not only filtered | Feature | frontend | S | needs-decision |
| [#361](https://github.com/enorm-labs/event-checker/issues/361) | Filter events by venue type, and venues by type and genre | Feature | bff, frontend | M | blocked |
| [#362](https://github.com/enorm-labs/event-checker/issues/362) | Browse past events — an archive view | Feature | bff, frontend | M | needs-decision |
| [#363](https://github.com/enorm-labs/event-checker/issues/363) | Reduce or group the displayed genres | Task | data-quality, frontend | M | needs-decision |
| [#364](https://github.com/enorm-labs/event-checker/issues/364) | Decide whether to display event descriptions and source images | Task | frontend, legal | M | needs-decision |
| [#365](https://github.com/enorm-labs/event-checker/issues/365) | Show the number of displayed events on the calendar and detail-page feeds | Task | frontend | S | needs-decision |
| [#366](https://github.com/enorm-labs/event-checker/issues/366) | Make the home page a real entry point into the data | Feature | frontend | M |  |
| [#367](https://github.com/enorm-labs/event-checker/issues/367) | BFF-served sitemap for detail routes | Feature | bff, seo | M |  |
| [#368](https://github.com/enorm-labs/event-checker/issues/368) | RSS feed for newly imported events | Feature | bff | M |  |
| [#369](https://github.com/enorm-labs/event-checker/issues/369) | Add capacity and venue size to the venue detail page | Task | data-quality, frontend | M |  |
| [#370](https://github.com/enorm-labs/event-checker/issues/370) | Enforce that events-frontend/src/api/schema.d.ts is current | Task | frontend, ci | L |  |
| [#371](https://github.com/enorm-labs/event-checker/issues/371) | TypeScript 7 — blocked on vue-tsc, not on us | Task | frontend | S | blocked |
| [#372](https://github.com/enorm-labs/event-checker/issues/372) | Full frontend UX pass | Task | frontend | L |  |
| [#373](https://github.com/enorm-labs/event-checker/issues/373) | Manual accessibility passes — keyboard-only and screen reader | Task | frontend, legal | M |  |
| [#374](https://github.com/enorm-labs/event-checker/issues/374) | Improve branding and visual design | Task | frontend | L |  |
| [#375](https://github.com/enorm-labs/event-checker/issues/375) | Verify responsive design and the look on real mobile devices | Task | frontend | S |  |
| [#376](https://github.com/enorm-labs/event-checker/issues/376) | Audit that all user-facing surfaces read "Event Junkie" | Task | documentation, frontend | S |  |
| [#377](https://github.com/enorm-labs/event-checker/issues/377) | Decide on a display/hero typeface versus staying all-Geist | Task | frontend | S | needs-decision |
| [#378](https://github.com/enorm-labs/event-checker/issues/378) | Route "missing event or venue" and general feedback from the site to the tracker | Feature | frontend | S |  |
| [#379](https://github.com/enorm-labs/event-checker/issues/379) | Evaluate design tools and AI models for UI and branding | Task | frontend | M |  |
| [#380](https://github.com/enorm-labs/event-checker/issues/380) | Always attach context to log lines — event id, artist id, source | Task | importer, infra | M |  |
| [#381](https://github.com/enorm-labs/event-checker/issues/381) | Nothing checks that notices.json is current | Task | ci, legal | M |  |
| [#382](https://github.com/enorm-labs/event-checker/issues/382) | Opt in to Vite's native config loader | Task | frontend | S | blocked |
| [#383](https://github.com/enorm-labs/event-checker/issues/383) | Evaluate a Checkov scan for the infrastructure code | Task | ci, security | S | blocked |
| [#384](https://github.com/enorm-labs/event-checker/issues/384) | Evaluate an infra/tooling update checker beyond Dependabot | Task | ci, security | S |  |
| [#385](https://github.com/enorm-labs/event-checker/issues/385) | Review the security workflows GitHub offers | Task | ci, security | S |  |
| [#386](https://github.com/enorm-labs/event-checker/issues/386) | A dashboard for analysing the data | Feature | data-quality, infra | L |  |
| [#387](https://github.com/enorm-labs/event-checker/issues/387) | Evaluate agentic workflows for continuous refactoring and docs | Task | ci, agents | M |  |
| [#388](https://github.com/enorm-labs/event-checker/issues/388) | Path-specific agent instruction files, at least backend and frontend | Task | documentation, agents | M |  |
| [#389](https://github.com/enorm-labs/event-checker/issues/389) | Create the remaining prompts, skills and agents | Task | agents | L |  |
| [#390](https://github.com/enorm-labs/event-checker/issues/390) | Evaluate external agent-tooling ideas worth stealing | Task | agents | M |  |
| [#391](https://github.com/enorm-labs/event-checker/issues/391) | Fix dev-env.sh diff-snapshot when the baseline snapshot is empty | Bug | agents | S |  |
| [#392](https://github.com/enorm-labs/event-checker/issues/392) | Decision — rename the repo and internal references to event-junkie | Task | documentation | XL | needs-decision |
| [#393](https://github.com/enorm-labs/event-checker/issues/393) | Clean up KDoc comments across the codebase | Task | documentation | M |  |
| [#394](https://github.com/enorm-labs/event-checker/issues/394) | Generate a Mermaid domain class diagram via Gradle | Task | documentation | M |  |
| [#395](https://github.com/enorm-labs/event-checker/issues/395) | Repository best-practices pass | Task | documentation, ci | M |  |
| [#396](https://github.com/enorm-labs/event-checker/issues/396) | Create a template repository from this project's tooling | Task | documentation, ci | L |  |

## Phase 3 — Accounts & personalization — 3 open

| # | Title | Type | Area | Size | State |
|---|---|---|---|---|---|
| [#397](https://github.com/enorm-labs/event-checker/issues/397) | Decision — does personalization need an account at all? | Task | frontend, legal | M | needs-decision |
| [#398](https://github.com/enorm-labs/event-checker/issues/398) | Accounts, follows and notifications | Feature | bff, frontend | XL | blocked |
| [#399](https://github.com/enorm-labs/event-checker/issues/399) | User and venue submitted events, with review before publish | Feature | data-quality, frontend | XL |  |

## Phase 4 — Social & ecosystem — 3 open

| # | Title | Type | Area | Size | State |
|---|---|---|---|---|---|
| [#400](https://github.com/enorm-labs/event-checker/issues/400) | Social layer — friends, activity and invitations | Feature | bff, frontend | XL | blocked |
| [#401](https://github.com/enorm-labs/event-checker/issues/401) | Calendar subscriptions and external music-service integrations | Feature | bff | XL | blocked |
| [#402](https://github.com/enorm-labs/event-checker/issues/402) | Public API and conversational access | Feature | bff | XL |  |

## Unscheduled — 1 open

| # | Title | Type | Area | Size | State |
|---|---|---|---|---|---|
| [#403](https://github.com/enorm-labs/event-checker/issues/403) | Expand beyond Berlin to other cities | Feature |  | XL |  |
