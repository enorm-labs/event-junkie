# ADR-011: Event-Calendar Library

## Status

**Accepted — FullCalendar**, in use on the calendar route.

> Follow-up to [ADR-010](ADR-010_FRONTEND_STYLING_FRAMEWORK.md), which adopted Tailwind v4 + shadcn-vue and deferred this as a separate sub-decision.

## Context

The calendar page is a **central screen** of the Berlin music-events guide (see
[VISION_ROADMAP_IDEAS.md](../VISION_ROADMAP_IDEAS.md)). It is a month and week view that shows **events on days**, not a date-picker. Through Reka UI,
shadcn-vue provides a date-picker `Calendar` primitive and **no event-calendar**. This needs a dedicated library or a custom build.

This decision inherits the prioritised criteria from ADR-010: **simple and clean, user-friendly, accessible, customizable, popular, well-supported,
well-documented and lightweight**. Customizable means it has to theme to our shadcn and Tailwind CSS-variable tokens. Two calendar-specific concerns come on
top:

- **Event rendering** — month and week ("time-grid") views with events placed on days and times. A list or agenda view too, ideally.
- **Roadmap fit** — iCal feeds and Google Calendar export and import are planned ([VISION_ROADMAP_IDEAS.md](../VISION_ROADMAP_IDEAS.md)). Native iCal support
  is a plus.

### Candidate options (data: June 2026)

#### Option A — FullCalendar (Vue 3 wrapper)

