---
applyTo: "events-frontend/**/*.vue,events-frontend/**/*.css,events-frontend/e2e/a11y.spec.ts"
paths:
    - "events-frontend/**/*.vue"
    - "events-frontend/**/*.css"
    - "events-frontend/e2e/a11y.spec.ts"
---

# Vue Components: Structure, Styling and Accessibility

What a single-file component looks like in this project, and the accessibility it has to keep. The rest of the frontend — composables, reactivity, routing, the
API client, localisation and the build — is in [events-frontend/AGENTS.md](../../events-frontend/AGENTS.md), which stays loaded for the whole subtree. Comment
rules are in [comments](comments.instructions.md), and apply here too.

## Component Conventions

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

## Styling & UI Components (Tailwind v4 + shadcn-vue)

See [ADR-010](../../docs/adr/ADR-010_FRONTEND_STYLING_FRAMEWORK.md) for the decision and rationale.

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

## Template Conventions (per [Vue Style Guide](https://vuejs.org/style-guide/))

- **Always use `:key` with `v-for`** — required for correct DOM patching and animations.
- **Never combine `v-if` and `v-for`** on the same element — use a computed property to filter, or wrap with `<template v-for>`.
- **Self-closing components** — use `<MyComponent/>` not `<MyComponent></MyComponent>` (in SFCs).
- **PascalCase in templates** — use `<MyComponent/>` not `<my-component/>` in SFC templates.
- **Multi-attribute elements** — when an element has 2+ attributes, put each on its own line.
- **Simple template expressions** — move complex logic into `computed()` properties; templates should describe _what_,
  not _how_.
- **Directive shorthands consistently** — always use `:` (not `v-bind:`), `@` (not `v-on:`), `#` (not `v-slot:`).
- **Prop casing** — camelCase in declarations (`greetingText`), kebab-case when passed in templates (`greeting-text`).

## Accessibility

**Target: WCAG 2.1 Level AA.** These rules encode what the codebase already does — follow them rather than rediscovering them. Background and the current gap
list: [docs/LEGAL.md §12](../../docs/LEGAL.md).

- **Every interactive element needs an accessible name.** Icon-only controls carry an `aria-label`. Where a `title` tooltip is also present, derive both from
  **one** `computed` so they cannot drift — see the theme toggle in `App.vue`.
- **Decorative SVGs get `aria-hidden="true"`** (`EjBadge`, `ClubStamp`, `GitHubMark`). Meaningful images get a real `alt`; `alt=""` is correct only when the
  image adds nothing the surrounding text does not already say. The brand SVGs are decorative even though they spell the product name, because the adjacent
  text — the wordmark in `BrandLogo`, the `sr-only` `h1` in the hero — already carries it; two accessible names for one thing is worse than none.
- **Every view renders exactly one `<main>`.** The detail views inherit theirs from `BaseDetailView` — do not add a second.
- **A heading level belongs to the page, not to the component.** One `h1` per view, and no skipped levels below it. **A hidden `h1` still counts** — the home
  hero renders its `h1` as `sr-only` because the club stamp carries the name as artwork, and the live text has to survive for search and screen readers.
  Do not delete it as redundant; the a11y heading-outline check in `e2e/a11y.spec.ts` is what proves the outline is intact. A shared component that renders a heading
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
pass remain manual, and are tracked in [docs/LEGAL.md §12](../../docs/LEGAL.md).
