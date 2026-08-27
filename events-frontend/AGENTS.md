# AGENTS.md — `events-frontend/`

The conventions every change to the Vue SPA is held to. The nearest `AGENTS.md` wins, so this file overrides the repository root's for anything under
`events-frontend/`.

## The short version

```sh
npm run dev                                       # Vite, on 5173 — the BFF must be running on 8080
npm run type-check && npm run lint && npm run test:unit && npm run test:e2e   # the gate, before any PR
npm run format                                    # oxfmt; reformatting is intentional, never revert it
npm run generate:api                              # regenerate schema.d.ts — needed whenever the BFF's API changes
```

**Four rules that catch most changes:**

1. **A legal or About page is a document per language, not translated strings.** Edit both languages or neither — [docs/LEGAL.md](../docs/LEGAL.md) §6.1.
2. **A change that adds a third-party request or stores anything on the visitor's device needs the privacy notice updated in the same PR, in both languages.**
3. **Accessibility is WCAG 2.1 AA and it is linted.** See §Accessibility before adding an interactive component.
4. **`schema.d.ts` is generated and committed, and nothing checks that it is current.** A BFF API change that skips `npm run generate:api` leaves the frontend
   type-checking against an API that no longer exists.

| Section                                                                         |                                                    |
| ------------------------------------------------------------------------------- | -------------------------------------------------- |
| [Project Structure](#project-structure) · [Code Conventions](#code-conventions) | Where things live, and how they are written        |
| [Localisation](#localisation)                                                   | i18n, routing per locale, and the document rule    |
| [Accessibility](#accessibility)                                                 | The AA target and what enforces it                 |
| [Linting & Formatting](#linting--formatting) · [Testing](#testing)              | oxlint, eslint, oxfmt, Vitest, Playwright          |
| [Versioning](#versioning) · [Open-source notices](#open-source-notices)         | The hand-mirrored version, and licence attribution |

## Agent Instructions

- **No unsolicited git commits/pushes**: Never run `git commit`, `git push`, or `git rebase` (squash) unless explicitly asked to by the user.
- **Build verification**: Always run `npm run build` after finishing an implementation to verify that the project
  compiles (type-check + Vite build) without errors.
- **Lint & format**: Run `npm run lint` and `npm run format` before finishing to ensure code passes oxlint, eslint,
  and oxfmt checks.
- **GitHub CLI (`gh`)**: The `gh` CLI is installed (Homebrew) and authenticated. Use it for GitHub interactions.

## Project Overview

`events-frontend` is a **Vue 3 SPA** for discovering music events in Berlin. It is the user-facing frontend of the
Event Junkie system. It communicates with the backend (`events-bff`) via REST API calls proxied through Vite's
dev server (`/api` → `http://localhost:8080`).

**Tech stack:**

- **Vue 3** (Composition API with `<script setup>`)
- **TypeScript 6**
- **Vite 8** (build tool & dev server)
- **Vue Router** (client-side routing)
- **Tailwind CSS v4** + **shadcn-vue** (styling & accessible component primitives — see ADR-010)
- **oxlint + oxfmt** (primary linter & formatter — fast, Rust-based)
- **eslint** (supplementary linting, integrated with oxlint via `eslint-plugin-oxlint`)
- **Vitest** (unit tests, jsdom environment)
- **Playwright** (end-to-end tests, multi-browser)

**Node version**: `>=24` (enforced via `engines` in `package.json`; `.nvmrc` and CI both pin 24). The floor has moved twice, and each move was forced by a
dependency rather than chosen:

- **Node 20 dropped** when `vue-i18n` was adopted — it requires Node ≥ 22 and `@intlify/unplugin-vue-i18n` ≥ 22.13. See
  [ADR-013](../docs/adr/ADR-013_LOCALISATION.md).
- **Node 22 dropped** for jsdom 30, which requires `^22.22.2 || ^24.15.0 || >=26`. Raising to the 24 line rather than `^22.22.2` matches what `.nvmrc` and CI
  already used. Note the **patch** floor: `>=24.15.0`, not `>=24` — jsdom's range excludes 24.0–24.14, so a bare `>=24` would let `npm install` succeed on a
  Node it does not support, with only an `EBADENGINE` warning to say so.

**A stale local Node silently hides dependency updates.** `npm outdated` filters out versions whose `engines` your interpreter does not satisfy — which is
exactly how jsdom 30 stayed invisible in the report while being available. If a dependency check looks suspiciously quiet, check `node --version` against
`.nvmrc` first.

This project is **not** a Gradle subproject — it is managed separately via npm and has its own CI workflow
(`build-frontend.yml`).

## Build & Dev Commands

```bash
npm run dev        # Vite dev server (http://localhost:5173)
npm run build      # Type-check (vue-tsc) + production build
npm run preview    # Preview production build locally (http://localhost:4173)
npm run test:unit  # Vitest unit tests (jsdom, watch mode)
npm run test:unit:coverage  # Unit tests with V8 coverage report
npm run test:e2e   # Playwright end-to-end tests (chromium, firefox, webkit)
npm run test:a11y  # Just the axe/WCAG sweep (a filter over test:e2e, not a second check)
npm run lint       # oxlint (--fix) + eslint (--fix --cache)
npm run format     # oxfmt formatter
```

## Project Structure

```
events-frontend/
├── src/
│   ├── main.ts              # App entry point
│   ├── App.vue              # Root component
│   ├── router/index.ts      # Vue Router configuration
│   ├── composables/         # Reusable stateful logic (use* functions)
│   ├── views/               # Route-level page components
│   ├── components/          # Reusable UI components
│   │   ├── ui/              # shadcn-vue components (vendored; `npx shadcn-vue add`)
│   │   └── __tests__/       # Unit tests (colocated)
│   ├── lib/                 # Shared helpers (e.g. utils.ts → cn() classnames helper)
│   └── assets/              # Static assets (main.css holds the theme tokens, images)
├── e2e/                     # Playwright end-to-end tests
├── public/                  # Static files served as-is
├── index.html               # HTML entry point
├── vite.config.ts           # Vite configuration
├── vitest.config.ts         # Vitest configuration
├── playwright.config.ts     # Playwright configuration
├── eslint.config.ts         # ESLint flat config
├── .oxlintrc.json           # oxlint configuration
├── tsconfig.json            # TypeScript project references
├── tsconfig.app.json        # App source TS config
├── tsconfig.node.json       # Node/config files TS config
└── tsconfig.vitest.json     # Test files TS config
```

## Code Conventions

### General

- **Reference docs**: [Vue 3 Guide](https://vuejs.org/guide/introduction.html) |
  [Vue Style Guide](https://vuejs.org/style-guide/) |
  [Vue Router](https://router.vuejs.org/) |
  [Vite](https://vite.dev/)
- **Composition API only** — always use `<script setup lang="ts">`. Do not use Options API.
- **TypeScript strict mode** — all code must be fully typed. Avoid `any`; prefer explicit interfaces/types.
- **Path alias** — use `@/` to reference `src/` (configured in `vite.config.ts` and `tsconfig.app.json`).
- **No semicolons** — the project uses oxfmt which omits semicolons (consistent with current codebase style).
- **Single quotes** for string literals (enforced by formatter).
- **No file extensions on imports** — Vite resolves them. Four files are the deliberate exception; see below.

#### Config-loader imports (the `.ts` exception)

Four imports carry an explicit `.ts` extension, against the rule above:

| File                  | Import                  |
| --------------------- | ----------------------- |
| `vite.config.ts`      | `./scripts/seoFiles.ts` |
| `vitest.config.ts`    | `./vite.config.ts`      |
| `scripts/seoFiles.ts` | `../src/lib/seo.ts`     |
| `src/lib/seo.ts`      | `../i18n/locales.ts`    |

Vite's config loader is moving from bundling the config (`configLoader: 'bundle'`, today's default) to handing it
to the Node runtime (`configLoader: 'native'`, the announced future default). The native loader uses Node's ESM
resolver, where a specifier means exactly what it says — no extension inference. The requirement is _transitive_,
so it propagates down the whole import chain reachable from a config file, which is how it reaches into `src/`.

`npm run dev` warned about each of these until they were fixed. Two consequences worth knowing:

- **Keep the chain short.** Every module `vite.config.ts` can reach inherits this constraint. `src/lib/seo.ts` is
  the only `src/` module in it, and that is worth keeping true — it is why the module is documented as free of
  browser globals at module scope.
- **`allowImportingTsExtensions`** is what lets TypeScript accept them. `tsconfig.app.json` inherits it from
  `@vue/tsconfig`; `tsconfig.node.json` sets it explicitly. It requires `noEmit`, which both projects have.

The project still runs on the default `bundle` loader — this is compatibility work, not an opt-in. `vite build
--configLoader native` was verified to work on Node 24 (`.nvmrc`); it fails on Node 22, which lacks unflagged
type-stripping, so do not switch the default until the engine floor moves.

### Component structure, styling and templates

**Moved to a path-scoped rule: [.github/instructions/vue.instructions.md](../.github/instructions/vue.instructions.md).** SFC structure and naming, Tailwind v4
and shadcn-vue, and the Vue Style Guide template rules load automatically when you open a `.vue` or `.css` file, and stay out of the way when you do not. The
reasoning is there, not repeated here.

### Composables

- Extract reusable stateful logic into **composables** — functions prefixed with `use` (e.g. `useCounter`, `useFetch`).
- Place composables in `src/composables/` with one composable per file.
- Return `readonly(ref)` from composables when consumers should not mutate internal state directly.
- Composables can use `ref`, `computed`, lifecycle hooks, and other composables — they are the primary code reuse
  mechanism in Vue 3 (replacing mixins).

### Reactivity

- Use **`ref`** for primitive values (`string`, `number`, `boolean`) and single references.
- Use **`reactive`** for objects/records where you want to avoid `.value` access.
- Do **not** destructure `reactive` objects without `toRefs()` — it breaks reactivity.
- Use `shallowRef` for large arrays/objects that are replaced wholesale (not mutated in place) — avoids deep
  reactivity overhead.
- Use `markRaw` for non-reactive third-party instances (e.g. chart libraries, maps).

### Routing

- Routes are defined in `src/router/index.ts`.
- Use **lazy loading** (dynamic `import()`) for non-critical routes to enable code splitting.
- Route names should be lowercase kebab-case strings.

### API Communication

- The Vite dev server proxies `/api` requests to the BFF backend at `http://localhost:8080`.
- Calls go through `src/api/client.ts` (`openapi-fetch`), which is typed from the generated schema — not through bare `fetch`.
- **Never hand-write a response type.** `src/api/schema.d.ts` is generated from the BFF's OpenAPI document; `src/api/types.ts` gives its schemas readable
  aliases (`EventSummary`, `VenueDetail`, …). Use those aliases in views and composables rather than reaching into `components['schemas'][…]`.
- Every generated field is **optional**, because the BFF's OpenAPI document emits no `required` metadata. Guard with optional chaining and defaults.

#### Regenerating `schema.d.ts` after a BFF API change

**`src/api/schema.d.ts` is generated and committed. If you change the BFF's public API — a new endpoint, a renamed or added response field, a changed
type — regenerate it in the same change, or the frontend will keep type-checking against an API that no longer exists.**

The generator reads the _running_ BFF's live OpenAPI document over HTTP; there is no offline mode and no build-time hook. So the BFF has to be up:

```bash
# 1. Start the BFF (from the repository root) — it must be listening on :8080
./gradlew :events-bff:bootRun
#    or: scripts/dev-env.sh up bff

# 2. Regenerate (from events-frontend/)
npm run generate:api        # npx openapi-typescript http://localhost:8080/v3/api-docs -o src/api/schema.d.ts

# 3. See what actually moved, then follow it through
git diff src/api/schema.d.ts
npm run type-check
```

Things worth knowing:

- **The failure mode is silent and confusing.** With the BFF down, `npm run generate:api` fails on the fetch — noisy and obvious. With the BFF running _stale
  code_, it succeeds and writes a schema for the API you didn't change. Restart the BFF after editing a controller or DTO.
- **A rename lands as a delete plus an add.** `src/api/types.ts` addresses schemas by name (`Schemas['EventDetailResponse']`), so a renamed BFF DTO surfaces as
  a type error there, not in the diff. That is the intended tripwire — fix the alias, don't widen it.
- **Removing or narrowing a field is a breaking change for the site, not just for the types.** Regenerating makes it compile; it does not make the view render.
  Grep for the alias before assuming type-check success means done.
- **Never edit `schema.d.ts` by hand** — it carries a "do not make direct changes" banner and the next regeneration discards them. It also does not count as
  hand-written code in reviews or coverage.
- The generated document covers the **BFF only**. The importer's admin API has its own OpenAPI document on `:8081`, and the frontend does not consume it.

## Localisation

The site is locale-routed: every page lives under `/<locale>/…`, and `src/i18n/locales.ts` is the single list of what is published. See
[ADR-013](../docs/adr/ADR-013_LOCALISATION.md).

- **Every in-app link goes through `useLocalePath()`.** A bare `to="/events"` still _works_ — the catch-all redirects it — but costs a redirect on every
  navigation and briefly shows the wrong URL. `localePath('/events')` → `/en/events`.
- **Adding a locale means adding it to `LOCALES`** _and_ shipping its message catalogue in the same change. The route matcher is built from that list, so a
  locale becomes routable the moment it is listed — and a `/de` URL rendering English is worse than no `/de` at all.
- **User-facing strings belong in `src/i18n/messages/`**, not in templates.
- **The four long-form pages are the documented exception**: About and the three under `/legal/*` have **one component per language**
  (`ImprintView.en.vue` / `ImprintView.de.vue`), wired through `localisedView()` in the router. Their prose carries inline links and `<strong>`/`<code>`
  _inside_ paragraphs, which JSON cannot hold without `v-html` or shattered sentences — and a legal page has to be reviewable as a document. **Editing one
  language version means editing the other in the same change**; facts that must not diverge (address, supervisory authority, review date) come from
  `src/lib/legal.ts`, and `views/legal/__tests__/legalViews.spec.ts` runs the mandatory-element checklist against each language separately.
- **German is the authoritative version of the legal pages** (LEGAL.md §6.1), and both language versions say so. Do not remove that sentence.
- **`docker/nginx.conf` logs no IP address, and that is a privacy decision rather than a formatting one** (#276, LEGAL.md §7.5). It defines its own `ej_no_ip`
  format specifically to override the base image's `main`, whose last field is `"$http_x_forwarded_for"` — the field Traefik fills with the visitor's real
  address. `$remote_addr` is dropped too: it is only ever the proxy's address while a proxy is in front, which is a property of the deployment rather than of
  this file. **Adding either field back changes what the privacy notice must declare**, so it is a change to make deliberately and with §7.5, not while tidying
  a log line.
- **`lib/format.ts` stays pure** — its functions take a locale argument. `composables/useFormat.ts` is the thin layer that supplies it from the active i18n
  instance, so unit tests can call the helpers without mounting an app.
- **`todayIso()`'s `en-CA` is a format, not a language.** It is the shortest way to get `YYYY-MM-DD` out of `Intl`. Making it locale-aware breaks every date
  filter _silently_, because `12.6.2026` is still a plausible date. Do not touch it.
- **Event-type labels come from the `eventType.*` catalogue**, with `humaniseEventType()` as the fallback for values the frontend has not been taught yet — the
  BFF enum can gain a value in a backend release that ships first.
- Component tests get the i18n plugin automatically via `src/test/setup.ts`; no per-spec wiring needed.

### SEO surfaces

- **Adding a static route means deciding whether it is indexable.** Put it in `INDEXABLE_PATHS` or in `NON_INDEXABLE_PATHS` (`src/lib/seo.ts`) — a unit test
  compares both against the router and fails on anything unaccounted for, so this cannot be skipped by forgetting.
- **`sitemap.xml` and `robots.txt` are generated, not files.** `scripts/seoFiles.ts` emits them at build and serves the same bytes from the dev server. Do not
  add copies under `public/`; they would go stale silently.
- **The sitemap is the primary `hreflang` carrier, not a duplicate of the head tags.** The `<link>` elements in `lib/seoTags.ts` are written by JavaScript after
  the router resolves, and script-injected hreflang is unreliable for crawlers. That inverts once prerendering lands; until then, an hreflang change that
  touches only the head tags has not really shipped.
- **Canonical URLs come from `SITE_URL`, never from `window.location`.** Deriving them from the request host makes every alias and preview deployment declare
  itself canonical, which is the duplicate-content problem the tag exists to solve.
- **Title, description and image come from `src/lib/pageMeta.ts` — nowhere else.** That module will be used twice: by the client today, and by the meta injector
  server-side ([ADR-014](../docs/adr/ADR-014_RENDERING_STRATEGY.md) §Decision 3). If the two ever compose their own, a shared link previews as one thing and
  opens as another. `composables/usePageMeta.ts` only writes the tags; it decides nothing.
- **Entity descriptions are composed from data and punctuation, never from prose.** `Fr., 12. Juni 2026 · Lido, Berlin` needs only `Intl`; "Concert at Lido on
  Friday" would need the message catalogue, and the injector may run somewhere that has none. Static pages are the exception — they are not data-driven, so they
  take their description from `pageDescription.*` in the catalogue.
- **Adding a static route means adding a `descriptionKey` too.** Without one the page falls back to the site-level description, which is the gap this replaced.
- **Omit a description rather than pad one.** An artist we hold nothing but a name for has nothing true to say, and the site-level text is a better answer than
  a generated sentence — the same rule the structured data follows.
- **Structured data (`src/lib/structuredData.ts`) may only describe what the page displays.** That is Google policy, not style — structured data must represent
  visible content. Before adding a property, check the view actually renders it.
- **Omit rather than guess.** A missing property costs a recommendation; a wrong one is a misrepresentation we volunteered, on a site whose imprint says _alle
  Angaben ohne Gewähr_. `eventJsonLd` returns `null` when a required field is absent, because partial structured data is rejected outright rather than partially
  used.
- **Two claims are deliberately not made, and both are legal rather than technical.** Performers are `PerformingGroup`, never `Person` — §7.3 treats artist
  names as personal data, and asserting personhood machine-readably is gratuitous. The site is a `WebSite`, never an `Organization` — the imprint states a
  private individual runs it, so the opposite claim would contradict our own legal page.
- Structured data is the one SEO surface that does **not** wait on prerendering: Googlebot renders JavaScript, so it reads JSON-LD injected after boot.

## Versioning

The application version lives in **`version` in the root `gradle.properties`** — that is the single source of truth.

- `events-frontend/package.json` **mirrors** it, kept in step **by hand**. Nothing generates or verifies it, so a version bump is one change touching two files.
- The mirror is deliberately **without** the `-SNAPSHOT` suffix that `gradle.properties` carries: npm's SemVer has no such convention, and `0.1.1-SNAPSHOT`
  reads as a malformed prerelease. The two files are _intentionally_ not byte-identical — do not "fix" this.
- **Do not bump `version` in `package.json` on its own.** It is `private: true` and never published, so nothing breaks visibly if it drifts — which is exactly
  why it needs the discipline.
- **The version the site displays never comes from `package.json`.** The footer reads `GET /meta`, which the backend stamps from the Gradle build (see
  `useAppMeta.ts`). A stale `package.json` therefore cannot put a wrong version on screen; it only misleads people reading the repository.

## Open-source notices

`/legal/notices` renders `src/assets/notices.json`, which is **generated and committed** — never hand-edited. Regenerate it whenever dependencies change on
either side:

```bash
./gradlew generateLicenseReport --no-configuration-cache   # repository root; writes build/reports/dependency-license/licenses.json
npm run generate:notices                                   # events-frontend; merges both ecosystems into src/assets/notices.json
```

- The `--no-configuration-cache` flag is required — the licence-report plugin is not configuration-cache compatible (see the note in `gradle.properties`).
- The generator deliberately writes **no timestamp**, so re-running it with unchanged dependencies produces an identical file and an empty diff.
  Licence policy is enforced on both sides, in two files because the ecosystems report licence names differently (SPDX ids vs the Gradle normaliser's prose
  names). They are one policy — change them together:

```bash
npm run check:licenses                                     # this project's production dependencies, vs config/allowed-licenses-npm.json
./gradlew checkLicense --no-configuration-cache            # the JVM modules, vs config/allowed-licenses-jvm.json (repository root)
```

`.github/workflows/dependency-review.yml` adds a third gate: a deny-list applied to _newly introduced_ dependencies at PR time.

**Do not widen an allow-list to make a build pass.** AGPL, GPL without the Classpath Exception, and source-available licences (SSPL, BUSL, Elastic-2.0) are not
acceptable — see [docs/LEGAL.md §9.2](../docs/LEGAL.md). If a licence genuinely belongs on the list, record why in the policy
file's `_rationale`.

The same guidance is in the [development guide](../docs/DEVELOPMENT.md#licences-and-open-source-notices) for people not reading this file.

## Accessibility

**Moved to the same rule: [.github/instructions/vue.instructions.md](../.github/instructions/vue.instructions.md).** The WCAG 2.1 AA target, the two checks that
enforce it, and why the axe CLI is the wrong tool are all markup concerns, so they load with the markup. `e2e/a11y.spec.ts` is in the rule's globs for the same
reason.

## Screenshots go stale silently

`docs/screenshots/` holds the pictures in the README, and **nothing will tell you when one is wrong** — a screenshot of last year's UI renders exactly as well as
one of today's. Retake after changing `App.vue`, `EventCard.vue`, `EventFilterBar.vue` or the theme tokens in `main.css`, since those are what the shots are
actually of. The procedure and the reasons behind it are in [`docs/screenshots/README.md`](../docs/screenshots/README.md) — three things are easy to get wrong
and all three shipped a worse picture the first time.

**Not on a schedule, and not when the data changes.** The events turn over daily; chasing them would churn the image for reasons unrelated to the product.

## Linting & Formatting

The project uses a two-tier linting strategy:

1. **oxlint** (primary) — fast Rust-based linter with plugins: `eslint`, `typescript`, `unicorn`, `oxc`, `vue`, `vitest`.
   Configured via `.oxlintrc.json`. Runs first with `--fix`.
2. **eslint** (secondary) — catches rules not covered by oxlint. Uses `eslint-plugin-oxlint` to disable rules
   already handled by oxlint (avoids duplicate warnings). Runs second with `--fix --cache`.
3. **oxfmt** — Rust-based formatter for consistent code style. Runs via `npm run format`.

**Important**: Do NOT add Prettier — the project uses oxfmt instead.

### Comments

The rules are the repository's, not the frontend's: see **[.github/instructions/comments.instructions.md](../.github/instructions/comments.instructions.md)** for the reasoning, which is not
repeated here. In TS/Vue terms they come out as:

- **Explain _why_, not _what_.** A `computed` that exists to follow the active locale needs a comment; a `computed` that adds two numbers does not. Self-
  explanatory code needs none at all — rename it or extract it before reaching for prose.
- **Rewrite, never append.** No "used to", no "since #540 it now", no dates. `git blame` and the PR hold that.
- **No `@param foo the foo`.** TypeScript already carries the type and the name; document a parameter only for what the signature cannot say.
- **No commented-out code and no `TODO`s** — deleted code is in git, and work worth remembering is an issue.

**`event-junkie/comment-density` and `event-junkie/comment-smell` enforce the rest** (#713), and both are local rules beside `max-comment-lines`. Density caps a
file at 70% comment once it carries 25 comment lines — a per-comment cap cannot see twenty reasonable comments adding up to prose with code between it. Smell
reports a date, a markdown heading, a comment narrating its own history, or a `TODO`. **A date inside backticks or quotes is left alone**, because a format
example is far more common here than a dated decision. `__tests__`, `e2e/` and the legal module are exempt from Smell, and `src/lib/legal.ts` from Density: a
pinned clock and the date a DPA was concluded are facts about the world, which is exactly what a comment is for. `src/api/schema.d.ts` is generated and exempt
from all three.

**`event-junkie/max-comment-lines` enforces the length half, at 15 lines**, and it counts an unbroken run of `//` lines as one comment. It is a local ESLint
rule in [`eslint-rules/max-comment-lines.ts`](eslint-rules/max-comment-lines.ts), wired up in `eslint.config.ts` — the counterpart to the `:detekt-rules`
Gradle module, which caps Kotlin at 25.

- **It lives on the ESLint side because oxlint cannot host it.** oxlint is Rust and takes no JS plugins, so a custom rule has no home there. That is also why
  `npm run lint` runs both linters rather than one.
- **15, not Kotlin's 25**, because the number came from this tree: of 285 block comments none reached 25 lines, so 25 would never have fired. Change it by
  measuring again, not by taste.
- **The escape hatch is `// eslint-disable-next-line event-junkie/max-comment-lines` with a reason**, the way `@Suppress("LongComment")` is used on the Kotlin
  side. Reach for it before you reach for a bigger `max`.
- It reads comments in `<script>`, not in `<template>`: the parser hands the rule script comments only, and HTML comments in a template are somebody else's
  problem. In practice the prose worth capping lives in the script block.

## Testing

**Moved to a path-scoped rule: [.github/instructions/testing.instructions.md](../.github/instructions/testing.instructions.md).** Vitest and Playwright
conventions load with `e2e/**` and `**/__tests__/**`, beside the backend patterns, rather than for every file in this directory.

## CI/CD

The frontend has its own GitHub Actions workflow (`.github/workflows/build-frontend.yml`) that triggers
only when `events-frontend/**` files change. It performs:

1. Install dependencies (`npm ci`)
2. Lint (`npm run lint`)
3. Build (`npm run build`)
4. Unit test (`npm run test:unit -- --run`)
5. Playwright e2e test (`npm run test:e2e`)

Uses Node 24.

## Key Files

| Purpose                | Path                        |
| ---------------------- | --------------------------- |
| Package config         | `package.json`              |
| Vite config            | `vite.config.ts`            |
| Vitest config          | `vitest.config.ts`          |
| Playwright config      | `playwright.config.ts`      |
| ESLint config          | `eslint.config.ts`          |
| oxlint config          | `.oxlintrc.json`            |
| shadcn-vue config      | `components.json`           |
| Theme & global CSS     | `src/assets/main.css`       |
| shadcn UI components   | `src/components/ui/`        |
| TypeScript root config | `tsconfig.json`             |
| App entry point        | `src/main.ts`               |
| Root component         | `src/App.vue`               |
| Router                 | `src/router/index.ts`       |
| Stores                 | `src/stores/`               |
| Views (pages)          | `src/views/`                |
| Components             | `src/components/`           |
| Unit tests             | `src/components/__tests__/` |
| E2E tests              | `e2e/`                      |
| README screenshots     | `../docs/screenshots/`      |
