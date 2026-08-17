# AGENTS.md

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

### Component Conventions

- **SFC structure order**: `<script setup>` → `<template>` → `<style scoped>`.
- **Component naming**: PascalCase for filenames (`HelloWorld.vue`, `TheWelcome.vue`).
    - Prefix `The` for singleton layout components (e.g. `TheNavbar.vue`, `TheFooter.vue`).
    - Prefix `Base` for presentational/dumb components that wrap HTML elements (e.g. `BaseButton.vue`, `BaseInput.vue`).
    - Views (page-level route components) go in `src/views/` with `*View.vue` suffix.
    - Reusable components go in `src/components/`.
- **Multi-word names** — component names must always be multi-word (`TodoItem`, not `Item`) to avoid conflicts with
  HTML elements. Only the root `App.vue` is exempt.
- **Full words over abbreviations** — prefer `UserProfileOptions.vue` over `UProfOpts.vue`.
- **Props**: Define with `defineProps<T>()` using TypeScript interface syntax. Use `withDefaults()` for default values.
- **Emits**: Define with `defineEmits<T>()` using TypeScript interface syntax.
- **Scoped styles**: Always use `<style scoped>` to prevent style leakage.

### Styling & UI Components (Tailwind v4 + shadcn-vue)

See [ADR-010](../docs/adr/ADR-010_FRONTEND_STYLING_FRAMEWORK.md) for the decision and rationale.

Reference: [Styling with utility classes](https://tailwindcss.com/docs/styling-with-utility-classes).

- **Styling** — use **Tailwind utility classes** in templates. Avoid hand-written CSS; reach for `<style scoped>`
  only when utilities genuinely can't express something (e.g. bridging a third-party library's CSS variables to
  our tokens, as `EventCalendar.vue` does). Global styles and the design tokens live in `src/assets/main.css`.
- **Theming** — the colour/radius/typography tokens are **CSS variables** in `src/assets/main.css`
  (`:root` for light, `.dark` for dark mode). Re-theme by editing those variables — do **not** hardcode hex
  colours in components; use the semantic Tailwind tokens (`bg-background`, `text-foreground`, `bg-primary`,
  `text-muted-foreground`, `border-border`, etc.).
- **No raw palette colours either** — `bg-emerald-500`, `text-slate-600` and friends are as off-limits as hex.
  They don't flip with the theme, so they drag a hand-written `dark:` override along with them and drift from
  the palette. If a semantic token is missing for the meaning you need (success, warning, …), **add the token**
  to `main.css` — both `:root` and `.dark` — and use that.
- **Deliberately unused tokens** — `--font-heading` and the `--chart-*` / `--sidebar-*` sets in `main.css` have
  no references in `src/`. They are shadcn registry defaults, kept so a future `chart`/`sidebar` component
  themes correctly on arrival. Don't "clean them up"; do keep an eye on tokens we added ourselves going unused.
