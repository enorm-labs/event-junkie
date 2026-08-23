# Implementation Plan — Brand refresh (mark replacement, then the visual pass)

Execution plan for [#475](https://github.com/enorm-labs/event-junkie/issues/475) (_Replace the pulse mark_) and
[#374](https://github.com/enorm-labs/event-junkie/issues/374) (_Improve branding and visual design_), which are one piece of work in two halves rather than two
issues that happen to be adjacent. The reasoning behind the replacement — the collision itself, the directions considered, and why one of them is recommended —
lives in [BRANDING.md](BRANDING.md) §4, §4a and §4b, the candidates and the case against each live in [LOGO_IDEAS.md](LOGO_IDEAS.md), and neither is repeated
here. **This document is the ordered list of what to do about it**, and since 2026-08-23 the decision it was waiting on exists — see Phase 1.

**The chain is #475 → #374 → [#294](https://github.com/enorm-labs/event-junkie/issues/294)** (README hero screenshot), and
[#377](https://github.com/enorm-labs/event-junkie/issues/377) (display typeface) is settled inside #374 rather than separately.

**Why that order and not the other one.** #374 was written first and originally owned the logo. The dependency reversed on 2026-08-13: the mark stopped being a
refinement and became a replacement, and a visual pass that chooses a palette, a motion language and a hero treatment around a mark that is being deleted does
the work twice. The home hero is the clearest case — its ambient glow is centred on the mark and only reads correctly if the mark is its light source. In the
event the decision removed the mark from the hero entirely, and the glow with it (Phase 3 step 4), which is the same conclusion arrived at from the other end.

**One PR per phase.** Phases 1–4 are #475 and should land together or in close succession, because a half-replaced identity — new header, old favicon — is worse
than either state. Phase 5 is #374 and is deliberately a separate branch.

---

## What must not change

Worth stating up front, because each of these looks like part of the same family and is easy to delete by association.

| Survives untouched                                                                          | Why it looks related, and is not                                                                                                    |
| ------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| **Eyebrow labels** — `SectionLabel.vue`, `--tracking-eyebrow`, the mono uppercase `TONIGHT` | A typographic device from the editorial-listings direction. Unrelated to the logo; the name collision with "pulse" is coincidental. |
| **The wordmark** — "Event Junkie", accent on "Junkie"                                       | #475 replaces the _mark_, not the name. It now carries more weight, not less: the decision leaves no pictorial mark in the chrome.  |
| **The naming rule** (§1) and the voice (§3)                                                 | Brand foundation. Decided, and nothing here reopens it.                                                                             |
| **`GitHubMark.vue`**                                                                        | A third-party glyph that happens to sit next to ours in the footer.                                                                 |

**Non-goals for #475 specifically:** no palette change, no type change, no layout change. This is why the **club stamp is held out of the home hero** (Phase 3
step 7): black ink and distress is a different visual language from the UV-violet, soft-glow look §5.1 records as shipped, so placing it in the app would be a
#374 decision taken inside a logo PR. The README and the social preview sit outside the app and are unaffected.

---

## Phase 0 — Evidence, and the name check

Mostly done. What remains is hand-work at a browser, and it is the only part of this plan that cannot be automated.

- [x] **Both marks read from source and compared field by field.** BRANDING.md §4a, dated 2026-08-19. The comparison is construction, caps, joins, gradient
      endpoints and lockup role — not a visual impression, which is what makes it usable later.
- [x] **A general web search on the wordmark.** No product, app or company trading as _Event Junkie_ in the German or EU events market. Weak evidence, recorded
      as weak evidence.
- [ ] **Capture the side-by-side image.** §4a records the comparison as a table; the issue also asks for a picture. Render both SVGs at the same height into one
      PNG, attach it to #475, and commit it as `docs/branding/collision-2026-08-19.png`. An attachment on an issue is not a durable record on its own.
- [x] **Text prior-art on the wordmark and on `EJ`.** Run 2026-08-23, recorded in §4a bis. The wordmark is clear; `EJ` is in use by several small
      entertainment entities and its monogram is generic on the stock marketplaces, which is a distinctiveness finding rather than a collision.
- [x] **The register searches**, on the wordmark _Event Junkie_ and the closed-up _Eventjunkie_, in **classes 9, 41 and 42**. Run 2026-08-23, no hits. Free, minutes each, and
      session-based applications rather than queryable endpoints — so they are done by hand and the result is pasted into §4a with the date:
    - [DPMAregister](https://register.dpma.de/DPMAregister/marke/einsteiger) — German national marks
    - [EUIPO eSearch](https://www.euipo.europa.eu/en/search) — EU trade marks
    - [TMview](https://www.tmdn.org/tmview/) — both of the above plus WIPO in one query
- [x] **The figurative image search on both adopted assets.** Run 2026-08-23 on TMview, eSearch plus, Google Lens and TinEye — **no hits on any of them**;
      the table is in §4a bis. This was a different search and the one #475's "reverse image search" instruction was reaching
      for. **eSearch plus and TMview take an uploaded PNG** and rank visually similar marks across ~57 million figurative applications — free, no account, one
      image at a time. That covers registered marks. **A reverse image search (Google Lens, TinEye, Yandex) still has its own job**: it is what surfaces an
      unregistered mark in active use, which is exactly what SprintPulse was. Neither is optional and neither replaces the other.

**A hit in class 9 or 41 changes this plan's scope from a mark to a name**, which is why the searches sit in Phase 0 and not somewhere convenient. A hit in an
unrelated class is not a problem and should be recorded as checked-and-fine, so nobody re-runs it in six months.

---

## Phase 1 — Decide the mark · **done, 2026-08-23**

**Decided, and the answer is not a mark.** BRANDING.md §4b records it in full; the short version is that there are three surfaces and each gets the thing that
works on it:

| Surface                                                    | Asset                                                                                                |
| ---------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `favicon.svg` / `.ico`, 16 px                              | **small badge** — [`…-ej-badge-small.svg`](branding/mark-ej-badge-small.svg)                         |
| Header below `sm`, at 24 px                                | **small badge** — the same file                                                                      |
| README, social preview, `apple-touch-icon`, merch — 96 px+ | **stamp badge** — [`…-ej-badge-stamp.svg`](branding/mark-ej-badge-stamp.svg)                         |
| Header below `sm`                                          | **EJ badge** alone                                                                                   |
| Header `sm`+, and the footer                               | **Wordmark** alone                                                                                   |
| Home hero (held), About — bilingual                        | **Club stamp**, _BERLIN_ — [`lockup-club-stamp.svg`](branding/lockup-club-stamp.svg)                 |
| README, GitHub + site social preview — English-only        | **Club stamp**, tagline — [`…-club-stamp-tagline-en.svg`](branding/lockup-club-stamp-tagline-en.svg) |

There is **no pictorial mark in the UI chrome**. Badge and wordmark are never shown together — same two letters, so together they are tautological, and
splitting them across `sm` also solves the header overflow the e2e guard already watches.

**What this phase actually taught, and it is the transferable part:** the recommendation this section was written to execute did not survive being rendered. The
overflowing calendar cell is a clipboard at 16 px. Nobody had rasterised it, and no automated check in this repository would ever have said so. The rule that
follows is in [LOGO_IDEAS.md](LOGO_IDEAS.md) §"Adding an idea", and it is the cheapest step on this page: **draw it, render it at 16 px, and look at it.**

**Nothing is outstanding from this phase.** The two checks it was waiting on were run on 2026-08-23 and both came back empty — the register searches on the
wordmark and on `EJ`, and the figurative image searches on both adopted assets across TMview, eSearch plus, Google Lens and TinEye. §4a bis records what that
does and does not establish.

**One trigger to remember:** the image searches match on shape, so they have to be **re-run when the stamp's text is outlined and its distress redrawn**
(Phase 2). That is a material change to the artwork, not a tidy-up.

---

## Phase 2 — The asset set

The mark is one file; the identity is eleven. This is where most of the work is, and it is the part that is easy to underestimate because the interesting
decision already happened in Phase 1.

| Asset                   | File                                         | Notes                                                                                                                                                                                                                                  |
| ----------------------- | -------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Badge, icon + chrome    | `docs/branding/mark-ej-badge-small.svg`      | Solid, tilted, drawn letters, no frame. 16 px favicon **and** the 24 px header. No font dependency, which `favicon.svg` needs since browsers get the SVG itself                                                                        |
| Badge, display          | `docs/branding/mark-ej-badge-stamp.svg`      | The lockup minus the words: same tilt, face, corners and wear; one frame. **96 px and up.** Text outlined, so no blocker                                                                                                               |
| Stamp, source (×2)      | `docs/branding/lockup-club-stamp.svg`        | Drawn. Wordmark is Rubik Distressed, **already outlined**. **One blocker left**: _BERLIN_ is still `<text>` — set it in Geist and outline it. Frame wear needs a hand redraw                                                           |
| Badge component         | `events-frontend/src/components/EjBadge.vue` | Replaces `PulseMark.vue`. Inline SVG, `aria-hidden`. **The mask id must come from `useId()`** — two on a page sharing one id renders the second solid. ~200 ellipses inline, so check the gzipped cost rather than assuming it is free |
| Lockup                  | `src/components/BrandLogo.vue`               | Rewritten: badge below `sm`, wordmark at `sm`+, never both. `sr-only` wordmark stays so the link keeps its accessible name                                                                                                             |
| Favicon (vector)        | `public/favicon.svg`                         | ✅ Done — the small badge with explicit opaque fills, plus a `prefers-color-scheme` block. Flat ink, no gradient                                                                                                                       |
| Favicon (raster)        | `public/favicon.ico`                         | ✅ Done — 16 and 32 px, PNG-embedded ICO, built from the **light** scheme because that is Safari's fallback                                                                                                                            |
| iOS home screen         | `public/apple-touch-icon.png`                | ✅ Done — 180×180, stamp badge reversed on an opaque ink ground, **full-bleed and square** so iOS applies its own squircle                                                                                                             |
| Social preview (GitHub) | `docs/branding/social-preview.png`           | ✅ Rendered to `docs/branding/social-preview.png`, 1280×640. **Still to upload** — a settings page, not a commit                                                                                                                       |
| Social preview (site)   | `events-frontend/public/og-image.png`        | ✅ Done — `og-image.png` 1200×630, plus `og:image`, dimensions, alt, `twitter:image` and `summary_large_image`                                                                                                                         |
| README logo             | `README.md`                                  | The **tagline stamp** — [`…-club-stamp-tagline-en.svg`](branding/lockup-club-stamp-tagline-en.svg). The README is English-only. No logo today; #475 asks for one                                                                       |
| Monochrome dark variant | derived                                      | The theme defaults to dark (§5.2), so this is the _common_ case rather than the fallback                                                                                                                                               |

**The stamp is not shippable yet, and that is a sequencing fact rather than a caveat.** Its text is `<text>` in a system font stack, so it renders differently on
any machine missing the first family — for a logo that is a defect, not a risk — and its distress is algorithmic and reads as uniform damage. The badge has no
such blocker, so **the badge half of this phase can land first** and the stamp half follows. That is also the natural split if the hero placement is being held
back (see Phase 3).

**Two findings worth landing while here.**

**The site had no `og:image` at all — fixed 2026-08-23.** `index.html` sets `og:title`, `og:description` and `twitter:card` — and the card is `summary`, not
`summary_large_image`. Every link to `event-junkie.de` on Slack, X or Discord unfurled as text. That is the same gap #477 records for the GitHub social
preview, one surface over, and it is not written down anywhere. Fixing it is two meta tags and one PNG, and it is only sensible to do it once the mark exists —
which is exactly why it belongs in this phase and not in a tidy-up later.

**No gradient — decided 2026-08-23.** The badge is flat ink. That is §4's _monochrome-first_ principle applied rather than argued with, and it retires the last
trace of the §4a collision signature, which was as much the left-to-right violet-to-magenta ramp as it was the ECG line.

**Flat does not mean "just remove the gradient", and this is the trap.** The badge is a **knockout** — its letters are transparent, not white — so the moment it
becomes `favicon.svg` it is a solid shape whose letters take the colour of whatever sits behind it. On a dark browser tab strip that is dark-on-dark, and the
monogram vanishes. Two things follow, and neither is optional:

- **The favicon needs explicit fills**, an opaque ground and opaque letters, rather than `currentColor` plus transparency. Inside the app the knockout is
  correct and should stay; the favicon is a different rendering context and needs its own build of the same drawing.
- **Ink, not the accent — decided 2026-08-23.** The badge stays monochrome, which is §4's _monochrome-first_ principle applied rather than an exception carved
  out of it. An earlier note here argued that a dark square on a dark tab strip "loses its silhouette", and overstated it: **the silhouette is not what carries
  the mark, the letters are.** An opaque ink ground with opaque white letters reads on a light strip as a black square, and on a dark strip as a floating white
  _EJ_ — legible either way, because whichever element loses contrast, the other gains it. That is why no coloured ground is needed.

**An SVG favicon can carry `@media (prefers-color-scheme: dark)`** and Chrome, Firefox and Edge honour it, switching without a reload. **Safari does not** — it
falls back to `favicon.ico`. So the media query is a refinement, not the fix: whichever ground is chosen has to work unaided, because a meaningful share of
users will only ever see that one.

**On generating the raster files.** There is no script for this today; `apple-touch-icon.png` was made by hand. Either is fine, but if it is done by hand, write
down the command — `rsvg-convert`, `resvg` or ImageMagick over the source SVG — in a comment next to the asset, so the next person regenerating a 180 px icon is
not reverse-engineering a padding value from a PNG.

---

## Phase 3 — Land it in the frontend

Ordered so the tree compiles at every step. The badge component is first because everything else imports it. **Steps 1–6 are the badge and are unblocked;
step 7 is the stamp and is not** — see Phase 2.

1. **Add `EjBadge.vue`** and delete `PulseMark.vue`. A rename rather than a parallel life — two marks in the tree for a week is how one of them ships by
   accident. **The mask id must come from `useId()`**, the way `PulseMark.vue` already does for its gradient: two badges on one page sharing a mask id renders
   the second as a solid block, and the footer plus the header is two.
2. **`BrandLogo.vue`** — rewritten rather than repointed, because the rule changed. Today it always renders the mark and hides the wordmark below `sm`. It now
   renders **the small badge below `sm` and the wordmark at `sm` and up, never both**. The `sr-only` wordmark stays regardless, so the link's accessible name remains
   "Event Junkie" at every width. `AppFooter.vue` passes `always-show-wordmark` and has the room, so the footer is wordmark-only.
    - The `e2e/smoke.spec.ts` header-overflow guard is what proves this: the badge is narrower than the wordmark, which is the reason mobile can afford it.
3. **`HomeView.vue`** — the hero, and this is the step with a decision in it. The pulse mark is removed; **it is not kept here.** The hero is the mark's largest
   and most prominent placement on the site (`h-14 sm:h-20`, animated, above the fold) against 24 px in the header, so keeping it here would retain the
   collision on the one surface where it is most visible, and would cost the position §4a exists to establish.
4. **The ambient glow goes with it.** It is a radial `var(--primary)` centred on the mark, not on the hero, and its whole premise is that the mark is its light
   source. Nothing replaces the mark in that slot, so nothing is left to be the source. **The stamp is not a substitute** — it is ink, and a glowing rubber
   stamp is incoherent. If a lit hero is wanted later, that is a §5.1 "spotlight in a dark room" decision and it belongs to #374.
5. **`main.css`** — delete `ej-draw` and `ej-beat` (lines ~151–184) outright rather than replacing them. Both are pulse-specific: `ej-draw` depends on
   `pathLength="1"` and one continuous stroke, and neither the badge nor the stamp is animated. **A mark that does not need to move is not a worse mark.** If
   anything is animated later it stays gated behind `prefers-reduced-motion: reduce`, which is not optional here.
6. **`index.html`** — the icon links are already correct (`favicon.svg`, `favicon.ico`, `apple-touch-icon.png`); only the files behind them change. Add
   `og:image` / `twitter:image` and switch `twitter:card` to `summary_large_image` (Phase 2).
7. **The stamp in the hero — hold this one.** Blocked twice over: on the stamp's own two fixes (Phase 2), and on a judgement that is not #475's to make.
   Black ink, distressed and high-contrast is a different visual language from the UV-violet, soft-glow, dark-mode look §5.1 records as shipped, so putting it
   in the hero is a partial answer to [#374](https://github.com/enorm-labs/event-junkie/issues/374) decided inside #475. **The README and the social preview
   sit outside the app and can take the stamp immediately** (Phase 4); the hero is the placement worth holding until the visual pass runs. When it does land,
   keep the `<h1>` as `sr-only` — the stamp carries the name as artwork, and the live text has to survive for search and screen readers.
8. **Tests.** There is no `BrandLogo.spec.ts` or `PulseMark.spec.ts` today — `AppFooter.spec.ts` covers the footer that renders the lockup. Add a component
   spec for the lockup asserting the accessible name **and the badge/wordmark swap at `sm`**, because both are behaviours a logo change can silently break and
   neither is visible in a screenshot. The swap is new behaviour and nothing currently guards it.

---

## Phase 4 — Docs, and the surfaces a `grep` cannot reach

The part that does not finish itself. §1 already paid for this lesson once: when the repository was renamed, a clean `grep` looked like completion and left two
whole classes of thing behind. Both apply here.

### The branding documents

| Where                         | Change                                                                                                                                     |
| ----------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| `docs/BRANDING.md` §4         | Direction #1 is recorded as **shipped**. It is not. Rewrite so the parked alternatives stay parked and the stamp system is the shipped one |
| `docs/BRANDING.md` §4a        | ✅ Done — the collision, the searches and their results                                                                                    |
| `docs/BRANDING.md` §4b        | ✅ Done — the decision                                                                                                                     |
| `docs/BRANDING.md` §5.1, §5.3 | ✅ Done — the accent kept (M), the typeface settled (D)                                                                                    |
| `docs/BRANDING.md` §5.5       | "a waveform that animates on the logo" — delete it. Phase 3 step 5 removes the logo motion entirely                                        |
| `docs/BRANDING.md` §6         | The favicon/logo file map, which still says "pulse badge"                                                                                  |
| `docs/BRANDING.md` §6a        | Social preview is listed as blocked on #475. Unblock it and record the value                                                               |
| `docs/BRANDING.md` §7         | The checklist needs a **successor line**, not an edit — the waveform entry records what was true when it was ticked                        |
| `docs/BRANDING.md` glossary   | ✅ Done — audited end to end                                                                                                               |
| `docs/LOGO_IDEAS.md`          | ✅ Current. Keep it open: it is what makes the decision re-openable                                                                        |
| `docs/BRAND_REFRESH_PLAN.md`  | This file. **Close it out when Phase 5 ends** rather than leaving a half-ticked plan as the record                                         |

### Everything else that names the old mark

**`grep -rln "PulseMark\|pulse mark" --include="*.md"` is the check**, and it must come back empty except for the deliberate `_(retired)_` glossary entry and
§4a, which is written about the mark and needs it.

| Where                                      | Change                                                                           |
| ------------------------------------------ | -------------------------------------------------------------------------------- |
| `events-frontend/AGENTS.md` §Accessibility | Names `PulseMark` as the decorative-SVG example. Becomes `EjBadge` / `ClubStamp` |
| `README.md`                                | Add the **tagline stamp** above the badges. No logo today; #475 asks for one     |
| `docs/README.md`                           | ✅ Done — indexes the plan and LOGO_IDEAS                                        |
| `events-frontend/README.md`                | Check for component inventories that list `PulseMark`                            |
| `docs/PRODUCT_OVERVIEW.md`                 | Check — it inventories what the frontend does and may describe the hero          |

### The issue tracker, which `grep` cannot reach at all

This is the class §1 warns about: eight issue bodies survived the rename sweep, and one of them had teeth. **Sweep it before calling #475 done:**

```sh
gh issue list --state open --limit 400 --json number,title,body \
  --jq '.[] | select(.body != null and (.body | test("(?i)pulse mark|pulsemark"))) | "#\(.number)\t\(.title)"'
```

| Issue                                                                                                                        | What it needs                                                                                   |
| ---------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| [#475](https://github.com/enorm-labs/event-junkie/issues/475)                                                                | The decision landed differently from what it proposed — record the outcome before closing       |
| [#374](https://github.com/enorm-labs/event-junkie/issues/374)                                                                | Its note says the logo comes first and it starts from whatever #475 produces. Say what that was |
| [#377](https://github.com/enorm-labs/event-junkie/issues/377)                                                                | ✅ Commented — decided all-Geist; retitle, since only the correction remains                    |
| [#477](https://github.com/enorm-labs/event-junkie/issues/477)                                                                | Social preview was blocked on #475. It is not any more                                          |
| [#294](https://github.com/enorm-labs/event-junkie/issues/294)                                                                | The README hero screenshot — still sequenced last, but the chain above it has moved             |
| [#481](https://github.com/enorm-labs/event-junkie/issues/481)                                                                | Launch marketing references the logo; check what it assumes                                     |
| [#395](https://github.com/enorm-labs/event-junkie/issues/395), [#281](https://github.com/enorm-labs/event-junkie/issues/281) | Repo best-practices and health-file passes both touch the README and social preview             |

**And the settings pages, which are neither code nor tracker:** the GitHub **social preview** upload (§6a) and, once the domain resolves, the repository
**homepage** field. Nothing in the repository will ever remind you about these — §6a exists precisely because a value set in a settings page and written down
nowhere drifts.

---

## Phase 5 — The visual pass (#374)

Starts from the mark, and only then. Everything below is a refinement of a shipped theme rather than a rebuild — re-theming is a token edit in `main.css`
(ADR-010), which is what keeps this phase cheap.

| Area            | What it means here                                                                                                                                                                                                                                                                                                         | Reference |
| --------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- |
| **Colour**      | ✅ Settled ahead of this pass: **UV violet stays**. Re-examined against the monochrome identity and kept — red and green are spoken for semantically, and what clashed with the stamp was the glow rather than the hue. Open here only as a thing to _try_: printing the stamp itself in the accent on a secondary surface | §5.1      |
| **Type**        | ✅ Settled ahead of this pass (#377): **all-Geist**. The work it leaves is not a choice but a correction — add `@fontsource-variable/geist-mono`, define `--font-mono`, and wire or delete the dead `--font-heading` token                                                                                                 | §5.3      |
| **Spacing**     | A scale audit across the views, not a per-page nudge                                                                                                                                                                                                                                                                       | —         |
| **Components**  | Cards, badges, inputs, the filter bar — consistency against the tokens                                                                                                                                                                                                                                                     | §5.6      |
| **Iconography** | One family, one weight, sized against the type scale                                                                                                                                                                                                                                                                       | —         |
| **Imagery**     | Desaturate-at-rest is shipped in `EventCard.vue`. Open question is whether the duotone-tinted treatment is better than plain grayscale                                                                                                                                                                                     | §5.4      |
| **Motion**      | Card hover-lift and the live dot are shipped. The logo's motion is decided in Phase 3; this is the rest                                                                                                                                                                                                                    | §5.5      |

**Verify AA contrast in both modes for anything that touches colour**, and remember the constraint §5.1 records: `--destructive` must stay visually distinct
from the accent, which is what favoured violet over a red.

**Then #294 is unblocked** — the README hero screenshot, in dark mode, sequenced last precisely so it is taken once.

---

## Verification

- `/verify` — the full pre-PR sequence. For these branches the load-bearing parts are the frontend `type-check`, `lint`, `test:unit` and `test:e2e`
  (chromium), plus `scripts/format-markdown.sh check`, since every phase touches Markdown.
- **The e2e overflow guard in `e2e/smoke.spec.ts`** is the test most likely to catch a logo regression, because the header lockup is what it is guarding.
- **Look at it at 16 px.** No automated check in this repository will tell you the favicon turned to mud, and that is the failure mode a logo change actually
  has.
- **Both themes, and the dark one first** — dark is the default for new visitors (§5.2), so a mark that only works on white is broken for most of the traffic.

## Done, when

This plan is finished when all four are true, and not before:

1. **`grep -rln "PulseMark\|pulse mark" --include="*.md"` returns only the deliberate survivors** — §4a, which is written about the mark, and the
   `_(retired)_` glossary entries. Anything else is a leftover.
2. **No asset in `docs/branding/` differs from what the app actually serves.** These files are the source; `favicon.svg`, the raster icons and the social
   previews are derived from them. A drift between the two is the failure this plan exists to prevent, since it is exactly how the old mark survived in three
   places at once.
3. **The tracker sweep above is clean**, and #475 records what was decided rather than what it proposed — the two are not the same, and the issue is the more
   durable of the two records.
4. **The settings pages are set** — social preview, and the homepage field once the domain resolves.

**Then close this file rather than leaving it.** A half-ticked plan is a worse record than either a finished one or none: it reads as work in progress
indefinitely, and the next person cannot tell which unticked boxes are pending and which were overtaken. When Phase 5 ends, either mark it superseded and point
at `BRANDING.md`, or delete it and let §4a–§4b carry the history — they already do.

## Open decisions

| #   | Decision                                                                          | Status                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| --- | --------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| A   | Which direction wins                                                              | ✅ **Decided 2026-08-23** — badge + wordmark + stamp, BRANDING §4b                                                                                                                                                                                                                                                                                                                                                                                   |
| B   | The favicon badge's fill                                                          | ✅ **Decided 2026-08-23 — flat ink, no gradient, no accent ground.** Monochrome, per §4. It needs opaque fills rather than the in-app knockout, but no colour: the letters carry it where the silhouette cannot                                                                                                                                                                                                                                      |
| C   | Does the mark animate at all                                                      | ✅ **Decided** — no. Phase 3 step 5 deletes the keyframes                                                                                                                                                                                                                                                                                                                                                                                            |
| D   | Display typeface versus all-Geist (#377)                                          | ✅ **Decided 2026-08-23 — all-Geist**, and the question turned out to be a different one. The hero `<h1>` is now `sr-only` so the display moment moved into artwork, and Rubik Distressed floors at 96 px so it cannot be live type anyway. What the audit did find: `--font-mono` is undefined, so the eyebrow device renders in a different system face per OS — pin it to **Geist Mono**. And `--font-heading` is a token nothing reads. See §5.3 |
| E   | Whether the register searches change this from a mark question to a name question | ✅ **Answered 2026-08-23** — no. No hits on any register or image search; §4a bis                                                                                                                                                                                                                                                                                                                                                                    |
| F   | Does the stamp go in the home hero, or wait for #374                              | ✅ **Decided 2026-08-23 — it goes in**, with the tagline, and the live tagline `<p>` is removed. That makes the caption localised artwork, so the hero picks the stamp by locale                                                                                                                                                                                                                                                                     |
| G   | Slab serif or Helvetica for the badge                                             | ✅ **Superseded 2026-08-23** by I — neither. Rubik Distressed                                                                                                                                                                                                                                                                                                                                                                                        |
| H   | Square corners instead of rounded                                                 | ✅ **Decided 2026-08-23** — square, on every badge and the lockup. BRANDING §4b carries the reasoning                                                                                                                                                                                                                                                                                                                                                |
| I   | Which typeface, and on what licence                                               | ✅ **Decided 2026-08-23** — **Rubik Distressed**, SIL OFL 1.1, so commercial and logo use are permitted with nothing to buy. Dharma Punk and RUBBER STAMP are personal-use-only                                                                                                                                                                                                                                                                      |
| J   | Split badge or plain badge for display surfaces                                   | ✅ **Decided 2026-08-23** — split, at 96 px and up. Below that the drawn geometric badge, since Rubik Distressed is illegible at 32 px                                                                                                                                                                                                                                                                                                               |
| K   | Does the club stamp lockup move to Rubik Distressed too                           | ✅ **Decided 2026-08-23** — yes, for the wordmark. _BERLIN_ stays clean, because it degrades to specks long before the wordmark does                                                                                                                                                                                                                                                                                                                 |
| L   | Keep the split badge as an alternative, or retire it                              | ✅ **Retired 2026-08-23** — along with the two pre-typeface geometric badges. Recoverable from git                                                                                                                                                                                                                                                                                                                                                   |
| M   | Does the UV violet accent survive the monochrome identity                         | ✅ **Decided 2026-08-23 — kept.** `--destructive` (sold out, cancelled) and `--success` (free) already own red and green, which rules out the stamp-native inks and the Trainspotting orange with them. And what clashed was the glow, not the hue — flat violet on near-black is the Berlin flyer convention, one spot ink on monochrome. Violet is itself a classic stamp-pad ink. See §5.1                                                        |
