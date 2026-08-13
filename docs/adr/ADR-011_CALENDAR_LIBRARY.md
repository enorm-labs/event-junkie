# ADR-011: Event-Calendar Library

## Status

Accepted (2026-06-18)

> Follow-up to [ADR-010](ADR-010_FRONTEND_STYLING_FRAMEWORK.md), which adopted Tailwind v4 + shadcn-vue and
> explicitly deferred the **event-calendar** library as a separate sub-decision. Adopted **FullCalendar**;
> the calendar route was scaffolded in `events-frontend/` on 2026-06-18.

## Context

The calendar page is a **central screen** of the Berlin music-events guide (see
[VISION_ROADMAP_IDEAS.md](../VISION_ROADMAP_IDEAS.md)): a month/week view that shows **events on days**, not just a date-picker. shadcn-vue (via Reka UI)
provides a date-picker `Calendar` primitive but **no event-calendar**, so a dedicated library or custom build is needed.

This decision inherits the prioritised criteria from ADR-010 — **simple & clean, user-friendly, accessible, customizable (must theme to our shadcn/Tailwind
CSS-variable tokens), popular, well-supported, well-documented, lightweight** — plus two calendar-specific concerns:

- **Event rendering** — month + week ("time-grid") views with events placed on days/times; ideally a list/agenda view too.
- **Roadmap fit** — iCal feeds and Google Calendar export/import are planned ([VISION_ROADMAP_IDEAS.md](../VISION_ROADMAP_IDEAS.md)); native iCal support is a
  plus.

### Candidate options (data: June 2026)

#### Option A — FullCalendar (Vue 3 wrapper)

