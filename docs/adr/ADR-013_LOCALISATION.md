# ADR-013: Localisation (English + German)

## Status

Accepted (2026-08-07) — **implemented in full (2026-08-08)**

> Written as Phase 0 of a localisation plan, which delivered the follow-up [LEGAL.md](../LEGAL.md) §6.2 had agreed. **That plan has been retired**: every phase
> shipped, so the decisions live here, the operational rules in [`events-frontend/AGENTS.md`](../../events-frontend/AGENTS.md) §Localisation, and nothing was
> left for a plan to describe.
>
> What shipped: locale-prefixed routes for `en` and `de`, per-locale legal and About pages with German authoritative, a switcher in header and footer,
> locale-aware formatting, `hreflang`, `og:locale` and a generated sitemap.
>
> Package versions and download figures were checked on **2026-08-07**.

## Context

The site is English-only today. It targets Berlin — an audience that is heavily international but lives in Germany — and it is operated by a German controller
under German law. Two forces make German non-optional rather than a nice-to-have:

1. **Audience.** A Berlin events guide that cannot be read in German excludes a large part of the city it is about.
2. **Law.** [LEGAL.md §6.1](../LEGAL.md) chose English-only legal pages on the explicit condition that _German legal pages ship in the same release as German
   UI_. An English-only imprint and privacy notice on a site presenting itself in German to a German visitor is the configuration where the Art. 12 GDPR "clear
   and plain language" argument turns against us.

The scale is what makes this ADR worth writing: 20 of 29 `.vue` files carry user-facing text (~145 literal strings), 7 TypeScript modules do too, and ~82 e2e
assertions address elements by their English accessible name.

### Criteria

Inherited from [ADR-010](ADR-010_FRONTEND_STYLING_FRAMEWORK.md) — simple, well-supported, well-documented, lightweight, popular — plus three specific to this
decision:

- **Composition-API native.** The codebase is `<script setup>` throughout; an options-based API would be a foreign body.
- **Locale-aware date and number formatting**, not just string lookup. Dates are the most visible difference between the two locales.
- **Crawlable per-language URLs.** SEO is on the backlog and prerendering is planned; a language that exists only in JavaScript state is invisible to crawlers.

## Candidate options

### Library

#### Option A — `vue-i18n` + `@intlify/unplugin-vue-i18n`

- v11.4.8, MIT, ~3.6 M weekly downloads, 19 releases in the last 8 months. The de-facto standard for Vue 3, by the Intlify team.
- Composition API (`useI18n`) is first-class; the Legacy API is deprecated in v11 and removed in v12, so writing Composition-only today makes v12 a version
  bump.
- Ships `$d`/`$n` wrappers over `Intl` for dates and numbers.
- The unplugin (v11.2.4, MIT) precompiles messages at build time, which removes the runtime message compiler.
- **Verified compatible with this repo**: `vue ^3.0.0` (we have 3.5.41), plugin peers `vite ^6 || ^7 || ^8` (we have 8.2.0).
- Heaviest of the candidates by package size (~1.58 MB unpacked, though tree-shaken and precompiled runtime cost is a fraction of that).

#### Option B — `petite-vue-i18n`

- Same team, same version line, a deliberately reduced subset (~1 MB unpacked).
- **~4.7 K weekly downloads against vue-i18n's 3.6 M.** That ratio is the problem: it is the same project's minor sibling, so every question, example and Stack
  Overflow answer is written for the full package.
- Drops features we would immediately want back, including the datetime/number formatting that is the most visible part of German localisation.

#### Option C — `vue-intl` / FormatJS

- `vue-intl` is a thin Vue binding (~10 kB) over FormatJS, which is itself very widely used (`@formatjs/intl`, ~3.3 M weekly).
- But the Vue binding has **~6 K weekly downloads** and is not the mainline of the FormatJS project — the React binding is. The ICU message syntax is excellent;
  the Vue-side ecosystem around it is thin.

#### Option D — Hand-rolled: plain JSON catalogues + native `Intl` + a small composable

- No dependency at all. `Intl.DateTimeFormat` and `Intl.NumberFormat` already do the formatting work; a `useT()` composable over a JSON object is perhaps 40
  lines.
- Genuinely viable at this size, and worth taking seriously rather than dismissing.
- What it costs: pluralisation, interpolation, fallback chains, lazy-loading per locale, and the SFC/devtools integration all become ours to write and
  maintain — and each is a small thing that is easy to get subtly wrong. It also gives future contributors nothing familiar to work from.

### URL strategy

| Option                 | Example                    | Crawlable | Shareable | Notes                                                                                                                                     |
| ---------------------- | -------------------------- | --------- | --------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| **Path prefix**        | `/de/events`               | ✅        | ✅        | One deployment, one origin. Google's own guidance prefers distinct URLs per language.                                                     |
| Subdomain              | `de.event-junkie.de`       | ✅        | ✅        | Extra DNS and TLS setup; splits the origin, which complicates the same-origin `/api` arrangement in [ADR-012](ADR-012_CLOUD_PLATFORM.md). |
| ccTLD                  | `event-junkie.de` / `.com` | ✅        | ✅        | A second domain to buy and run. Language and country are not the same axis — a German speaker abroad wants German, not `.de`.             |
| Query parameter        | `/events?lang=de`          | ⚠️        | ✅        | Works, but reads as an afterthought and is weaker for SEO.                                                                                |
| Stored preference only | `/events`                  | ❌        | ❌        | Every shared link becomes a coin flip: the recipient gets whatever _their_ storage says. Invisible to crawlers.                           |

## Decision

**Accepted, and implemented from Phase 1 onward:**

### 1. `vue-i18n` v11 with `@intlify/unplugin-vue-i18n`, Composition API only