- **Arbitrary values (`[…]`) are a last resort** — in order of preference:
    1. a built-in utility — `grayscale-50`, not `grayscale-[0.5]`; check the docs before bracketing;
    2. a `@theme` token when the value recurs or carries brand meaning — a second sighting of
       `tracking-[0.18em]`/`tracking-[0.2em]` means it should have been `--tracking-eyebrow`;
    3. an arbitrary value, for genuinely one-off values (a decorative blur radius, a hero glow's dimensions).

    Arbitrary **variants** (`[&.router-link-exact-active]:…`, `dark:[color-scheme:dark]`) are fine — the rule is
    about magic _values_.

- **Inline `style` is allowed only** for values utilities can't express: dynamic values from data, or setting a
  CSS variable that utilities then read (`class="bg-(--glow)"`). Not as an escape hatch from writing classes.
- **Never put two conflicting utilities on one element** (`class="grid flex"`) — the winner is decided by
  stylesheet order, not markup order. Branch with a ternary or `cn()` instead.
- **Extract repeated class lists into components, not `@apply`** — the moment the same class list appears a
  third time, it wants to be a component (or a cva variant on an existing one), not a copy-paste. A `v-for`
  over the markup counts as extraction too — the class list appears once either way. Where the shared thing is
  genuinely just a string of classes used by two sibling primitives, an exported constant (`FIELD_CLASS` in
  `@/lib/utils`) beats duplicating it.
  **`@apply` is reserved for the `@layer base` resets in `main.css`** (`*`, `body`). Do not introduce
  `.some-component { @apply … }`: it gives up the utility model, hides the styling from the component that
  owns it, and grows the CSS bundle.
- **Registry primitive vs. `Base*` wrapper** — reach for `npx shadcn-vue@latest add <name>` first, but check
  what it renders. The registry's `Select` (and several others) are Reka UI listboxes, **not** native form
  controls: swapping one in breaks the browser's own dropdown/date picker and every Playwright
  `selectOption`/`fill` in `e2e/`. Where native behaviour matters, write a thin `Base*` wrapper around the real
  element instead — `BaseInput.vue`, `BaseSelect.vue` — carrying the shared classes and letting attributes and
  listeners fall through. `BaseBadge.vue` follows the same shape, with `cva` variants like the shadcn ones.
- **Keep classes in the official Tailwind order** — layout → box model → typography → visual → variants last,
  matching the order the surrounding files already use. This is **not automated**: the official ordering tool is
  a Prettier plugin, and this project uses oxfmt (see "Linting & Formatting" — do not add Prettier). Match the
  neighbouring files by hand. If drift becomes a problem, `eslint-plugin-better-tailwindcss` (peer-compatible
  with our eslint 10 / oxlint 1.x / Tailwind 4.x) has an `enforce-consistent-class-order` rule and would be the
  route to add — no Prettier required.
- **Prefer shorthand** — `py-4` over `pt-4 pb-4`, `flex justify-between` over `flex flex-row justify-between`,
  `border-black/50` over a separate opacity utility.
- **Components** — add shadcn-vue components with `npx shadcn-vue@latest add <name>` (e.g. `button`, `card`,
  `dialog`). They are generated into `src/components/ui/<name>/` and are **owned by us** — edit them freely;
  they are not managed/upgraded by npm. Import via the `@/components/ui/...` alias.
- **Updating a `ui/` component to a newer registry version** — there is no automatic upgrade (we own the
  code). `--overwrite` **replaces the file wholesale; it does not merge**, so use git as the reconciliation
  tool:
    1. `npx shadcn-vue@latest diff <name>` — check whether the registry version differs from ours.
    2. Ensure the component has **no uncommitted changes**, then
       `npx shadcn-vue@latest add <name> --overwrite` to pull the latest.
    3. Review `git diff` to see both the upstream change and anything it clobbered, then reconcile
       (keep upstream, re-apply our customizations, or `git checkout` to revert).

    For a component we have customized, prefer hand-porting the change shown by `diff` instead of overwriting.
    This applies **only** to vendored `src/components/ui/**` components — our own components (e.g.
    `EventCalendar.vue`) are not registry-managed.

- **Vendored `ui/` components: reach for a variant, not a one-off `class`** — they accept a `class` prop (that's
  the shadcn pattern, merged via `cn()`), but using it to invent per-call-site colours or sizes erodes the
  consistency the variants exist to enforce. Add a `variant`/`size` to the component's `cva` config instead.
  A one-off `class` for _layout_ at the call site (margins, `w-full`) is fine — it's appearance that must stay
  in the variants.
- **Class merging** — compose conditional classes with the `cn()` helper from `@/lib/utils`
  (clsx + tailwind-merge), as the generated components do.
- **Icons** — use **`@lucide/vue`** (`import { CalendarDays } from '@lucide/vue'`) for new icons.
- **Accessibility** — shadcn-vue components are built on Reka UI primitives and are accessible by default
  (focus management, ARIA, keyboard nav). Preserve that — don't strip ARIA attributes or `:as`/slot wiring
  when customizing.
- **Naming exemption** — `vue/multi-word-component-names` is disabled for `src/components/ui/**` in
  `eslint.config.ts` because shadcn components use single-word names (`Button`, `Card`) by design. This
  exemption applies **only** to vendored `ui/` components; your own components still follow the multi-word
  rule below.

### Template Conventions (per [Vue Style Guide](https://vuejs.org/style-guide/))

- **Always use `:key` with `v-for`** — required for correct DOM patching and animations.
- **Never combine `v-if` and `v-for`** on the same element — use a computed property to filter, or wrap with `<template v-for>`.
- **Self-closing components** — use `<MyComponent/>` not `<MyComponent></MyComponent>` (in SFCs).
- **PascalCase in templates** — use `<MyComponent/>` not `<my-component/>` in SFC templates.
- **Multi-attribute elements** — when an element has 2+ attributes, put each on its own line.
- **Simple template expressions** — move complex logic into `computed()` properties; templates should describe _what_,
  not _how_.
- **Directive shorthands consistently** — always use `:` (not `v-bind:`), `@` (not `v-on:`), `#` (not `v-slot:`).
- **Prop casing** — camelCase in declarations (`greetingText`), kebab-case when passed in templates (`greeting-text`).

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

**Target: WCAG 2.1 Level AA.** These rules encode what the codebase already does — follow them rather than rediscovering them. Background and the current gap
list: [docs/LEGAL.md §12](../docs/LEGAL.md).

- **Every interactive element needs an accessible name.** Icon-only controls carry an `aria-label`. Where a `title` tooltip is also present, derive both from
  **one** `computed` so they cannot drift — see the theme toggle in `App.vue`.
- **Decorative SVGs get `aria-hidden="true"`** (`PulseMark`, `GitHubMark`). Meaningful images get a real `alt`; `alt=""` is correct only when the image adds
  nothing the surrounding text does not already say.
- **Every view renders exactly one `<main>`.** The detail views inherit theirs from `BaseDetailView` — do not add a second.
- **A heading level belongs to the page, not to the component.** One `h1` per view, and no skipped levels below it. A shared component that renders a heading
  takes it as a prop rather than hard-coding one, and the prop is spelled `as` in all of them: `SectionLabel` defaults to `h2`, `EventCard` and `VenueCard`
  to `h3` (right, under a `SectionLabel`). On a card, `as` sets the _heading_ element, not the card's root. **Placing a card grid straight under the page `h1`
  means passing `as="h2"`** — see the list pages, and the `heading-order` gate in `e2e/a11y.spec.ts`.
- **Do not remove a focus indicator.** `outline-none` is acceptable only when paired with a `focus-visible:` ring, as in `components/ui/button/index.ts`.
- **Prefer a reka-ui / shadcn-vue primitive** over a hand-rolled interactive component. They handle focus management, keyboard interaction and ARIA that a
  bespoke `div` will not.
- **Form controls need a label** — a `<label>` element, or an `aria-label` where the design has no visible label (as in `EventFilterBar.vue`).
- **Write e2e selectors by role and accessible name** (`getByRole('link', { name: … })`). This is the house style _and_ it makes the Playwright suite an
  accessibility regression test.
- **Colour is never the only carrier of meaning** (1.4.1). New colour pairs must clear 4.5:1 for body text and 3:1 for large text and UI boundaries (1.4.3,
  1.4.11).
- **The skip link in `App.vue` must stay the first focusable element** in the document, and `#main-content` must keep its `tabindex="-1"` — that is what makes
  it focusable as a skip target without entering the tab order.
- **`<html lang>` is not decorative, and it is dynamic.** `index.html` ships `lang="en"`; `src/i18n/index.ts` rewrites it on every locale change. Never blank
  it, and never hard-code it back to a literal — axe's `html-has-lang` would still pass while German content announced itself as English.

Further reading: [Vue's own accessibility guide](https://vuejs.org/guide/best-practices/accessibility.html). The rules above already implement its
recommendations — skip link, heading order, landmarks, labelled controls, `aria-hidden` on decorative icons — so read it for the _why_, not as a gap list.
Two of its suggestions are deliberately **not** followed:

- It suggests restoring focus to the top of the document on route change. This app instead announces the new page title into an `aria-live` region (see
  `App.vue`), which tells a screen-reader user _where they are_ rather than only resetting where they are. Moving focus as well would interrupt that
  announcement.
- It prefers `for`/`id` label association over wrapping. Both are valid; the `label-has-for` override in `eslint.config.ts` accepts either, and
  `EventFilterBar.vue` wraps.

### The two checks

**Neither may be silenced to make a build pass** — fix the markup, or raise it:

- **`eslint-plugin-vuejs-accessibility`** (`flat/recommended`), inside `npm run lint` — catches what is visible in the source: missing form labels, bad `alt`,
  redundant roles, click handlers on non-interactive elements.
- **`@axe-core/playwright`**, via `e2e/a11y.spec.ts`, inside `npm run test:e2e` (or `npm run test:a11y` alone) — catches what only exists at runtime: colour
  contrast against the resolved theme tokens, focus order, landmark structure, duplicate IDs.

```bash
npm run test:a11y                          # the axe sweep on its own, all five browser projects
npm run test:a11y -- --project=chromium    # the fast local loop
```

`test:a11y` is a **filter over the same suite**, not a second check — `test:e2e` already includes it, so CI needs nothing extra. It exists so markup work does
not have to pay for the whole e2e run. If axe reports a contrast failure, fix the design token rather than excluding the rule.

The sweep runs in three passes:

1. **Static routes, no BFF** — every static route in both locales, plus a light-theme pass (new visitors get dark, so light is otherwise unexercised).
2. **Data-driven routes, BFF mocked** — home feeds, the events list with its filter bar and pagination, the venues list, an event detail page, and the calendar
   with a populated month grid. Without this pass the components carrying nearly all the interactive markup are never scanned, because an error state renders
   none of them. **If you add a data-driven view, add it here** — the static pass will happily go green on its error state.
3. **`best-practice`, informational** — never fails the build. See below.

**The mock matchers must stay non-overlapping**, which takes a lookahead: `/events` has three sub-resources (`/today`, `/calendar`, `/{slug}`) and a naive
`\/events\/[^/?]+` swallows all three. Playwright consults route handlers in _reverse_ registration order, so an overlap silently picks the handler registered
last — and a feed served an object instead of an array renders an empty state that axe passes happily.

#### The informational `best-practice` pass

axe's `best-practice` rules are recommendations, not WCAG conformance criteria, so they are reported and **never gated**. Gating on them means either fixing
recommendations under deadline or — far more likely — silencing them one at a time until the whole pass is noise. Findings are printed by rule id in the console
and attached to the Playwright report.

One is open today. Fixing it is welcome; **promoting the pass to a gate is not the way to get it fixed**:

| Finding                        | Where       | Note                                                                         |
| ------------------------------ | ----------- | ---------------------------------------------------------------------------- |
| `empty-table-header` (7 nodes) | `/calendar` | FullCalendar's own weekday header cells. Third-party markup we do not write. |

`heading-order` used to sit here too, on `/events` and `/venues`. It is fixed: **a card's heading level is a property of the page, not of the card**, so
`EventCard` and `VenueCard` take an `as` prop (`'h2' | 'h3' | 'h4'`, defaulting to `h3`) — the same name and shape `SectionLabel` already uses. `h3` is right
wherever a `SectionLabel` `h2` sits above the grid — the home page, the detail pages — and the two list pages, which have nothing between their `h1` and the
grid, pass `h2`. **Reuse a card on a new page and you own its level**: if there is no section heading above the grid, pass `as="h2"`.

That fix is pinned by its own narrow gate — `heading-order` alone, on the four list routes — rather than by promoting this pass. The distinction is the point:
a rule that was investigated, fixed, and can now only regress is a gate; a rule nobody has looked at yet is a report. Do the same with the next one, one rule
at a time.

### Why not the axe CLI

`@axe-core/cli` exists, and it is the wrong tool here. It drives a standalone ChromeDriver against a list of URLs, which means: no way to reach a state behind
an interaction (the light theme is behind a button click), no way to mock the BFF (so every data-driven route scans its error state), one browser instead of
five, and a second browser-automation dependency alongside Playwright. The `@axe-core/playwright` integration runs the _same_ axe-core engine with none of
those limits. There is no coverage argument for adding the CLI.

What automation genuinely cannot do is still worth knowing: axe reliably finds roughly a third of WCAG issues. Keyboard-only walkthroughs and a screen-reader
pass remain manual, and are tracked in [docs/LEGAL.md §12](../docs/LEGAL.md).

## Linting & Formatting

The project uses a two-tier linting strategy:

1. **oxlint** (primary) — fast Rust-based linter with plugins: `eslint`, `typescript`, `unicorn`, `oxc`, `vue`, `vitest`.
   Configured via `.oxlintrc.json`. Runs first with `--fix`.
2. **eslint** (secondary) — catches rules not covered by oxlint. Uses `eslint-plugin-oxlint` to disable rules
   already handled by oxlint (avoids duplicate warnings). Runs second with `--fix --cache`.
3. **oxfmt** — Rust-based formatter for consistent code style. Runs via `npm run format`.

**Important**: Do NOT add Prettier — the project uses oxfmt instead.

## Testing

### Unit Tests (Vitest)

- Test files are colocated with components: `src/components/__tests__/*.spec.ts`.
- Uses **jsdom** as the DOM environment.
- Use `@vue/test-utils` for component mounting and interaction.
- Use **`data-testid` attributes** for test selectors — decoupled from CSS classes and DOM structure.
- Test composables in isolation (no component mount needed — just call the function and assert on returned refs).
- Run with: `npm run test:unit` (watch mode) or `npm run test:unit -- --run` (single run).
- Run with coverage: `npm run test:unit:coverage` — prints summary to console and generates HTML report in `coverage/`.

### End-to-End Tests (Playwright)

- Test files live in `e2e/` directory with `*.spec.ts` extension.
- Tests run against **five projects**: Desktop Chromium, Firefox, WebKit, plus **Mobile Chrome (Pixel 5)
  and Mobile Safari (iPhone 12)** — the last two use ~390px viewports.
- Dev mode: runs against `http://localhost:5173` (Vite dev server, reuses existing).
- CI mode: builds first, then runs against `http://localhost:4173` (Vite preview server).
- Run with: `npm run test:e2e`. CI runs the **full matrix**; the `/verify` skill runs **chromium only** to stay fast.
- **Locale strategy: every suite is pinned to `/en` except `e2e/i18n.spec.ts` and the axe sweep.** The other suites are behaviour tests that happen to use
  English accessible names as stable handles; re-running them in German would double an already five-project matrix to re-assert the same behaviour. So put
  anything that only exists in a second language — the URL contract, the switcher, date formats, the per-locale pages — in `i18n.spec.ts`, and leave the rest
  in English.
    - **Two exceptions, both deliberate.** The **axe sweep runs both locales**, because German is reliably longer and that is where a layout overflow or a
      contrast regression actually appears. And **landmark names are translated**, so a selector like `getByRole('navigation', { name: 'Main' })` becomes
      `'Haupt'` under `/de` — which is the concrete reason the other suites stay on `/en` rather than a stylistic one.
- **Layout/responsive gotcha:** because `/verify` is chromium-only (desktop viewport), it will not catch
  regressions that only appear on the mobile projects — e.g. a wider header nav overflowing a ~390px screen
  and pushing a control off-screen (a real failure we hit). When touching the **app shell, header/nav, or any
  layout**, run the mobile projects locally before pushing:
  `npm run test:e2e -- --project="Mobile Chrome" --project="Mobile Safari"`. On CI such a break also _slows_
  the run — a failing interaction burns the 30s action timeout × 2 retries × 5 projects.

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
