# AGENTS.md — `events-frontend/`

The conventions every change to the Vue SPA is held to. The nearest `AGENTS.md` wins, so this file overrides the repository root's for anything under
`events-frontend/`. The project is npm-managed, not a Gradle subproject, with its own workflow (`build-frontend.yml`: `npm ci`, lint, build, unit, e2e, on
Node 24).

## The short version

```sh
npm run dev                                       # Vite on 5173; /api proxies to the BFF, which must be on 8080
npm run type-check && npm run lint && npm run test:unit && npm run test:e2e   # the gate, before any PR
npm run format                                    # oxfmt; reformatting is intentional, never revert it. CI runs check:format
npm run generate:api                              # schema.d.ts from a running BFF; § API Communication has the route without one
npm run test:a11y                                 # the axe/WCAG sweep alone (a filter over test:e2e)
npm run check:knip                                # unused files, exports, dependencies; knip.jsonc names each exception
```

**Four rules that catch most changes:**

1. **A legal or About page is a document per language, not translated strings.** Edit both languages or neither — [docs/LEGAL.md](../docs/LEGAL.md) §6.1.
2. **A change that adds a third-party request or stores anything on the visitor's device needs the privacy notice updated in the same PR, in both languages.**
3. **Accessibility is WCAG 2.1 AA and it is linted** — [vue.instructions.md](../.github/instructions/vue.instructions.md) has the target and the two checks.
4. **`schema.d.ts` is generated and committed, and a BFF API change regenerates it in the same PR.** `Build & Test (backend)` fails on a stale file and
   prints the command; [§ API Communication](#api-communication) has it.

Stack: Vue 3 (`<script setup lang="ts">` only, no Options API), TypeScript 6 strict, Vite 8, Vue Router, Tailwind CSS v4 + shadcn-vue (ADR-010), oxlint +
eslint + oxfmt, Vitest (jsdom), Playwright. **Do not add Prettier.** Path alias `@/` → `src/`; no semicolons, single quotes, no file extensions on imports
except the four below.

**Node `>=24.15.0` — a patch floor, forced by jsdom 30** (`^22.22.2 || ^24.15.0 || >=26`); a bare `>=24` would let `npm install` succeed on a Node jsdom does
not support, with only an `EBADENGINE` warning. The floor moved before, for `vue-i18n` (ADR-013), and each move was forced, not chosen. **A stale local Node
silently hides dependency updates**: `npm outdated` filters out versions whose `engines` your interpreter fails, which is how jsdom 30 stayed invisible. A
quiet report means check `node --version` against `.nvmrc` first.

Path-scoped rules carry the rest and load with the matching files: [vue.instructions.md](../.github/instructions/vue.instructions.md) (SFC structure,
Tailwind, shadcn-vue, accessibility), [design.instructions.md](../.github/instructions/design.instructions.md) (tokens, type scale),
[testing.instructions.md](../.github/instructions/testing.instructions.md) (Vitest, Playwright, the locale strategy),
[comments.instructions.md](../.github/instructions/comments.instructions.md) (`max-comment-lines` at 15, `comment-density`, `comment-smell` — the local
ESLint rules in `eslint-rules/`, on the ESLint side because oxlint reads only the `<script>` block of a `.vue` file).

## Agent Instructions

- **No unsolicited git commits, pushes or rebases.** Only when the user asks.
- **`npm run build` after an implementation** (vue-tsc + Vite), and `npm run lint` and `npm run format` before finishing. `npm run format` is `oxfmt src`:
  `e2e/` is outside the formatter and keeps its hand wrapping, so never run oxfmt on a spec. It rewraps lines nobody touched (#2370).
- **Layout**: `src/{views,components,composables,lib,i18n,api,router,assets}`, `components/ui/` is vendored shadcn-vue (`npx shadcn-vue add`), tests colocated
  in `__tests__/`, `e2e/` for Playwright, `injector/` for the meta-injection sidecar (ADR-014: Node, no DOM, no Vue), `scripts/` for build-time generators.
  Composables are `use*`, one per file, returning `readonly(ref)` where consumers must not mutate; `ref` for primitives, `reactive` for records (never
  destructured without `toRefs()`), `shallowRef` for wholesale-replaced data, `markRaw` for third-party instances. Routes in `src/router/index.ts`, lazy
  `import()` for non-critical ones, kebab-case names.

### Config-loader imports (the `.ts` exception)

Every npm script that runs Vite or Vitest passes `--configLoader native`: Node's own ESM loader reads the config, not Vite's default Rolldown bundle (#382).
A specifier then means exactly what it says — transitively, down the whole chain a config file can reach. So eight imports carry an explicit `.ts`
extension: `vite.config.ts` → `./scripts/seoFiles.ts`, `./scripts/pageMetaText.ts`, `./scripts/csp.ts`; `vitest.config.ts` → `./vite.config.ts`;
`vite.injector.config.ts` → `./scripts/pageMetaText.ts`; `scripts/seoFiles.ts` → `../src/lib/seo.ts`; `scripts/pageMetaText.ts` and `src/lib/seo.ts` →
`locales.ts`. No `@/` alias in the chain either: `resolve.alias` does not exist yet when the config loads. **Keep the chain short**: `src/lib/seo.ts` is
the only `src/` module in it besides `locales.ts`, which is why it is documented as free of browser globals. `allowImportingTsExtensions` (from
`@vue/tsconfig`, explicit in `tsconfig.node.json`) is what lets TypeScript accept them. A bare `npx vite` uses the `bundle` loader and still works; go
through the npm scripts so the loader CI runs is the one you test.

## API Communication

Calls go through `src/api/client.ts` (`openapi-fetch`), typed from the generated schema — never bare `fetch`. **Never hand-write a response type**:
`src/api/types.ts` aliases the generated schemas (`EventSummary`, `VenueDetail`, …); use those, not `components['schemas'][…]`. Every generated field is
**optional**, because the BFF emits no `required` metadata — guard with optional chaining and defaults.

**Regenerating `schema.d.ts`** needs no running BFF. `OpenApiDocumentTest` boots it in the test suite and writes its OpenAPI document, and
`scripts/api-schema-parity.sh` generates from that file with the version `generate:api` pins. `build-backend.yml` runs the same script with `check` and fails
on any difference (#370):

```bash
./gradlew :events-bff:test --tests '*OpenApiDocumentTest' && scripts/api-schema-parity.sh   # repository root
git diff events-frontend/src/api/schema.d.ts && npm --prefix events-frontend run type-check
```

- **`npm run generate:api` reads a running BFF on `:8080` instead, and a stale one succeeds** — it writes the schema for the API you didn't change. Restart
  it after editing a controller or DTO.
- **The committed file is the generator's raw output — double quotes, semicolons — and oxfmt ignores it** (`.oxfmtrc.json`). Never format it: the diff
  of a regeneration is then the API change alone, and a formatted copy turns two changed lines into a 3,000-line rewrite.
- **A rename lands as a delete plus an add**, and surfaces as a type error in `types.ts` — fix the alias, don't widen it. **Removing or narrowing a field is
  a site break, not a type break**: regenerating makes it compile, not render. Grep for the alias.
- **Never edit `schema.d.ts` by hand.** It covers the BFF only; the importer's admin API on `:8081` is not consumed.

## Localisation

Every page lives under `/<locale>/…`; `src/i18n/locales.ts` is the single list of what is published (ADR-013).

- **Every in-app link goes through `useLocalePath()`.** A bare `to="/events"` works via the catch-all redirect, and costs a redirect and a wrong URL flash.
- **Adding a locale means adding it to `LOCALES` and shipping its catalogue in the same change** — a locale is routable the moment it is listed, and a `/de`
  URL rendering English is worse than no `/de`.
- **User-facing strings belong in `src/i18n/messages/`.** The five long-form pages are the exception: About and `/legal/*` have one component per language
  (`ImprintView.en.vue` / `.de.vue`, wired through `localisedView()`), because their prose carries inline links and markup JSON cannot hold, and a legal page
  has to be reviewable as a document. **Edit both languages in the same change**; shared facts (address, authority, review date) come from `src/lib/legal.ts`,
  and `views/legal/__tests__/legalViews.spec.ts` runs the mandatory-element checklist per language. **German is authoritative** (LEGAL.md §6.1), and both
  versions say so — do not remove that sentence.
- **`docker/nginx.conf` logs no IP address; that is a privacy decision** (#276, LEGAL.md §7.5). Its `ej_no_ip` format overrides the base image's `main`, whose
  last field is `$http_x_forwarded_for` — the visitor's real address behind Traefik. Adding either field back changes what the notice must declare.
- **`lib/format.ts` stays pure** (takes a locale); `composables/useFormat.ts` supplies it from i18n. **`todayIso()`'s `en-CA` is a format, not a language** —
  the shortest way to `YYYY-MM-DD`; making it locale-aware breaks every date filter silently. Event-type labels come from `eventType.*`, with
  `humaniseEventType()` as the fallback for a value the BFF enum gained first. Component tests get the i18n plugin from `src/test/setup.ts`.

### SEO surfaces

- **A new static route decides whether it is indexable** — `INDEXABLE_PATHS` or `NON_INDEXABLE_PATHS` in `src/lib/seo.ts`; a unit test compares both against
  the router. It also needs a `descriptionKey`, or it falls back to the site-level description.
- **`sitemap.xml`, `sitemap-pages.xml`, `robots.txt` and `llms.txt` are generated by `scripts/seoFiles.ts`**, at build and from the dev server. No copies
  under `public/`. **`robots.txt` keeps the AI training crawlers out and lets the retrieval crawlers in** (ADR-044, a licence decision): a new vendor's
  training token goes into `AI_TRAINING_CRAWLERS` in `lib/seo.ts` with its documentation link. `sitemap.xml` is an index: it names `sitemap-pages.xml` and the four detail sitemaps, `/sitemap-<kind>.xml`, which the BFF renders
  (`/api/sitemaps/<kind>.xml`, #367). nginx sends those four through the injector, and the dev server proxies them to the BFF.
- **The RSS feed of new events is public at `/feed.xml?locale=<locale>`** (#368), and the BFF renders it at `/api/events/feed`. nginx sends `/feed.xml`
  through the injector with its query, and the dev server proxies it to the BFF. Every page's head names it through both writers (`feedUrl()` in
  `lib/seo.ts`); the footer and the filter bar link it (`feedPath()`, the filter bar's with its filters). Its titles mirror `EventFeedXml.kt`.
- **The calendar subscription is public at `/calendar.ics?locale=<locale>`** (#2719), routed like the feed to `/api/events/calendar.ics`. The results
  bar links it as `webcal://` on the current host, with the site-relative `https:` address beside it (`calendarPath()`). `EventCalendarIcs.kt` writes
  the file by the rules of `lib/addToCalendar.ts`: change both or neither.
- **The sitemap is the primary `hreflang` carrier.** The `<link>` tags in `lib/seoTags.ts` are script-injected and unreliable for crawlers; the injector
  writes them into served HTML for detail routes only, so an hreflang change that touches only the head tags has not shipped.
- **Canonical URLs come from `SITE_URL`, never `window.location`** — otherwise every alias and preview declares itself canonical.
- **Title, description and image come from `src/lib/pageMeta.ts` — nowhere else.** The client and the injector (`injector/`, ADR-014 §Decision 3) both use it;
  `usePageMeta.ts` only writes tags, and `injector/__tests__/parity.spec.ts` proves both writers leave the same head.
- **The injector is a second Vite build and a second image**: `vite.injector.config.ts` bundles `injector/server.ts` into `dist-injector/injector.mjs`,
  `Dockerfile.injector` ships that file, it answers only the four detail route families, the static pages and the four detail sitemaps `nginx.conf` proxies to it, and it fails open. Everything it imports
  stays free of the DOM and of Vue — `tsconfig.node.json` type-checks it and has neither.
- **Entity descriptions are data and punctuation, never prose** (`Fr., 12. Juni 2026 · Lido, Berlin` needs only `Intl`). Static pages take `pageTitle.*`
  and `pageDescription.*` from the catalogue, which reaches the injector as plain strings through `virtual:page-meta-text`, because the vue-i18n plugin
  compiles every direct import. **Omit a description rather than pad one**: the page then gets the site description in its own locale.
- **Structured data (`src/lib/structuredData.ts`) describes only what the page displays** — Google policy. **Omit rather than guess**: `eventJsonLd` returns
  `null` when a required field is absent, because partial structured data is rejected outright. Two claims are deliberately not made, for legal reasons:
  performers are `PerformingGroup`, never `Person` (§7.3, artist names are personal data), and the site is a `WebSite`, never an `Organization` (the imprint
  names a private individual). **The injector serves an event's and a venue's JSON-LD in the HTML** (ADR-044), for AI crawlers that run no
  JavaScript: `eventPageJsonLd` and `venuePageJsonLd` build it for both writers, and `useStructuredData` takes the served block over rather than add a
  second.

## Versioning

No file carries the application version ([ADR-032](../docs/adr/ADR-032_VERSION_FROM_TAGS.md)): `scripts/version.sh compute` reads it from the release tags
and the commits since the last one, and the build stamps it. `package.json` holds `0.0.0`, a placeholder — **do not bump it**, there is nothing to keep it in
step with. The footer's version comes from `GET /meta`, stamped by the Gradle build (`useAppMeta.ts`), never from `package.json`.

## Open-source notices

`/legal/notices` renders `src/assets/notices.json`, **generated and committed**, never hand-edited. Regenerate whenever dependencies change on either side:

```bash
scripts/notices-parity.sh                                  # repository root; both generators in the right order, then a diff
./gradlew generateLicenseReport --no-configuration-cache   # the halves by hand: the Gradle report …
npm run generate:notices                                   # … merged with npm's into notices.json
```

- **`npm run generate:notices` merges whatever Gradle report is on disk and cannot tell whether it is current** — a month-old report produced 312 components
  where the answer was 368, exit 0 (#1084). Gradle's `UP-TO-DATE` makes the report's age no evidence; **read the line the generator prints naming the report
  it merged and when it was written.**
- **The generator writes no timestamp**, so unchanged dependencies give an empty diff; `validate-notices.yml` runs the parity script whenever either
  ecosystem's declarations change (#1037). **Bot PRs repair themselves** (`fix-notices-on-bot-prs.yml`); that push stops Dependabot rebasing, so if `main`
  moves under one, close and reopen it.
- **Licence policy is one policy in two files** — `npm run check:licenses` against `config/allowed-licenses-npm.json`, `./gradlew checkLicense
--no-configuration-cache` against `config/allowed-licenses-jvm.json` — because the ecosystems name licences differently. `dependency-review.yml` adds a
  deny-list on newly introduced dependencies. **Do not widen an allow-list to make a build pass**: AGPL, GPL without Classpath Exception, SSPL, BUSL,
  Elastic-2.0 are out ([LEGAL.md §9.2](../docs/LEGAL.md)); a genuine addition records why in the file's `_rationale`.

## Screenshots go stale silently

`docs/screenshots/` holds the README's pictures and nothing signals when one is wrong. Retake after changing `App.vue`, `EventCard.vue`, `EventFilterBar.vue`
or the theme tokens in `main.css` — [`docs/screenshots/README.md`](../docs/screenshots/README.md) has the procedure. Not on a schedule, and not when the data
changes.

## The maps

`/map` (tonight's events) and the venues page's map view share `components/VenueMap.vue`, which takes pins and knows nothing about events
([ADR-037](../docs/adr/ADR-037_SELF_HOSTED_MAP.md)).

- **Every map request goes to this site.** Tiles, glyphs and sprites are served from `/map/`, copied into the image from `event-junkie-map-assets`.
  A style, tile or font URL on another origin is a new processor and a privacy-notice change — rule 2 above.
- **The CSP shapes the code.** The worker is a same-origin file (`?worker&url`), not a `blob:`; the zoom buttons are ours, because MapLibre's draw `data:`
  icons. A MapLibre control added later is checked in `npm run preview`, which sends the production CSP.
- **Style a marker through `classList`, never `className`**: MapLibre positions it with classes of its own.
- **"Near me" measures in the browser and sends nothing** (LEGAL.md §7.4a). The position from `useLocation.ts` lives in memory only: never in a
  query, a URL or storage. A radius on the BFF, or sending the nearby venues' slugs, is a privacy-notice change, not a refactor.
- **MapLibre's paint properties do not parse `oklch()`**, which every token is. `VenueMap.vue`'s `tokenColor` converts through a canvas, and a
  layer added with a token colour re-adds on `style.load`, because a theme swap drops it.
- **Locally**, `scripts/map-assets.sh dev` fills `public/map/` (git-ignored). Without it the pins draw on an empty background, which is also what CI's
  Playwright run sees.

## Testing

[testing.instructions.md](../.github/instructions/testing.instructions.md) carries Vitest and Playwright conventions and the locale strategy, loaded with
`e2e/**` and `**/__tests__/**`.
