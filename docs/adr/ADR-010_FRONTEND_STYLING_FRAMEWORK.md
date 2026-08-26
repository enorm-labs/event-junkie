# ADR-010: Frontend Styling & Component Framework

## Status

**Accepted — Option A: Tailwind CSS v4 + shadcn-vue**, in use in `events-frontend/`.

> The event-calendar library was a separate sub-decision, settled by [ADR-011](ADR-011_CALENDAR_LIBRARY.md).

## Context

> **Pinia is no longer in the stack**, because nothing used it. The Context below describes the stack as it stood when this decision was made, and the
> styling decision does not depend on Pinia either way.

The frontend (`events-frontend/`) is a **Vue 3.5 + Vite 8 + Pinia + vue-router + TypeScript** SPA. It currently has **no CSS framework and no component
library** — styling is a clean slate. We want:

- A styling approach that is productive and consistent.
- A "nice" default look that can be **re-themed easily** (colours, radius, typography, dark mode).
- Components suited to an events application: data tables/lists, date pickers, dialogs/modals, forms, cards, filters.

Two largely orthogonal decisions are bundled here:

1. **Styling layer** — how we author styles (utility CSS vs. component-scoped CSS vs. CSS-in-framework).
2. **Component layer** — whether we adopt a component library and which one.

### Candidate options

#### Option A — Tailwind CSS v4 + shadcn-vue

