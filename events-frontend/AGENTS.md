# AGENTS.md — `events-frontend/`

The conventions every change to the Vue SPA is held to. The nearest `AGENTS.md` wins, so this file overrides the repository root's for anything under
`events-frontend/`. The project is npm-managed, not a Gradle subproject, with its own workflow (`build-frontend.yml`: `npm ci`, lint, build, unit, e2e, on
Node 24).

## The short version

```sh
npm run dev                                       # Vite on 5173; /api proxies to the BFF, which must be on 8080
npm run type-check && npm run lint && npm run test:unit && npm run test:e2e   # the gate, before any PR
npm run format                                    # oxfmt; reformatting is intentional, never revert it
npm run generate:api                              # regenerate schema.d.ts — whenever the BFF's API changes
npm run test:a11y                                 # the axe/WCAG sweep alone (a filter over test:e2e)
```

**Four rules that catch most changes:**

1. **A legal or About page is a document per language, not translated strings.** Edit both languages or neither — [docs/LEGAL.md](../docs/LEGAL.md) §6.1.
2. **A change that adds a third-party request or stores anything on the visitor's device needs the privacy notice updated in the same PR, in both languages.**
3. **Accessibility is WCAG 2.1 AA and it is linted** — [vue.instructions.md](../.github/instructions/vue.instructions.md) has the target and the two checks.
4. **`schema.d.ts` is generated and committed, and nothing checks that it is current.** A BFF API change that skips `npm run generate:api` leaves the frontend
   type-checking against an API that no longer exists.

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
ESLint rules in `eslint-rules/`, on the ESLint side because oxlint takes no JS plugins).

## Agent Instructions

- **No unsolicited git commits, pushes or rebases.** Only when the user asks.
- **`npm run build` after an implementation** (vue-tsc + Vite), and `npm run lint` and `npm run format` before finishing.
- **Layout**: `src/{views,components,composables,lib,i18n,api,router,assets}`, `components/ui/` is vendored shadcn-vue (`npx shadcn-vue add`), tests colocated
  in `__tests__/`, `e2e/` for Playwright, `injector/` for the meta-injection sidecar (ADR-014: Node, no DOM, no Vue), `scripts/` for build-time generators.
  Composables are `use*`, one per file, returning `readonly(ref)` where consumers must not mutate; `ref` for primitives, `reactive` for records (never
  destructured without `toRefs()`), `shallowRef` for wholesale-replaced data, `markRaw` for third-party instances. Routes in `src/router/index.ts`, lazy
  `import()` for non-critical ones, kebab-case names.

### Config-loader imports (the `.ts` exception)

Four imports carry an explicit `.ts` extension: `vite.config.ts` → `./scripts/seoFiles.ts`, `vitest.config.ts` → `./vite.config.ts`, `scripts/seoFiles.ts` →
`../src/lib/seo.ts`, `src/lib/seo.ts` → `../i18n/locales.ts`. Vite's config loader is moving to `configLoader: 'native'`, Node's ESM resolver, where a
specifier means exactly what it says — transitively, down the whole chain a config file can reach. **Keep the chain short**: `src/lib/seo.ts` is the only
`src/` module in it, which is why it is documented as free of browser globals. `allowImportingTsExtensions` (from `@vue/tsconfig`, explicit in
`tsconfig.node.json`) is what lets TypeScript accept them. The default loader is still `bundle`; `--configLoader native` works on Node 24 and fails on 22.

## API Communication

Calls go through `src/api/client.ts` (`openapi-fetch`), typed from the generated schema — never bare `fetch`. **Never hand-write a response type**:
`src/api/types.ts` aliases the generated schemas (`EventSummary`, `VenueDetail`, …); use those, not `components['schemas'][…]`. Every generated field is
**optional**, because the BFF emits no `required` metadata — guard with optional chaining and defaults.

**Regenerating `schema.d.ts`** reads the _running_ BFF's OpenAPI document; there is no offline mode:

```bash
./gradlew :events-bff:bootRun          # or scripts/dev-env.sh up bff — restart it after editing a controller or DTO
npm run generate:api                   # events-frontend/
git diff src/api/schema.d.ts && npm run type-check
```

- **A running but stale BFF succeeds and writes the schema for the API you didn't change.** Restart first.
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
- **`sitemap.xml` and `robots.txt` are generated by `scripts/seoFiles.ts`**, at build and from the dev server. No copies under `public/`.
- **The sitemap is the primary `hreflang` carrier.** The `<link>` tags in `lib/seoTags.ts` are script-injected and unreliable for crawlers; the injector
  writes them into served HTML for detail routes only, so an hreflang change that touches only the head tags has not shipped.
- **Canonical URLs come from `SITE_URL`, never `window.location`** — otherwise every alias and preview declares itself canonical.
- **Title, description and image come from `src/lib/pageMeta.ts` — nowhere else.** The client and the injector (`injector/`, ADR-014 §Decision 3) both use it;
  `usePageMeta.ts` only writes tags, and `injector/__tests__/parity.spec.ts` proves both writers leave the same head.
- **The injector is a second Vite build and a second image**: `vite.injector.config.ts` bundles `injector/server.ts` into `dist-injector/injector.mjs`,
  `Dockerfile.injector` ships that file, it answers only the four detail route families `nginx.conf` proxies to it, and it fails open. Everything it imports
  stays free of the DOM and of Vue — `tsconfig.node.json` type-checks it and has neither.
- **Entity descriptions are data and punctuation, never prose** (`Fr., 12. Juni 2026 · Lido, Berlin` needs only `Intl`; the injector may run with no
  catalogue). Static pages take `pageDescription.*` from the catalogue. **Omit a description rather than pad one.**
- **Structured data (`src/lib/structuredData.ts`) describes only what the page displays** — Google policy. **Omit rather than guess**: `eventJsonLd` returns
  `null` when a required field is absent, because partial structured data is rejected outright. Two claims are deliberately not made, for legal reasons:
  performers are `PerformingGroup`, never `Person` (§7.3, artist names are personal data), and the site is a `WebSite`, never an `Organization` (the imprint
  names a private individual). JSON-LD is the one surface that does not wait on prerendering; Googlebot renders JavaScript.

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

## Testing

[testing.instructions.md](../.github/instructions/testing.instructions.md) carries Vitest and Playwright conventions and the locale strategy, loaded with
`e2e/**` and `**/__tests__/**`.
