# Implementation Plan — Brand refresh (mark replacement, then the visual pass)

Execution plan for [#475](https://github.com/enorm-labs/event-junkie/issues/475) (_Replace the pulse mark_) and
[#374](https://github.com/enorm-labs/event-junkie/issues/374) (_Improve branding and visual design_), which are one piece of work in two halves rather than two
issues that happen to be adjacent. The reasoning behind the replacement — the collision itself, the directions considered, and why one of them is recommended —
lives in [BRANDING.md](BRANDING.md) §4, §4a and §4b and is **not repeated here**. This document is the ordered list of what to do about it.

**The chain is #475 → #374 → [#294](https://github.com/enorm-labs/event-junkie/issues/294)** (README hero screenshot), and
[#377](https://github.com/enorm-labs/event-junkie/issues/377) (display typeface) is settled inside #374 rather than separately.

**Why that order and not the other one.** #374 was written first and originally owned the logo. The dependency reversed on 2026-08-13: the mark stopped being a
refinement and became a replacement, and a visual pass that chooses a palette, a motion language and a hero treatment around a mark that is being deleted does
the work twice. The home hero is the clearest case — its ambient glow is centred on the mark and only reads correctly if the mark is its light source, so the
glow cannot be tuned before the thing it lights exists.

**One PR per phase.** Phases 1–4 are #475 and should land together or in close succession, because a half-replaced identity — new header, old favicon — is worse
than either state. Phase 5 is #374 and is deliberately a separate branch.

---

## What must not change

Worth stating up front, because each of these looks like part of the same family and is easy to delete by association.

| Survives untouched                                                                          | Why it looks related, and is not                                                                                                    |
| ------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| **Eyebrow labels** — `SectionLabel.vue`, `--tracking-eyebrow`, the mono uppercase `TONIGHT` | A typographic device from the editorial-listings direction. Unrelated to the logo; the name collision with "pulse" is coincidental. |
| **The wordmark** — "Event Junkie", accent on "Junkie"                                       | #475 replaces the _mark_, not the name. The lockup is rebuilt around a new mark, not rewritten.                                     |
| **The naming rule** (§1) and the voice (§3)                                                 | Brand foundation. Decided, and nothing here reopens it.                                                                             |
| **`GitHubMark.vue`**                                                                        | A third-party glyph that happens to sit next to ours in the footer.                                                                 |

**Non-goals for #475 specifically:** no palette change, no type change, no layout change. If the winning direction implies one — the Trainspotting poster
language would replace the UV-violet accent §5.1 records as shipped — that lands in #374 and is called out there rather than absorbed quietly into a logo PR.

---

## Phase 0 — Evidence, and the name check

Mostly done. What remains is hand-work at a browser, and it is the only part of this plan that cannot be automated.

- [x] **Both marks read from source and compared field by field.** BRANDING.md §4a, dated 2026-08-19. The comparison is construction, caps, joins, gradient
      endpoints and lockup role — not a visual impression, which is what makes it usable later.
- [x] **A general web search on the wordmark.** No product, app or company trading as _Event Junkie_ in the German or EU events market. Weak evidence, recorded
      as weak evidence.
- [ ] **Capture the side-by-side image.** §4a records the comparison as a table; the issue also asks for a picture. Render both SVGs at the same height into one
      PNG, attach it to #475, and commit it as `docs/branding/collision-2026-08-19.png`. An attachment on an issue is not a durable record on its own.
- [ ] **The three register searches**, on the wordmark _Event Junkie_ and the closed-up _Eventjunkie_, in **classes 9, 41 and 42**. Free, minutes each, and
      session-based applications rather than queryable endpoints — so they are done by hand and the result is pasted into §4a with the date:
    - [DPMAregister](https://register.dpma.de/DPMAregister/marke/einsteiger) — German national marks
    - [EUIPO eSearch](https://www.euipo.europa.eu/en/search) — EU trade marks
    - [TMview](https://www.tmdn.org/tmview/) — both of the above plus WIPO in one query

**A hit in class 9 or 41 changes this plan's scope from a mark to a name**, which is why the searches sit in Phase 0 and not somewhere convenient. A hit in an
unrelated class is not a problem and should be recorded as checked-and-fine, so nobody re-runs it in six months.

---

## Phase 1 — Decide the mark

**This is the one open decision that blocks everything below it, and it is the owner's.** BRANDING.md §4b recommends **the overflowing calendar cell** and it is
already drawn and committed as [`docs/branding/mark-proposal-overflow.svg`](branding/mark-proposal-overflow.svg) — geometry notes included, so it can be redrawn
rather than only nudged. The runner-up is **type-only**; the rejected neighbour is the scan line. The argument for each is in §4b.

The proposal is a proposal. Before it is adopted, three checks that cost minutes and are the ones that actually kill logos:

1. **Render it at 16 px, 32 px and 180 px on a real screen**, light and dark, and look at it rather than at the SVG. The hanger ticks and the 3-unit stroke are
   the parts most likely not to survive; §4b's own notes flag the ticks as the first thing to drop.
2. **Reverse image search the rendered mark**, the same way the collision was found. A calendar square is a more crowded space than the §4b argument admits —
   the check is cheap and the whole point of this issue is that it was not run last time.
3. **Draw the runner-up too, if it is genuinely live.** Deciding between one drawn option and one described option is not a comparison. If type-only is a real
   candidate, it needs a rendered lockup before the choice, not after.

**If none of the directions convinces:** commission it. §475 costs this out — a few hundred euro buys a vector file and the variants, which are most of the
actual work, for the one asset that has to survive years of use. That is a legitimate outcome of this phase and it should be taken deliberately rather than by
drifting.

**Whatever wins is held to §4's principles**, which are not the reason the pulse mark is being replaced and therefore still apply: monochrome-first, legible at
16 px, one strong silhouette, shipped as inline-able SVG.

---

## Phase 2 — The asset set

The mark is one file; the identity is eleven. This is where most of the work is, and it is the part that is easy to underestimate because the interesting
decision already happened in Phase 1.

| Asset                   | File                                            | Notes                                                                                                                                    |
| ----------------------- | ----------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| Source mark             | `docs/branding/mark-<name>.svg`                 | The canonical drawing, `currentColor`, no gradient. Everything below is derived from it                                                  |
| App component           | `events-frontend/src/components/<Name>Mark.vue` | Replaces `PulseMark.vue`. Inline SVG, `aria-hidden`, sized by a height utility                                                           |
| Lockup                  | `src/components/BrandLogo.vue`                  | Mark + wordmark; keeps the `sm:` collapse to mark-only and the `sr-only` wordmark                                                        |
| Favicon (vector)        | `public/favicon.svg`                            | The badge: rounded square, mark reversed out. Today's is a violet gradient — see the note below                                          |
| Favicon (raster)        | `public/favicon.ico`                            | 32 px. Generated, not drawn                                                                                                              |
| iOS home screen         | `public/apple-touch-icon.png`                   | 180×180. iOS does not render SVG favicons, which is why this file exists                                                                 |
| Social preview (GitHub) | `docs/branding/social-preview.png`              | 1280×640. Uploaded in repository settings; **empty today** ([#477](https://github.com/enorm-labs/event-junkie/issues/477), BRANDING §6a) |
| Social preview (site)   | `events-frontend/public/og-image.png`           | 1200×630, plus the `og:image` / `twitter:image` tags — **neither exists today**, see below                                               |
| README logo             | `README.md`                                     | No logo today; #475 asks for one                                                                                                         |
| Reversed / single-ink   | derived                                         | The glossary term already exists; the file has to as well, for contexts that cannot render a gradient                                    |
| Monochrome dark variant | derived                                         | The theme defaults to dark (§5.2), so this is the _common_ case rather than the fallback                                                 |

**Two findings worth landing while here.**

**The site has no `og:image` at all.** `index.html` sets `og:title`, `og:description` and `twitter:card` — and the card is `summary`, not
`summary_large_image`. Every link to `event-junkie.de` on Slack, X or Discord currently unfurls as text. That is the same gap #477 records for the GitHub social
preview, one surface over, and it is not written down anywhere. Fixing it is two meta tags and one PNG, and it is only sensible to do it once the mark exists —
which is exactly why it belongs in this phase and not in a tidy-up later.

**The favicon badge is a violet gradient, and the mark is not.** §4's principles say monochrome-first and the proposal drawing obeys them; the shipped badge
does not. Whether the badge keeps a gradient is a real choice — an app icon is the one place a flat mark can afford colour — but it should be made rather than
inherited from the mark being deleted. If the gradient stays, it must not stay _violet-to-magenta left-to-right_, because that is half of what §4a identifies as
the collision.

**On generating the raster files.** There is no script for this today; `apple-touch-icon.png` was made by hand. Either is fine, but if it is done by hand, write
down the command — `rsvg-convert`, `resvg` or ImageMagick over the source SVG — in a comment next to the asset, so the next person regenerating a 180 px icon is
not reverse-engineering a padding value from a PNG.

---

## Phase 3 — Land it in the frontend

Ordered so the tree compiles at every step. The mark component is replaced first because everything else imports it.

1. **Add the new mark component** and delete `PulseMark.vue`. A rename rather than a parallel life — two marks in the tree for a week is how one of them ships
   by accident.
2. **`BrandLogo.vue`** — swap the import. The lockup's behaviour is unchanged: the wordmark collapses below `sm` so the header stays inside a ~390 px viewport
   (there is an overflow guard in `e2e/smoke.spec.ts` that enforces this), and it stays in the a11y tree as `sr-only` so the link's accessible name remains
   "Event Junkie". `AppFooter.vue` passes `always-show-wordmark`; that stays.
3. **`HomeView.vue`** — the hero. The ambient glow is a radial `var(--primary)` centred on the mark specifically, not on the hero, and it only makes sense if
   the mark is its light source. A calendar square is a different silhouette from a 3.9:1 horizontal line, so **the glow's 560×300 box is wrong for it** and has
   to be re-tuned rather than carried over. Keep the property that earns it: no `overflow` clip anywhere, so it fades to transparent on all sides.
4. **`main.css`** — the motion. `ej-draw` and `ej-beat` (lines ~151–184) are pulse-specific: `ej-draw` depends on `pathLength="1"` and a single continuous
   stroke, which a multi-path calendar mark does not have. Replace them with motion that suits the new silhouette, or drop the animation entirely — a mark that
   does not need to move is not a worse mark. **Whatever replaces them stays gated behind `prefers-reduced-motion: reduce`**, which is not optional here
   (accessibility is a first-class project value, and both current keyframes already do it).
5. **`index.html`** — the icon links are already correct (`favicon.svg`, `favicon.ico`, `apple-touch-icon.png`); only the files behind them change. Add
   `og:image` / `twitter:image` and switch `twitter:card` to `summary_large_image` (Phase 2).
6. **Tests.** There is no `BrandLogo.spec.ts` or `PulseMark.spec.ts` today — `AppFooter.spec.ts` covers the footer that renders the lockup. Add a component spec
   for the lockup asserting the accessible name and the `sm:` collapse, because both are behaviours that a logo swap can silently break and neither is visible
   in a screenshot.

---

## Phase 4 — Docs and repository surfaces

The part a `grep` for `PulseMark` will not finish for you.

| Where                                      | Change                                                                                                                                                                                                                           |
| ------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `docs/BRANDING.md` §4                      | Direction #1 is recorded as **shipped**. It is not. Rewrite so the parked alternatives stay parked and the new mark is the shipped one                                                                                           |
| `docs/BRANDING.md` §4a                     | Add the register-search results and the side-by-side image from Phase 0                                                                                                                                                          |
| `docs/BRANDING.md` §4b                     | Currently **needs a decision**. Record the decision and why, including if the answer was "commission it"                                                                                                                         |
| `docs/BRANDING.md` §5.5                    | "a waveform that animates on the logo" — replace with whatever Phase 3 step 4 decided                                                                                                                                            |
| `docs/BRANDING.md` §6                      | The favicon/logo file map, which says "pulse badge"                                                                                                                                                                              |
| `docs/BRANDING.md` §6a                     | Social preview is listed as blocked on #475. Unblock it and record the value                                                                                                                                                     |
| `docs/BRANDING.md` §7                      | The checklist item "Prototyped the waveform wordmark and shipped it" needs a successor line, not an edit — the history is true                                                                                                   |
| `docs/BRANDING.md` glossary                | **Pulse mark** and **Favicon badge** define the deleted thing. **Ambient glow** names the pulse mark as its source. **Lockup**, **Reversed / single-ink** and **Wordmark** survive as concepts and need their references updated |
| `docs/README.md`                           | Add this plan to the product table, next to BRANDING.md                                                                                                                                                                          |
| `events-frontend/AGENTS.md` §Accessibility | Line ~443 names `PulseMark` as the decorative-SVG example                                                                                                                                                                        |
| `README.md`                                | Add the logo above the badges                                                                                                                                                                                                    |
| GitHub repository settings                 | Upload the social preview. This is a click in a settings page, not a commit — which is exactly why §6a exists to record it                                                                                                       |

**On §4a and §7: do not rewrite history.** §4a is the record that the collision was noticed before launch, and §7's checklist records what was true when it was
ticked. Both keep their tense. This is the same rule the naming rename learned the hard way (§1): sentences that exist to record a state get a successor, not an
edit.

---

## Phase 5 — The visual pass (#374)

Starts from the mark, and only then. Everything below is a refinement of a shipped theme rather than a rebuild — re-theming is a token edit in `main.css`
(ADR-010), which is what keeps this phase cheap.

| Area            | What it means here                                                                                                                                                                                                                             | Reference |
| --------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- |
| **Colour**      | UV violet is shipped and AA-verified. Revisit **only** if the winning mark implies a different accent — the Trainspotting direction does, and that would be a replacement, not a tune                                                          | §5.1      |
| **Type**        | Settles [#377](https://github.com/enorm-labs/event-junkie/issues/377): a display face for headings versus staying all-Geist. Body/UI stays Geist either way. The cost of a display face is a font on the critical path and a fallback decision | §5.3      |
| **Spacing**     | A scale audit across the views, not a per-page nudge                                                                                                                                                                                           | —         |
| **Components**  | Cards, badges, inputs, the filter bar — consistency against the tokens                                                                                                                                                                         | §5.6      |
| **Iconography** | One family, one weight, sized against the type scale                                                                                                                                                                                           | —         |
| **Imagery**     | Desaturate-at-rest is shipped in `EventCard.vue`. Open question is whether the duotone-tinted treatment is better than plain grayscale                                                                                                         | §5.4      |
| **Motion**      | Card hover-lift and the live dot are shipped. The logo's motion is decided in Phase 3; this is the rest                                                                                                                                        | §5.5      |

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

## Open decisions

| #   | Decision                                                                          | Owner | Blocks                   |
| --- | --------------------------------------------------------------------------------- | ----- | ------------------------ |
| A   | Which direction wins — overflow calendar cell, type-only, or commission it        | Owner | Everything below Phase 1 |
| B   | Does the favicon badge keep a gradient, and if so which one                       | Owner | Phase 2                  |
| C   | Does the mark animate at all, and if so how                                       | Open  | Phase 3 step 4           |
| D   | Display typeface versus all-Geist (#377)                                          | Owner | Phase 5                  |
| E   | Whether the register searches change this from a mark question to a name question | —     | Phase 0 answers it       |