- **What**: Tailwind v4 — utility-first CSS, near-zero config with the Vite plugin — plus
  [shadcn-vue](https://www.shadcn-vue.com). Components are **copied into the repo and owned by us**, built on Reka UI primitives and Tailwind.
- **Theming**: driven by CSS variables, so a large ecosystem of ready-made themes such as tweakcn is compatible. Dark mode and design tokens are first-class.
- **Pros**: maximum control and re-theming flexibility. No library version lock-in, because we own the code. Excellent accessibility through Reka UI, and
  small, tree-shakeable output.
- **Cons**: more assembly per component, and more upfront wiring. Richer widgets such as a full data table or a calendar are composed by us, or pulled from
  extensions.

#### Option B — Tailwind CSS v4 + PrimeVue

- **What**: Tailwind for layout/utilities plus [PrimeVue](https://primevue.org) — a large batteries-included component library (DataTable, Calendar/DatePicker,
  Dialog, forms, etc.).
- **Theming**: built-in styled-mode theme presets such as Aura, plus an online theme designer. It can also run unstyled and lean on Tailwind.
- **Pros**: the richest out-of-the-box component set, and the fastest path to a working events UI. DataTable and the date components are strong, and the docs
  are good.
- **Cons**: a larger dependency. Theming is powerful, and more framework-specific than pure CSS variables. Two styling systems, the PrimeVue theme and
  Tailwind, have to be kept coherent.

#### Option C — Vuetify (Material Design)

- **What**: [Vuetify](https://vuetifyjs.com) — an all-in-one Material Design component framework.
- **Theming**: Built-in Material theme system (light/dark, named colours).
- **Pros**: opinionated and complete, with a huge component set and a consistent Material look for little effort.
- **Cons**: a heavier bundle. The Material aesthetic is harder to restyle into something distinctive, so it is less easily re-themed if we want to stray from
  Material.

#### Option D — Tailwind CSS v4 only (no component library)

- **What**: Tailwind alone. We build every component ourselves.
- **Theming**: full control, through CSS variables and the Tailwind theme config.
- **Pros**: maximum control, minimal dependencies, the smallest footprint.
- **Cons**: the most upfront work. We re-implement accessible dialogs, date pickers and tables ourselves.

### Comparison summary

| Criterion             | A: Tailwind + shadcn-vue | B: Tailwind + PrimeVue  | C: Vuetify      | D: Tailwind only |
| --------------------- | ------------------------ | ----------------------- | --------------- | ---------------- |
| Re-theming ease       | Excellent (CSS vars)     | Good (presets/designer) | Good (Material) | Excellent        |
| Out-of-box components | Medium (own/extend)      | High                    | High            | None             |
| Control / ownership   | High (we own code)       | Medium                  | Low             | Highest          |
| Bundle footprint      | Small                    | Medium                  | Larger          | Smallest         |
| Upfront effort        | Medium                   | Low                     | Low             | High             |
| Distinctive look      | Easy                     | Medium                  | Hard (Material) | Easy             |

## Criteria

The frontend is the public face of a **Berlin music-events guide** (concerts, club nights, festivals across Berlin venues —
see [VISION_ROADMAP_IDEAS.md](../VISION_ROADMAP_IDEAS.md)). Prioritised criteria:

1. **Simple and clean** — a "pretty simple website". The design should stay uncluttered, not a heavy enterprise UI kit.
2. **User-friendly** — easy browsing of events, with sensible defaults.
3. **Accessible** — keyboard navigation, focus management, ARIA, colour contrast as first-class concerns.
4. **Customizable** — easy re-theming (colours, radius, typography, dark mode) without fighting the library.
5. **Calendar page is central.** A month and week **event calendar**, showing events on days, is a key screen rather than a date-picker. Export to iCal and
   Google Calendar is a later goal.
6. **Public listing app** — it needs cards, lists, filters, dialogs and forms. Rich data grids and heavy admin widgets are _not_ a priority for a public site.
7. **Popularity** — a widely adopted option, for lower risk of abandonment, more examples and easier help.
8. **Community & support** — active maintenance, responsive issues, large user base.
9. **Documentation** — thorough, well-organised docs with examples.
10. **Lightweight** — a small baseline footprint. Ship only what we use.

### Popularity / support / lightweightness — data (June 2026)

Weekly npm downloads and bundle characteristics, used to score criteria 7–10:

| Library              | Weekly npm downloads | Footprint notes                                             |
| -------------------- | -------------------- | ----------------------------------------------------------- |
| Tailwind CSS         | ~121 M               | Compiles to only the utility classes used — minimal CSS.    |
| reka-ui (shadcn-vue) | ~1.33 M              | Tree-shaken per primitive; ship only imported components.   |
| Vuetify              | ~975 K               | Tree-shakeable, but heavier baseline than the alternatives. |
| PrimeVue             | ~675 K               | Tree-shaken per component; theme runtime adds baseline.     |
| @fullcalendar/vue3   | ~192 K               | Calendar option — larger, feature-rich.                     |
| shadcn-vue (CLI)     | ~94 K                | CLI only; real runtime usage is reka-ui (above).            |
| vue-cal              | ~39 K                | Calendar option — small and light, fewer features.          |

Four notes on reading that table.

- **shadcn-vue's download count understates adoption**, because components are copied into the repo. The meaningful runtime signal is **reka-ui (~1.33 M)**,
  the most-downloaded of the Vue component runtimes here.
- **Full-package bundle sizes are misleading.** PrimeVue is ~356 KB gzip and reka-ui ~157 KB, and all three libraries are tree-shakeable. Only the imported
  components ship.
- **Documentation**: PrimeVue and Vuetify have the most extensive first-party docs. The shadcn-vue docs are good, and backed by the very large shadcn/ui
  ecosystem of themes and examples, which ports directly.
- **For the calendar**, FullCalendar (~192 K) has a notably larger community than vue-cal (~39 K), so popularity favours FullCalendar.

### Note: the event-calendar component is a separate sub-decision

None of the component libraries below ships a full **event calendar**, a month grid with events on days. They provide date _pickers_. The calendar page
therefore needs a dedicated library or a custom build, evaluated independently of the component-library choice:

- **FullCalendar** (Vue 3 wrapper) — feature-rich, with month, week and list views, drag and recurring events, themeable via CSS variables. It brings its own
  styling, which has to be aligned with our theme.
- **vue-cal** — a lightweight, Vue-3-native event calendar. Simpler and easier to restyle, with fewer features.
- **Custom** — a month grid built from a date library, `@internationalized/date` or `date-fns`. Maximum control, and the most effort.

This is recorded in [ADR-011](ADR-011_CALENDAR_LIBRARY.md).

## Decision

**Adopted: Option A — Tailwind CSS v4 + shadcn-vue**, paired with a dedicated event-calendar library for the calendar page. That library is
[ADR-011](ADR-011_CALENDAR_LIBRARY.md).

Rationale, scored against the requirements:

| Requirement        | Weight | A: shadcn-vue   | B: PrimeVue  | C: Vuetify        | D: Tailwind only  |
| ------------------ | ------ | --------------- | ------------ | ----------------- | ----------------- |
| Simple & clean     | High   | ✅ Excellent    | 🟡 Good      | 🟡 Material-heavy | ✅ Excellent      |
| Accessible         | High   | ✅ Reka UI      | 🟡 Good      | 🟡 Good           | ❌ DIY (risky)    |
| Customizable       | High   | ✅ CSS vars     | 🟡 Presets   | ❌ Material       | ✅ Full           |
| User-friendly look | Med    | ✅              | ✅           | ✅                | 🟡 We build it    |
| Out-of-box parts   | Med    | 🟡 Compose      | ✅ Rich      | ✅ Rich           | ❌ None           |
| Small/simple site  | Med    | ✅              | 🟡 Heavier   | ❌ Heaviest       | ✅                |
| Popularity         | Med    | ✅ reka 1.33M   | 🟡 675K      | ✅ 975K           | ✅ TW 121M        |
| Community/support  | Med    | ✅ + shadcn eco | ✅ Active    | ✅ Active         | ✅ Huge (TW)      |
| Documentation      | Med    | 🟡 Good         | ✅ Extensive | ✅ Extensive      | ✅ Excellent (TW) |
| Lightweight        | High   | ✅ Minimal base | 🟡 Theme rt  | ❌ Heaviest       | ✅ Smallest       |

Option A wins on the three High-weight criteria that match this project's stated goals: clean, accessible, customizable. Its components are accessible by
default through the Reka UI primitives, and re-themed purely through CSS variables. That satisfies "customizable" without library lock-in. We own
the component code, so the surface stays as small as a simple site needs.

The three it beat:

- **PrimeVue (B)** was the main alternative. It offers more out of the box, and its richness is aimed at the data-heavy admin UIs a public listing site does
  not need. Its theming is also more framework-specific.
- **Vuetify (C)** imposes a Material look that conflicts with customizable, distinctive and simple.
- **Plain Tailwind (D)** risks accessibility regressions, because we would hand-roll dialogs, menus and focus management.

Option A scores well on the added criteria too — popularity, community, docs and weight. Tailwind is among the most-used packages on npm. reka-ui (~1.33 M weekly) is the most-downloaded
of the Vue component runtimes considered. Popularity and community are therefore strong. Its baseline is the lightest of the component
options, being compiled-only CSS plus per-primitive imports.

Its **one relative weakness is documentation**. shadcn-vue's first-party docs are good, and less exhaustive than PrimeVue's or Vuetify's. The large shadcn/ui
ecosystem of themes and examples ports directly and mitigates that, and the weakness is not decisive against three High-weight wins.

## Consequences

**Positive**

- Components are accessible by default.
- Theming via CSS variables makes dark mode and brand changes trivial.
- A minimal dependency surface suits a simple public site.
- We own the component code, and can trim it.

**Negative**

- More upfront assembly than a batteries-included kit.
- Some richer widgets have to be composed, or pulled from community extensions.

**Calendar.** The central calendar page depends on a separate library decision, which is
[ADR-011](ADR-011_CALENDAR_LIBRARY.md). Its styling has to align to the shadcn and Tailwind theme tokens.

**Testing.** Components are plain Vue SFCs, so the existing Vitest and `@vue/test-utils` setup applies. No new test tooling is required.

### As built

Installed in `events-frontend/` per the
[shadcn-vue Vite guide](https://www.shadcn-vue.com/docs/installation/vite):

- **Dependencies**: `tailwindcss` and `@tailwindcss/vite` (v4). `shadcn-vue@2.7.4 init` pulled in `reka-ui`, `@lucide/vue`,
  `class-variance-authority`, `clsx`, `tailwind-merge` and `tw-animate-css`.
- **Config**:
    - `vite.config.ts` — the `@tailwindcss/vite` plugin, alongside the existing `vue` and `vue-devtools`
      plugins and the `/api` → `:8080` proxy. The existing `@` → `./src` alias is reused.
    - `src/assets/main.css` — `@import 'tailwindcss'` plus the shadcn theme: light and dark CSS-variable tokens (`oklch`), `@theme inline` token mapping,
      and a base layer. There is no `base.css`.
    - `components.json` — style `reka-nova`, base color `neutral`, icon library `lucide`, CSS variables on.
    - `src/lib/utils.ts` — the shadcn `cn()` helper.
    - **TypeScript**: `paths` (`@/*`) in `tsconfig.json`. The shadcn guide's `baseUrl` is **omitted**, because it is deprecated in this project's
      TypeScript 6.x. `paths` resolves without it.
    - **ESLint**: `src/components/ui/**` is exempted from `vue/multi-word-component-names` because shadcn components are vendored and single-word by design.
- **Conventions**:
    - Add components with `npx shadcn-vue@latest add <name>`. They land in `src/components/ui/<name>/`
      and are **owned by us**: edit them freely, and no package manager upgrades them.
    - Re-theme by editing the CSS-variable tokens in `src/assets/main.css`. Dark mode comes from the `.dark` class.
- **Verified**: `type-check`, `build` (CSS ~4.3 KB gzip), `lint` and `vitest run` all pass.
- **No Vue scaffolding remains.** `HelloWorld`, `TheWelcome`, `WelcomeItem`, the welcome icons, the unused `counter` store, `base.css` and `logo.svg` are all
  gone. `App.vue` and `HomeView.vue` are built with Tailwind and the shadcn `Button`, and the suite carries a `Button` smoke test in place of `HelloWorld`'s.

## References

- [Vue 3 documentation](https://vuejs.org)
- [Tailwind CSS v4](https://tailwindcss.com)
- [shadcn-vue](https://www.shadcn-vue.com)
- [PrimeVue](https://primevue.org)
- [Vuetify](https://vuetifyjs.com)
- [Reka UI (primitives behind shadcn-vue)](https://reka-ui.com)