- **What**: [`@fullcalendar/vue3`](https://fullcalendar.io/docs/vue) v6.1.21 wrapping the FullCalendar core, with the `daygrid` (month), `timegrid` (week/day),
  `list` (agenda) and `icalendar` plugins.
- **License/cost**: The plugins we need are **MIT** (core ~2.16 M weekly downloads, daygrid ~2.17 M, timegrid ~1.71 M, list ~0.90 M, icalendar ~19 K). Only the
  **resource/timeline** premium plugins are commercial — **we don't need those**.
- **Pros**: mature and feature-rich, with month, week and list views, recurring events and drag-resize. By far the largest community of the three. **Native
  iCal feed support** via `@fullcalendar/icalendar` maps directly onto the iCal and Google roadmap. It is framework-agnostic and themeable via CSS variables.
- **Cons**: the heaviest option. It renders its own DOM and ships its own stylesheet. Matching it to our shadcn tokens means overriding FullCalendar's
  CSS variables, which is a bridging layer and not free. The imperative API is less Vue-idiomatic than a native component, and its accessibility is decent without being
  built on Reka UI.

#### Option B — vue-cal

- **What**: [`vue-cal`](https://antoniandre.github.io/vue-cal/) v4.10.2 — a Vue-3-native event calendar component (MIT, ~39 K weekly downloads).
- **Pros**: lightweight and Vue-idiomatic, through props, slots and events. Easy to restyle with our own classes and Tailwind, so theming to shadcn tokens is
  natural. Month, week, day and year views, and no premium tiers.
- **Cons**: a much smaller community and ecosystem, and fewer advanced features. **No built-in iCal**, so feeds and Google export would be custom work. Its
  accessibility is less battle-tested than FullCalendar's.

#### Option C — Custom build

- **What**: build a month and week grid ourselves, from a date library, styled with Tailwind and composed from shadcn primitives. The date library is
  either `@internationalized/date`, already present transitively through Reka UI, or `date-fns`.
- **Pros**: total control, and perfectly on-theme and on-brand. Minimal dependencies, the tightest accessibility integration with the Reka primitives we
  already use, and we ship only what we need.
- **Cons**: the most effort and the most ongoing ownership. We would implement event layout, week and time grids, recurring-event expansion, timezone and DST
  handling, and iCal parsing and export ourselves. That reinvents well-solved problems on the project's most important screen.

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
events out of the box. It scores strongly on the **popularity, community, support and documentation** criteria the project weighted in ADR-010. Its
**native iCal support directly serves the iCal and Google Calendar roadmap**, which vue-cal and a custom build would each have to reinvent. The plugins we
need are MIT. The commercial tiers are resource and timeline views we do not use.

The accepted trade-off is **theming and weight**. FullCalendar renders its own DOM and ships its own CSS. Matching the shadcn and Tailwind look therefore requires a
bridging layer, mapping FullCalendar's CSS variables to our design tokens (`--primary`, `--border`, `--muted`, …). This is the main cost, and the reason the
decision is worth recording rather than assumed.

**Alternative if priorities shift.** If bundle size and a perfectly native shadcn look outweigh ecosystem and the iCal roadmap, **vue-cal (Option B)** is the
pivot. It is lighter and easier to theme, at the cost of features, community, and custom iCal work. A **custom build (Option C)** is only justified if the
calendar's needs diverge enough from both libraries to make ownership worthwhile.

## Consequences

**Positive**

- A proven, well-documented calendar on the app's most important page.
- Month, week and list views, and recurring events, for free.
- Native iCal feed support, aligned with the export and import roadmap.
- MIT for every plugin used.

**Negative**

- The largest dependency of the frontend.
- A theming bridge is required to align FullCalendar's styling with shadcn tokens, in light and dark.
- The imperative API is less Vue-idiomatic, so it has to be wrapped in a thin Vue component to keep usage clean.

**Theming.** Encapsulate FullCalendar behind a single `EventCalendar.vue` wrapper that owns the CSS-variable overrides, so the rest of the app sees an on-theme
component.

**Accessibility.** Verify keyboard navigation and announce semantics on the wrapper. FullCalendar's defaults are a starting point, not a guarantee.

**Bundle.** Import only the plugins needed, and lazy-load the calendar route so the calendar's weight does not affect first paint elsewhere. The router already
supports per-route code-splitting.

**Scope.** This ADR covers the rendering library only. The backend iCal feed and export format is a separate concern, and is not decided here.

### As built

- **Dependency**: `@fullcalendar/vue3` alone, exact-pinned, plus its required `temporal-polyfill` peer. The plugins ship as subpaths of that framework
  package: `/daygrid`, `/timegrid`, `/list` and `/themes/classic`. The standalone `@fullcalendar/core`, `-daygrid`, `-timegrid` and `-list` packages have no
  v7 release. Types (`CalendarOptions`, `EventInput`, …) come from `@fullcalendar/vue3`. `@fullcalendar/icalendar` is **not** added yet — it arrives with the
  iCal feed and export work.
- **Wrapper**: `src/components/EventCalendar.vue` encapsulates FullCalendar and owns the theming bridge. It exposes `events` and `initialView` props, and
  defaults to month view with month, week and list toolbar buttons, Monday first.
- **View and route**: `src/views/CalendarView.vue` is mounted at `/calendar` and **lazy-loaded** via the router's per-route code-splitting. Its events are
  placeholders until it is wired to the BFF. `App.vue` carries the nav link, and Home's "Browse calendar" button links here through shadcn `as-child` and
  `RouterLink`.
- **CSS is opt-in.** v7 bundles no styles, so the wrapper imports `skeleton.css` (structure), `themes/classic/theme.css` (rules) and
  `themes/classic/palette.css` (colour defaults) explicitly.
- **Verified**: `type-check`, `lint`, `build`, `vitest run` and the Playwright e2e suite (61 tests, chromium) all pass. The calendar is also screenshotted in
  light and dark mode to confirm the bridge, because no automated test covers appearance.

#### Four things the theming bridge has to know

- **Every custom property is namespaced per theme.** `--fc-border-color` is `--fc-classic-border`, `--fc-event-bg-color` is `--fc-classic-event`, and so on.
  The bridge is therefore keyed to the `classic` theme, and **switching themes means renaming the whole block**.
- **The shipped palette flips to dark on `[data-color-scheme=dark]`, which this app never sets.** It toggles a `.dark` class instead. Every colour the palette
  varies between light and dark is therefore overridden against our own tokens, which already flip. Relying on the palette's dark block would leave light
  values showing in dark mode, because that block never fires.
- **View buttons need explicit labels.** There is no `buttonText` option and no built-in English labels behind it. A view button whose text cannot be resolved
  is **not rendered at all**, silently: the calendar still looks fine, with no view switcher. The wrapper names them through the `buttons` option.
- **The view switcher is a tablist.** It renders as `role="tablist"` with `role="tab"` children named `"<View> view"` — "Month view", "Week view", "List
  view". That is better semantics than plain buttons, and it breaks any selector written against the older shape. `e2e/calendar.spec.ts` targets the tab role.

**The cost of the theming, measured.** The calendar route chunk is ~264 kB of JS and ~18 kB of CSS. The route is lazy-loaded, so first paint elsewhere is
unaffected.

## References

- [FullCalendar — Vue 3 docs](https://fullcalendar.io/docs/vue)
- [FullCalendar — plugin/license overview](https://fullcalendar.io/docs/plugin-index)
- [`@fullcalendar/icalendar`](https://fullcalendar.io/docs/icalendar)
- [vue-cal](https://antoniandre.github.io/vue-cal/)
- [ADR-010 — Frontend styling framework](ADR-010_FRONTEND_STYLING_FRAMEWORK.md)