Option A. Option D is the only real contender — at 145 strings and two locales, a hand-rolled composable would work — but the features it defers (plural rules,
fallback chains, lazy loading, and the `Intl` wrappers) are exactly the ones that arrive later as small, quiet bugs, and this project has no appetite for
maintaining an i18n runtime alongside everything else. Options B and C are ruled out by ecosystem size rather than by capability.

**The Legacy API is not to be used**, even though most tutorials show it. `useI18n` from the first line.

### 2. Path-prefixed URLs: `/en/…` and `/de/…`

Bare `/` redirects to a locale chosen from `Accept-Language`, falling back to `en`. Unprefixed routes (`/events`) redirect rather than 404.

**Doing this now is materially cheaper than doing it later**: the site is not deployed, so there are no inbound links, no search index and no bookmarks to
preserve. Every month this waits, the cost rises.

A stored locale preference (`localStorage`) is permitted **only** as a hint for resolving bare `/`. The URL is always the source of truth. This keeps the § 25
TDDDG posture from [LEGAL.md §7.4](../LEGAL.md) intact: a preference the user set themselves is strictly necessary, so no consent banner — but it must not
become the _only_ record of the choice.

### 3. Translate the chrome, not the data

|                                                          | Translate?                           |
| -------------------------------------------------------- | ------------------------------------ |
| UI labels, headings, empty and error states, legal pages | ✅                                   |
| Event titles, venue names, artist names, line-ups        | ❌ third-party content               |
| Berlin district names (`lib/districts.ts`)               | ❌ proper nouns — _Mitte_ is _Mitte_ |
| Event types (`CONCERT`, `CLUB_NIGHT`, …)                 | ✅ enum-backed, so ours              |
| Genre tags                                               | ❌ they behave like data             |

Genre tags are the close call. They are enum-ish, but they arrive from venues and are largely untranslatable anyway ("Techno", "Singer-Songwriter"). Treating
them as data avoids a translation table that would be wrong as often as right.

### 4. Formatting

- **`formatDate`** becomes locale-aware — the most visible change in the whole phase (_Fri, 12 Jun 2026_ → _Fr., 12. Juni 2026_).
- **`formatEventType`** stops deriving labels by string manipulation (`CLUB_NIGHT` → `Club night`) and becomes a message lookup keyed by the enum, with a
  fallback for `OTHER` and for values the frontend has not seen. **No amount of locale plumbing can translate the current implementation** — this is a rewrite,
  not a wiring change.
- **`formatPrice` stays `de-DE` in both locales** (`38,00 €`). That is the price written on the door in Berlin; `€38.00` would be a worse answer for an
  English-speaking user standing in front of that door.
- **`todayIso` must NOT become locale-aware.** It uses `Intl.DateTimeFormat('en-CA')` as a trick to obtain `YYYY-MM-DD` — a _format_, not a language. Changing
  it breaks every date filter in the app **silently**, because the output remains a plausible date.

### 5. German becomes the authoritative version of the legal pages

Stated on each page once both exist: _Maßgeblich ist die deutsche Fassung._ The controller, the venue and the supervisory authority are all German.

## Consequences

**Accepted costs:**

- **Node 20 support is dropped.** `vue-i18n` requires Node ≥ 22 and the plugin ≥ 22.13, while `package.json` currently declares `^20.19.0 || >=22.12.0`. The
  engines field must be raised in the same change; nothing in CI pins Node 20, but this is a deliberate narrowing, not an accident.
- **Every route gains a prefix**, so every internal link, every router assertion and the `scrollBehavior` added in the footer work must account for it.
- **The German legal pages become release-blocking.** German UI cannot ship without them (§Context). This is the constraint most likely to be forgotten under
  time pressure, and the one with an actual legal standard attached.
- **Two message catalogues drift.** Mitigated by a unit test asserting identical key sets — a missing German key silently falls back to English, which is
  exactly the failure that ships unnoticed.
- **German is longer than English**, reliably. Layouts that fit today will not all fit tomorrow; the axe sweep and the overflow guards must cover `/de`.

**Deliberately deferred:**

- **More than two languages.** The structure supports it; nothing here assumes it.
- **Backend localisation.** The BFF returns data and RFC 9457 problem details; the frontend owns all user-facing language. If that changes, `Accept-Language`
  handling in the BFF is a separate decision.
- **SSR / prerendering.** Wanted for SEO and tracked separately, but not a prerequisite: `hreflang` and per-locale `og:locale` are worth adding regardless. _(
  Decided in [ADR-014](ADR-014_RENDERING_STRATEGY.md), 2026-08-08. The "not a prerequisite" judgement held for `hreflang` — the sitemap carries it — but only
  partly: page-level `og:` tags do need server-side rendering, because the scrapers that consume them do not run JavaScript.)_
- **A German tagline.** _"Can't get enough of Berlin"_ is a pun on the brand premise ([BRANDING.md](../BRANDING.md) §2) and a literal rendering loses it.
  Whether the brand line stays English on the German site is a **brand decision, not an architectural one** — it belongs in BRANDING.md, and many Berlin brands
  do keep an English tagline.

## References

- [`events-frontend/AGENTS.md`](../../events-frontend/AGENTS.md) §Localisation — the rules this ADR's decisions became, and §Testing for the e2e locale strategy
- [LEGAL.md](../LEGAL.md) §6.1, §6.2, §7.4 — the language and device-storage commitments this inherits
- [Vue I18n](https://vue-i18n.intlify.dev/) · [`@intlify/unplugin-vue-i18n`](https://www.npmjs.com/package/@intlify/unplugin-vue-i18n)
- [Google Search Central — managing multi-regional and multilingual sites](https://developers.google.com/search/docs/specialty/international/managing-multi-regional-sites)
