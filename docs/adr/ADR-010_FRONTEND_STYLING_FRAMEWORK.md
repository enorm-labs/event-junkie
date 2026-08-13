# ADR-010: Frontend Styling & Component Framework

## Status

Accepted (2026-06-18)

> Adopted **Option A — Tailwind CSS v4 + shadcn-vue**. Set up in `events-frontend/` on 2026-06-18.
> The event-calendar library remains a separate, still-open sub-decision (see the note under
> [Requirements](#requirements)).

## Context

> **Note (2026-06-30):** Pinia was removed from the stack as it was unused. The styling decision
> below is unaffected; the stack description here reflects the state at the time of this ADR.

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

- **What**: Tailwind v4 (utility-first CSS, near-zero config with the Vite plugin) plus
  [shadcn-vue](https://www.shadcn-vue.com) — components are **copied into the repo and owned by us**
  (built on Reka UI primitives + Tailwind).
- **Theming**: Driven by CSS variables; large ecosystem of ready-made themes (e.g. tweakcn) is compatible. Dark mode and design tokens are first-class.
- **Pros**: Maximum control and re-theming flexibility; no library version lock-in (we own the code); excellent accessibility via Reka UI; small, tree-shakeable
  output.
- **Cons**: More assembly per component; richer widgets (full data table, calendar) are composed by us or pulled from extensions; more upfront wiring.

#### Option B — Tailwind CSS v4 + PrimeVue

- **What**: Tailwind for layout/utilities plus [PrimeVue](https://primevue.org) — a large batteries-included component library (DataTable, Calendar/DatePicker,
  Dialog, forms, etc.).
- **Theming**: Built-in styled-mode theme presets (Aura, etc.) + an online theme designer; can also run unstyled and lean on Tailwind.
- **Pros**: Richest out-of-the-box component set — fastest path to a working events UI (DataTable and date components are strong); good docs.
- **Cons**: Larger dependency; theming is powerful but more framework-specific than pure CSS variables; two styling systems (PrimeVue theme + Tailwind) to keep
  coherent.

#### Option C — Vuetify (Material Design)

- **What**: [Vuetify](https://vuetifyjs.com) — an all-in-one Material Design component framework.
- **Theming**: Built-in Material theme system (light/dark, named colours).
- **Pros**: Opinionated and complete; consistent Material look with little effort; huge component set.
- **Cons**: Heavier bundle; Material aesthetic is harder to restyle into a distinctive non-Material look; less "easily re-themed" if we want to stray from
  Material.

#### Option D — Tailwind CSS v4 only (no component library)

- **What**: Just Tailwind; we build all components ourselves.
- **Theming**: Full control via CSS variables / Tailwind theme config.
- **Pros**: Maximum control, minimal dependencies, smallest footprint.
- **Cons**: Most upfront work; we re-implement accessible dialogs, date pickers, tables, etc. ourselves.

### Comparison summary

| Criterion             | A: Tailwind + shadcn-vue | B: Tailwind + PrimeVue  | C: Vuetify      | D: Tailwind only |
| --------------------- | ------------------------ | ----------------------- | --------------- | ---------------- |
| Re-theming ease       | Excellent (CSS vars)     | Good (presets/designer) | Good (Material) | Excellent        |
| Out-of-box components | Medium (own/extend)      | High                    | High            | None             |
| Control / ownership   | High (we own code)       | Medium                  | Low             | Highest          |
| Bundle footprint      | Small                    | Medium                  | Larger          | Smallest         |
| Upfront effort        | Medium                   | Low                     | Low             | High             |
| Distinctive look      | Easy                     | Medium                  | Hard (Material) | Easy             |

## Requirements

The frontend is the public face of a **Berlin music-events guide** (concerts, club nights, festivals across Berlin venues —
see [VISION_ROADMAP_IDEAS.md](../VISION_ROADMAP_IDEAS.md)). Prioritised criteria:

1. **Simple & clean** — a "pretty simple website"; the design should stay uncluttered, not a heavy enterprise UI kit.
2. **User-friendly** — easy browsing of events; sensible defaults.
3. **Accessible** — keyboard navigation, focus management, ARIA, colour contrast as first-class concerns.
4. **Customizable** — easy re-theming (colours, radius, typography, dark mode) without fighting the library.
5. **Calendar page is central** — a month/week **event calendar** (events shown on days) is a key screen, not just a date-picker. iCal/Google Calendar export is
   a later goal.
6. **Public listing app** — needs cards/lists, filters, dialogs/modals, forms; rich data grids and heavy admin widgets are _not_ a priority for the public site.
7. **Popularity** — a widely-adopted option (lower risk of abandonment, more examples, easier hiring/AI help).
8. **Community & support** — active maintenance, responsive issues, large user base.
9. **Documentation** — thorough, well-organised docs with examples.
10. **Lightweight** — small baseline footprint; ship only what we use.

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

Notes: shadcn-vue's download count understates adoption because components are copied into the repo — the meaningful runtime signal is **reka-ui (~1.33 M)**,
the most-downloaded of the Vue component runtimes here. Full-package bundle sizes (e.g. PrimeVue ~356 KB gzip, reka-ui ~157 KB gzip) are misleading because all
three libraries are tree-shakeable; only the imported components ship. **Documentation**: PrimeVue and Vuetify have the most extensive first-party docs;
shadcn-vue's docs are good and backed by the very large shadcn/ui ecosystem (themes, examples) that ports directly. For the **calendar**, FullCalendar (~192 K)
has a notably larger community than vue-cal (~39 K) — popularity favours FullCalendar there.

### Note: the event-calendar component is a separate sub-decision

None of the component libraries below ship a full **event calendar** (month grid with events on days); they provide date _pickers_. The calendar page will need
a dedicated library or a custom build, evaluated independently of the component-library choice:

- **FullCalendar** (Vue 3 wrapper) — feature-rich (month/week/list views, drag, recurring), themeable via CSS variables; brings its own styling that must be
  aligned with our theme.
- **vue-cal** — lightweight, Vue-3-native event calendar; simpler and easier to restyle, fewer features.
- **Custom** — build a month grid from a date library (`@internationalized/date` / `date-fns`); maximum control, most effort.

This is recorded in [ADR-011](ADR-011_CALENDAR_LIBRARY.md).

## Decision

**Adopted: Option A — Tailwind CSS v4 + shadcn-vue**, to be paired with a dedicated event-calendar library (leaning **vue-cal** for simplicity, FullCalendar if
we need week/recurring views) for the calendar page — the calendar library itself is still an open sub-decision.

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

Option A wins on the three High-weight criteria (clean, accessible, customizable) that match this project's stated goals. shadcn-vue's components are accessible
by default (Reka UI primitives) and re-themed purely through CSS variables, which directly satisfies "customizable" without library lock-in. We own the
component code, so the surface stays as small as a simple site needs. PrimeVue (B) was the main alternative — it offers more out of the box, but its richness is
aimed at data-heavy admin UIs we don't need for a public listing site, and its theming is more framework-specific. Vuetify (C) imposes a Material look that
conflicts with "customizable/distinctive + simple." Plain Tailwind (D) risks accessibility regressions because we'd hand-roll dialogs, menus, and focus
management.

On the added criteria (popularity, community, docs, lightweight): Option A also scores well. Tailwind is among the most-used packages on npm and reka-ui (~1.33
M weekly) is the most-downloaded of the Vue component runtimes considered, so popularity and community are strong. Its baseline is the lightest of the component
options (compiled-only CSS + per-primitive imports). Its **one relative weakness is documentation** — shadcn-vue's first-party docs are good but less exhaustive
than PrimeVue's or Vuetify's; this is mitigated by the large shadcn/ui ecosystem (themes, examples, AI familiarity) that ports directly. This weakness is not
decisive given the three High-weight wins.

## Consequences

- **Positive**: Accessible-by-default components; theming via CSS variables makes dark mode and brand changes trivial; minimal dependency surface suits a simple
  public site; we own and can trim component code.
- **Negative**: More upfront assembly than a batteries-included kit; some richer widgets must be composed or pulled from community extensions.
- **Calendar**: The central calendar page depends on a separate library decision (vue-cal vs FullCalendar vs custom); its styling must be aligned to the
  shadcn/Tailwind theme tokens.
- **Testing**: Components are plain Vue SFCs — existing Vitest + `@vue/test-utils` setup applies; no new test tooling required.

### As-built setup (2026-06-18)

Installed in `events-frontend/` per the
[shadcn-vue Vite guide](https://www.shadcn-vue.com/docs/installation/vite):

- **Dependencies**: `tailwindcss` + `@tailwindcss/vite` (v4); `shadcn-vue@2.7.4 init` pulled in `reka-ui`,
  `@lucide/vue`, `class-variance-authority`, `clsx`, `tailwind-merge`, and `tw-animate-css`.
- **Config**:
    - `vite.config.ts` — added the `@tailwindcss/vite` plugin (kept the existing `vue`, `vue-devtools`
      plugins and the `/api` → `:8080` proxy; the existing `@` → `./src` alias was reused).
    - `src/assets/main.css` — replaced the Vue scaffolding styles with `@import 'tailwindcss'` plus the shadcn theme: light/dark CSS-variable tokens (`oklch`),
      `@theme inline` token mapping, and a base layer. `base.css` is no longer imported.
    - `components.json` — style `reka-nova`, base color `neutral`, icon library `lucide`, CSS variables on.
    - `src/lib/utils.ts` — the shadcn `cn()` helper.
    - **TypeScript**: added `paths` (`@/*`) to `tsconfig.json`. Note the shadcn guide's `baseUrl` is **omitted** — it is deprecated in this project's TypeScript
      6.x; `paths` resolves without it.
    - **ESLint**: `src/components/ui/**` is exempted from `vue/multi-word-component-names` because shadcn components are vendored and single-word by design.
- **Conventions**:
    - Add components with `npx shadcn-vue@latest add <name>` — they land in `src/components/ui/<name>/`
      and are **owned by us** (edit freely; not upgraded by a package manager).
    - Re-theme by editing the CSS-variable tokens in `src/assets/main.css`; dark mode via the `.dark` class.
- **Verified**: `type-check`, `build` (CSS ~4.3 KB gzip), `lint`, and `vitest run` all pass.
- **Scaffolding removal**: the default Vue scaffolding (`HelloWorld`, `TheWelcome`, `WelcomeItem`, the welcome icons, the unused `counter` store, `base.css`,
  `logo.svg`) was deleted on 2026-06-18; `App.vue`
  and `HomeView.vue` were rebuilt with Tailwind + the shadcn `Button`. The `HelloWorld` unit test was replaced with a `Button` smoke test.

## References

- [Vue 3 documentation](https://vuejs.org)
- [Tailwind CSS v4](https://tailwindcss.com)
- [shadcn-vue](https://www.shadcn-vue.com)
- [PrimeVue](https://primevue.org)
- [Vuetify](https://vuetifyjs.com)
- [Reka UI (primitives behind shadcn-vue)](https://reka-ui.com)