- **What**: [`@fullcalendar/vue3`](https://fullcalendar.io/docs/vue) v6.1.21 wrapping the FullCalendar core, with the `daygrid` (month), `timegrid` (week/day),
  `list` (agenda) and `icalendar` plugins.
- **License/cost**: The plugins we need are **MIT** (core ~2.16 M weekly downloads, daygrid ~2.17 M, timegrid ~1.71 M, list ~0.90 M, icalendar ~19 K). Only the
  **resource/timeline** premium plugins are commercial — **we don't need those**.
- **Pros**: Mature, feature-rich (month/week/list, recurring events, drag/resize); by far the largest community of the options; **native iCal feed support** via
  `@fullcalendar/icalendar` maps directly onto the iCal/Google roadmap; v6 is framework-agnostic and themeable via CSS variables.
- **Cons**: Heaviest option; renders its own DOM and ships its own stylesheet — matching it to our shadcn tokens means overriding FullCalendar's CSS variables
  (a bridging layer, not free); imperative API is less Vue-idiomatic than a native component; its accessibility is decent but not built on Reka UI.

#### Option B — vue-cal

- **What**: [`vue-cal`](https://antoniandre.github.io/vue-cal/) v4.10.2 — a Vue-3-native event calendar component (MIT, ~39 K weekly downloads).
- **Pros**: Lightweight and Vue-idiomatic (props/slots/events); easy to restyle with our own classes and Tailwind, so theming to shadcn tokens is natural;
  month/week/day/year views; no premium tiers.
- **Cons**: Much smaller community and ecosystem; fewer advanced features; **no built-in iCal** — feeds and Google export would be custom work; accessibility is
  less battle-tested than FullCalendar.

#### Option C — Custom build

- **What**: Build a month/week grid ourselves from a date library (`@internationalized/date` — already transitively present via Reka UI — or `date-fns`) styled
  with Tailwind and composed from shadcn primitives.
- **Pros**: Total control; perfectly on-theme and on-brand; minimal dependencies; tightest accessibility integration with the Reka primitives we already use;
  ship only what we need.
- **Cons**: The most effort and the most ongoing ownership — we'd implement event layout, week/time grids, recurring-event expansion, timezone/DST handling, and
  iCal parsing/export ourselves; reinvents well-solved problems on the project's most important screen.

### Comparison

| Criterion                   | Weight | A: FullCalendar       | B: vue-cal        | C: Custom             |
| --------------------------- | ------ | --------------------- | ----------------- | --------------------- |
| Event month/week/list views | High   | ✅ All, mature        | ✅ Month/week/day | 🟡 We build each      |
| Simple & clean              | Med    | 🟡 Own DOM/CSS        | ✅ Native/simple  | ✅ Exactly as we want |
| Accessible                  | High   | 🟡 Decent (own)       | 🟡 Less proven    | ✅ Reka-aligned (DIY) |
| Customizable (shadcn theme) | High   | 🟡 CSS-var bridge     | ✅ Native restyle | ✅ Full               |
| iCal / Google roadmap fit   | Med    | ✅ Native iCal plugin | ❌ Custom         | ❌ Custom             |
| Popularity / support        | Med    | ✅ ~2.1 M/wk core     | 🟡 ~39 K/wk       | n/a                   |
| Documentation               | Med    | ✅ Extensive          | 🟡 Good           | n/a                   |
| Lightweight                 | Med    | ❌ Heaviest           | ✅ Light          | ✅ Lightest           |
| Upfront / ongoing effort    | Med    | ✅ Low                | ✅ Low            | ❌ High               |

## Decision

**Proposed: Option A — FullCalendar** (`@fullcalendar/vue3` with the MIT `daygrid`, `timegrid`, `list`, and `icalendar` plugins).

Rationale: the calendar is the **central, highest-risk screen**, and FullCalendar's maturity de-risks it while covering month/week/list views and recurring
events out of the box. It scores strongly on the **popularity, community, support, and documentation** criteria the project weighted in ADR-010, and its
**native iCal support directly serves the iCal/Google Calendar roadmap** — a capability vue-cal and a custom build would each have to reinvent. The plugins we
need are MIT; the commercial tiers are resource/timeline views we don't use.

The accepted trade-off is **theming and weight**: FullCalendar renders its own DOM and ships its own CSS, so matching the shadcn/Tailwind look requires a
bridging layer that maps FullCalendar's CSS variables to our design tokens (`--primary`, `--border`, `--muted`, …). This is the main cost, and the reason the
decision is worth recording rather than assumed.

**Alternative if priorities shift**: if bundle size and a perfectly native shadcn look outweigh ecosystem and the iCal roadmap, **vue-cal (Option B)** is the
pivot — lighter and easier to theme, at the cost of features, community, and custom iCal work. A **custom build (Option C)** is only justified if the calendar
needs diverge enough from both libraries to make ownership worthwhile.

## Consequences

- **Positive**: Proven, well-documented calendar on the app's most important page; month/week/list and recurring events for free; native iCal feed support
  aligned with the export/import roadmap; MIT for all plugins used.
- **Negative**: Largest dependency of the frontend; a theming bridge is required to align FullCalendar's styling with shadcn tokens (light/dark); the imperative
  API is less Vue-idiomatic and must be wrapped in a thin Vue component to keep usage clean.
- **Theming**: Encapsulate FullCalendar behind a single `EventCalendar.vue` wrapper that owns the CSS-variable overrides, so the rest of the app sees an
  on-theme component.
- **Accessibility**: Verify keyboard navigation and announce semantics on the wrapper; FullCalendar's defaults are a starting point, not a guarantee.
- **Bundle**: Import only the needed plugins; lazy-load the calendar route (the router already supports per-route code-splitting) so the calendar's weight
  doesn't affect first paint elsewhere.
- **Scope**: This ADR covers the rendering library only. The backend iCal feed/export format is a separate concern and not decided here.

### As-built setup (2026-06-18)

- **Dependencies** (exact-pinned): `@fullcalendar/vue3`, `@fullcalendar/core`, `@fullcalendar/daygrid`,
  `@fullcalendar/timegrid`, `@fullcalendar/list` (all v6.x, MIT). `@fullcalendar/icalendar` is **not** added yet — it comes with the iCal feed/export work.
- **Wrapper**: `src/components/EventCalendar.vue` encapsulates FullCalendar and owns the theming bridge — a `<style scoped>` block mapping FullCalendar's
  `--fc-*` CSS variables to our shadcn tokens (`--border`,
  `--background`, `--muted`, `--primary`, …) so the calendar follows light/dark mode. It exposes `events`
  and `initialView` props; defaults to month view with month/week/list toolbar buttons, Monday-first.
- **View/route**: `src/views/CalendarView.vue` (placeholder events; to be wired to the BFF) is mounted at
  `/calendar`, **lazy-loaded** via the router's per-route code-splitting. Nav link added in `App.vue`, and Home's "Browse calendar" button links here via shadcn
  `as-child` + `RouterLink`.
- **No CSS import needed**: FullCalendar v6 injects its own base styles at runtime.
- **Verified**: `type-check`, `lint`, `build`, and `vitest run` pass.

### Updated for FullCalendar 7 (2026-08-05)

The decision — FullCalendar behind a single wrapper — is unchanged. The v7 upgrade changed how nearly all of it is wired, so the notes above describe v6 only
and the following supersedes them:

- **Dependencies**: `@fullcalendar/core`, `-daygrid`, `-timegrid` and `-list` are **gone**. v7 ships the plugins as subpaths of the framework package
  (`@fullcalendar/vue3/daygrid`, `/timegrid`, `/list`, `/themes/classic`), and the standalone plugin packages have no v7 release — 6.1.21 is their last version.
  The only direct dependency is `@fullcalendar/vue3`, plus its required `temporal-polyfill` peer. Types (`CalendarOptions`, `EventInput`, …) now come from
  `@fullcalendar/vue3` rather than `@fullcalendar/core`.
- **CSS is now opt-in**: the "no CSS import needed" note above is **no longer true**. v7 bundles no styles, so the wrapper imports `skeleton.css` (structure),
  `themes/classic/theme.css` (rules) and `themes/classic/palette.css` (colour defaults) explicitly.
- **The theming bridge was rewritten, not renamed.** v7 renamed every custom property and namespaced it per theme: `--fc-border-color` →
  `--fc-classic-border`, `--fc-event-bg-color` → `--fc-classic-event`, and so on; no v6 name survives anywhere in the package. The bridge is therefore keyed to
  the `classic` theme, and **switching themes means renaming the whole block**.
    - The shipped palette flips to dark on `[data-color-scheme=dark]`, which this app never sets — it toggles a `.dark` class. Every colour the palette varies
      between light and dark is therefore overridden against our tokens (which already flip), rather than relying on the palette's own dark block, which would
      never fire and would leave light values showing in dark mode.
- **View buttons need explicit labels.** v7 removed the `buttonText` option and the built-in English labels behind it. A view button whose text cannot be
  resolved is **not rendered at all**, silently — the calendar still looks fine, just with no view switcher. The wrapper names them via the `buttons` option.
- **The view switcher is a tablist.** v7 renders it as `role="tablist"` with `role="tab"` children named `"<View> view"` ("Month view", "Week view",
  "List view"), where v6 used plain buttons named "month"/"week"/"list". This is better semantics, but it breaks any selector written against the old shape —
  `e2e/calendar.spec.ts` targets the tab role accordingly.
- **Cost**: the calendar route chunk grew from ~233 kB to ~264 kB of JS, and its CSS from ~1 kB to ~18 kB (previously injected at runtime rather than emitted as
  a stylesheet). The route is still lazy-loaded, so first paint elsewhere is unaffected.
- **Verified**: `type-check`, `lint`, `build`, `vitest run` and the Playwright e2e suite (61 tests, chromium) pass; the calendar was also screenshotted in both
  light and dark mode to confirm the rewritten bridge, since no automated test covers appearance.

## References

- [FullCalendar — Vue 3 docs](https://fullcalendar.io/docs/vue)
- [FullCalendar — plugin/license overview](https://fullcalendar.io/docs/plugin-index)
- [`@fullcalendar/icalendar`](https://fullcalendar.io/docs/icalendar)
- [vue-cal](https://antoniandre.github.io/vue-cal/)
- [ADR-010 — Frontend styling framework](ADR-010_FRONTEND_STYLING_FRAMEWORK.md)
