---
applyTo: "events-frontend/**/*.vue,events-frontend/**/*.css,events-frontend/e2e/a11y.spec.ts"
paths:
    - "events-frontend/**/*.vue"
    - "events-frontend/**/*.css"
    - "events-frontend/e2e/a11y.spec.ts"
---

# Vue Components: Structure, Styling and Accessibility

What a single-file component looks like here, and the accessibility it has to keep. The rest of the frontend is [events-frontend/AGENTS.md](../../events-frontend/AGENTS.md);
comment rules are [comments](comments.instructions.md).

## Component conventions

`<script setup lang="ts">` → `<template>` → `<style scoped>`. PascalCase multi-word filenames (`TodoItem`, never `Item`; only `App.vue` is exempt), `The*` for
singleton layout, `Base*` for wrappers around HTML elements, `*View.vue` under `src/views/`. `defineProps<T>()` with `withDefaults()`, `defineEmits<T>()`.
Templates per the [Vue Style Guide](https://vuejs.org/style-guide/): `:key` on every `v-for`, never `v-if` with `v-for` on one element, self-closing PascalCase
components, one attribute per line past two, logic in `computed()`, `:`/`@`/`#` shorthands, camelCase props passed as kebab-case. The
`vue/multi-word-component-names` exemption in `eslint.config.ts` covers `src/components/ui/**` only.

## Styling (Tailwind v4 + shadcn-vue, ADR-010)

- **Tailwind utilities in templates; `<style scoped>` only where utilities cannot express it** (bridging a third-party library's variables to our tokens, as
  `EventCalendar.vue` does). Tokens are CSS variables in `src/assets/main.css` (`:root` light, `.dark` dark).
- **No hex, and no raw palette colours** (`bg-emerald-500`, `text-slate-600`): they do not flip with the theme and drag a `dark:` override along. Use the
  semantic tokens (`bg-background`, `text-muted-foreground`, `border-border`); a missing meaning (success, warning) gets a **new token in both `:root` and
  `.dark`**. `--font-heading` and the `--chart-*` / `--sidebar-*` sets are unused shadcn registry defaults, kept on purpose.
- **Arbitrary values `[…]` are a last resort**: a built-in utility first (`grayscale-50`, not `grayscale-[0.5]`), a `@theme` token when the value recurs or
  carries brand meaning (a second `tracking-[0.18em]` should have been `--tracking-eyebrow`), an arbitrary value only for a true one-off. Arbitrary
  _variants_ are fine. Inline `style` only for data-driven values or a CSS variable utilities then read (`class="bg-(--glow)"`).
- **Never two conflicting utilities on one element** (`grid flex`) — stylesheet order decides, not markup order; branch with a ternary or `cn()`.
- **A class list seen a third time is a component or a `cva` variant, not `@apply`.** `@apply` is reserved for the `@layer base` resets in `main.css`. Two
  sibling primitives sharing a string can share an exported constant (`FIELD_CLASS` in `@/lib/utils`).
- **Registry primitive versus `Base*` wrapper**: `npx shadcn-vue@latest add <name>` first, but check what it renders — the registry's `Select` and others are
  Reka UI listboxes, not native controls, and swapping one in breaks the browser's own picker and every Playwright `selectOption`/`fill`. Where native
  behaviour matters, a thin `Base*` around the real element (`BaseInput.vue`, `BaseSelect.vue`), attributes falling through.
- **`src/components/ui/` is vendored and owned**; edit freely, npm does not manage it. To take a newer registry version: `npx shadcn-vue@latest diff <name>`,
  commit first, `add <name> --overwrite` (**replaces wholesale, no merge**), reconcile in `git diff`. A customised component is hand-ported instead.
  Appearance goes into the component's `cva` variants, never a per-call-site `class`; `class` at the call site is for layout (`w-full`, margins).
  Preserve the Reka UI accessibility — do not strip ARIA or `:as`/slot wiring.
- **Class order is the official Tailwind order** (layout → box model → typography → visual → variants) **by hand** — the ordering tool is a Prettier plugin and
  this project uses oxfmt; `eslint-plugin-better-tailwindcss` would be the route if drift becomes a problem. Prefer shorthand (`py-4`, `border-black/50`).
  `cn()` from `@/lib/utils` for conditional classes; `@lucide/vue` for icons.

## Accessibility

**Target: WCAG 2.1 Level AA.** Background and the open gaps: [docs/LEGAL.md §12](../../docs/LEGAL.md). Vue's own accessibility guide is implemented here;
two of its suggestions are deliberately not: focus is not moved to the top on route change (the new title is announced into an `aria-live` region in
`App.vue`, and moving focus would interrupt it), and labels may wrap rather than use `for`/`id` (`label-has-for` accepts either).

- **Every interactive element has an accessible name.** Icon-only controls carry `aria-label`; where a `title` tooltip exists too, derive both from one
  `computed` (the theme toggle in `App.vue`).
- **Decorative SVGs get `aria-hidden="true"`** (`EjBadge`, `ClubStamp`, `GitHubMark`) — the brand marks are decorative even though they spell the name,
  because the adjacent text carries it, and two accessible names for one thing is worse than none. Meaningful images get a real `alt`.
- **Exactly one `<main>` per view** (detail views inherit it from `BaseDetailView`), **one `h1`, no skipped levels.** The home hero's `h1` is `sr-only` and
  **still counts** — do not delete it. A shared component renders its heading from an `as` prop: `SectionLabel` defaults to `h2`, `EventCard` and `VenueCard`
  to `h3`. **A card grid straight under the page `h1` gets `as="h2"`**; the `heading-order` gate in `e2e/a11y.spec.ts` on the four list routes is what
  pins that — a card's heading level is a property of the page, not the card.
- **Never remove a focus indicator**; `outline-none` only with a `focus-visible:` ring. **Prefer a reka-ui / shadcn-vue primitive** over a hand-rolled
  interactive `div`. **Form controls need a label** (`<label>`, or `aria-label` where the design has none, as in `EventFilterBar.vue`).
- **e2e selectors by role and accessible name** (`getByRole('link', { name: … })`), which makes the Playwright suite an accessibility regression test.
- **Colour is never the only carrier of meaning**; 4.5:1 body, 3:1 large text and UI boundaries.
- **The skip link in `App.vue` stays the first focusable element**, and `#main-content` keeps `tabindex="-1"`.
- **`<html lang>` is dynamic**: `index.html` ships `en`, `src/i18n/index.ts` rewrites it per locale. Never blank it or hard-code it — axe's `html-has-lang`
  passes while German announces itself as English.

### The two checks — neither may be silenced to make a build pass

- **`eslint-plugin-vuejs-accessibility`** (`flat/recommended`) in `npm run lint` — what is visible in the source.
- **`@axe-core/playwright`** via `e2e/a11y.spec.ts` in `npm run test:e2e`; `npm run test:a11y` (`-- --project=chromium` for the fast loop) is a **filter over
  the same suite**, so CI needs nothing extra. A contrast failure is fixed in the token, never by excluding the rule.

Three passes: **static routes, no BFF**, both locales plus a light-theme pass; **data-driven routes, BFF mocked** — home feeds, events list with filter bar and
pagination, venues, an event detail, the calendar grid — because an error state renders none of the interactive markup, so **a new data-driven view is added
here** or the static pass goes green on its error state; **`best-practice`, informational** — recommendations, never gated, printed by rule id. Open today:
`empty-table-header` on `/calendar`, FullCalendar's own header cells. A rule that was investigated and fixed becomes its own narrow gate (as `heading-order`
did); a rule nobody has looked at stays a report. **The mock matchers must not overlap** — `/events` has `/today`, `/calendar` and `/{slug}`, Playwright
consults handlers in reverse registration order, and a feed served an object renders an empty state axe passes happily.

**Not the axe CLI**: it drives ChromeDriver against URLs, so no state behind an interaction, no BFF mock, one browser, a second automation dependency.
`@axe-core/playwright` is the same engine without those limits. axe finds roughly a third of WCAG issues; keyboard and screen-reader passes stay manual
(LEGAL.md §12).
