# Branding — Event Junkie

> Status: **living document** (started 2026-07-02). The brand foundation (name, tagline, voice) is
> decided; the **logo** and **visual-design** sections are _ideas to explore_, not committed decisions.
> Related: [VISION_ROADMAP_IDEAS.md](VISION_ROADMAP_IDEAS.md) · [ADR-010 (styling framework)](adr/ADR-010_FRONTEND_STYLING_FRAMEWORK.md).

## 1. Brand foundation

|                      |                                                                                                                                                                           |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Public name**      | **Event Junkie** (domain: `event-junkie.de`)                                                                                                                              |
| **Identifier form**  | **`event-junkie`** (repository, config, infrastructure — one name everywhere, see the naming rule below)                                                                  |
| **Tagline**          | _Can't get enough of Berlin_ · DE: _Von Berlin kriegst du nie genug_ — the German line is **shipping but not signed off**; see [§8](#8-localisation--the-german-register) |
| **Positioning line** | _The event app Berlin deserves_ — **not the tagline**; see below                                                                                                          |
| **One-liner**        | Your always-fresh feed of what's on across Berlin's venues — concerts, club nights, festivals, and the odd quiz night.                                                    |
| **Scope**            | All music, every genre and room size — not just techno. Berlin-only for now. Which _kinds_ of event: [EVENT_SCOPE.md](EVENT_SCOPE.md)                                     |

### Tagline vs. positioning line

Two lines exist and they do different jobs. Keeping them apart is deliberate — using either in the other's place is how the voice goes muddy.

|                   | _Can't get enough of Berlin_          | _The event app Berlin deserves_           |
| ----------------- | ------------------------------------- | ----------------------------------------- |
| **Role**          | Tagline                               | Positioning line                          |
| **Speaks about**  | the **user** — it flatters them (§3)  | the **product** — what it is trying to be |
| **Where it goes** | hero, page titles, OG tags, marketing | About page, README, a pitch               |
| **Register**      | playful, in-character                 | plain, sincere                            |

The positioning line is **not a second tagline**, and should not appear in the hero, the page title or the OG tags. It reads as a claim rather than a wink, and
the brand's whole premise (§2) is that the app flatters the user rather than itself — a claim in the hero would undercut the tagline sitting next to it.

**No "that" (decided 2026-08-08).** The line is _"The event app Berlin deserves"_, not _"…the event app **that** Berlin deserves"_. The relative pronoun is
optional in English when the relative clause relativises the object, and dropping it is what makes the line scan as a claim rather than as a sentence someone
started. It also lets the Batman cadence it borrows land unaltered — _"the hero Gotham deserves"_ has no _that_ either, and the half-echo is doing work the
extra syllable would blunt. **Do not add it back.** (It was written with _that_ until this date; if you find that spelling anywhere, it is a leftover.)

**German:** the About page already ships it inside a sentence — _"Weil ich die Event-App bauen wollte, die Berlin verdient."_ That works because the line sits
in prose there. A standalone German form (_"Die Event-App, die Berlin verdient"_) is **not signed off**, and would need the same written-not-translated
treatment as everything else in [§8](#8-localisation--the-german-register) before it goes anywhere on its own. Note that the omission above does not transfer:
German has no zero relative pronoun, so _"die"_ stays in either form. The two languages simply differ here — that is not a drift between them to be "fixed".

### Naming rule

**One name everywhere: Event Junkie.** User-facing surfaces (page titles, home hero, About copy, OG tags, the domain and marketing) use the display form **Event
Junkie**; everything else — the repository, Gradle settings, READMEs, ADRs, developer docs, infrastructure, the scraper's User-Agent — uses **`event-junkie`** in
identifier form.

**Reversed 2026-08-11 ([#427](https://github.com/enorm-labs/event-junkie/issues/427)).** Until then an internal codename, _Event Checker_, was kept deliberately
distinct from the public brand, with infrastructure as a documented exception on the grounds that it is read next to a _domain_ rather than next to the source.
The exception turned out to be the rule. Once `infra/` landed, most internal surfaces sat beside an operational one, and carrying two names bought a distinction
nobody had needed while charging a translation step every time the two met. **The codename is retired — don't reintroduce it**, and if you find _Event Checker_
anywhere, it is a leftover rather than a deliberate survival.

**What keeps its own name, because it never carried either one:** the Gradle modules (`events-core`, `events-bff`, `events-importer`), the Kotlin package
`de.norm.events`, and the `events` database schema ([ADR-004](adr/ADR-004_DEDICATED_DATABASE_SCHEMA.md)). Renaming those would be a different change with a real
cost in history and migrations, and no benefit — none of them says either name.

**A rename is not done when the tree is clean.** #427 swept 51 files the same day this rule was reversed, and left two kinds of thing behind. Both are worth
knowing about, because neither is the kind of thing a `grep` for the old name will ever show you.

**One: the issue tracker is a surface, and `grep` cannot reach it.** Eight issue bodies still carried the old name. Most were stale URLs, harmless because
GitHub redirects them — but #426's checklist carried `LABEL org.opencontainers.image.source=…/event-checker`, and _that_ one had teeth: a stale label still
resolves through the redirect, while the package-to-repository link is matched on the **canonical** name, so the published container package would have silently
failed to attach. One `good first issue` was worse than stale — the rename had inverted its premise, so it now instructed the next contributor to preserve a
split that no longer existed.

**Two: sentences whose meaning was the distinction.** A mechanical replace renames identifiers reliably and reads nothing. Three documents explained the old
split in prose — _"Event Junkie is the public name; Event Checker is the internal name"_ — and the sweep rewrote **both halves**, leaving a tautology that named
the same thing twice and then claimed the two referred to the same thing. Grep cannot find these, because the old name is gone; they are only visible by reading.
When a name is retired, the sentences that existed to _contrast_ the two names have to be deleted or rewritten, not renamed.

The check that covers the tracker:

```sh
gh issue list --state all --limit 600 --json number,title,state,body \
  --jq '.[] | select(.body != null and (.body | test("event-checker"))) | "#\(.number)\t[\(.state)]\t\(.title)"'
```

Expect two survivors and leave them: [#392](https://github.com/enorm-labs/event-junkie/issues/392) decided the rename and
[#427](https://github.com/enorm-labs/event-junkie/issues/427) carried it out, so both name the old codename because naming it is what they are for. Merged pull
request bodies keep it too — they are an accurate record of what the repository was called at the time, and rewriting them would make the history less true,
not more.

## 2. The concept — why "Junkie"

The name works because a junkie's traits map cleanly onto what the product does:

| Junkie trait                             | Product truth                                                               |
| ---------------------------------------- | --------------------------------------------------------------------------- |
| Always chasing the next **hit**          | A "hit" is both a drug hit _and_ a music hit — every event is the next one. |
| Always knows where to **score**          | The app _is_ the source: the one place that always knows what's on.         |
| Wired into the scene, ahead of the crowd | An always-fresh feed so you know before it sells out.                       |
| Feeds a **habit**, comes back nightly    | Discovery you return to; you never come up dry.                             |

**Metaphor to lean on:** the user is the _junkie_; the app is quietly the _dealer/source_. Name the audience (Junkie); let "source / score / hit / fix / feed
the habit" show up in the _copy_. Words that carry the double meaning — **hit**, **score** — are the strongest.

## 3. Voice & tone

Playful, self-aware, a little nocturnal — never actually about drugs. It flatters the user ("you can't get enough") rather than the app. Confident and
in-the-know, but warm, not edgy-for-its-own-sake.

**Do:** short, punchy, wink-y; nightlife/music vocabulary; treat FOMO as the enemy. **Don't:** glorify substance abuse, be crude, or over-explain the joke. Keep
it PG-13 and inclusive.

Great places to let the voice show — **microcopy**:

- Empty state: _"Nothing on tonight? In Berlin? Unlikely — try a wider date range."_
- End of list: _"That's the lot. Go touch some grass (or don't)."_
- 404 / not found: _"This one's gone. Like last call — you snooze, you lose."_
- Loading: _"Scoring the latest…"_

Tagline alternatives explored (kept for reference / A-B testing): _Never miss a hit_ · _Highly addictive_ · _Feed the habit_ · _Your dealer for Berlin
nightlife_ · _Know before the crowd_.

## 4. Logo — directions to explore

**Done:** direction #1 below (pulse / waveform wordmark) was prototyped and shipped — the pulse mark is the favicon (`events-frontend/public/favicon.svg`) and,
paired with the wordmark, the header lockup (`src/components/BrandLogo.vue`, collapsing to just the mark on mobile). The other directions stay parked as
alternatives. The principles that guided it:

- **Monochrome-first.** The UI theme is currently all-grayscale; the mark must read in a single ink and invert cleanly for dark mode. Design in black/white, add
  the accent (§5) as a highlight only.
- **Favicon-legible.** It has to survive at 16–32 px and as an emoji-style tab/app icon. Favour one strong silhouette.
- **Ship as SVG**, inline-able (the artifact/title system and CSP prefer self-contained assets).

Candidate directions (ordered by how well they fuse _music + the "junkie" concept_):

1. **Pulse / waveform wordmark** _(recommended lead)._ "Event Junkie" set in the site font, with a small ECG-heartbeat / audio-waveform line replacing the
   crossbar of a letter or underlining the word. Fuses **heartbeat + music waveform + "never miss a beat" + addiction**. The waveform alone becomes the favicon.
2. **"EJ" monogram.** A tight ligature of E + J for the app icon / favicon; pairs with the wordmark for full-lockup use.
3. **Pin + play.** A Berlin map-pin whose "hole" is a play triangle or a music note — literally "events at venues." Very legible small; a touch more literal /
   less witty.
4. **Wristband / ticket stub.** A club wristband or torn ticket — instant "nightlife entry." Characterful, but busier at favicon size.
5. **The live dot.** A single filled circle — a "hit," a record, a dot on a calendar day — that **pulses**
   when something's on tonight. Minimal, animatable, unbeatable as a favicon; leans on motion for meaning.

**Shipped:** #1 (waveform wordmark) + its standalone favicon glyph, in both inks — **and being replaced, see §4a.**

### 4a. The collision, recorded — 2026-08-19 (#475)

The pulse mark is close enough to **sprintpulse.io**'s that it cannot stay. Recorded here rather than remembered, because if a trademark question ever follows,
_"we noticed and changed it before launch"_ is a materially better position than a recollection.

**Both marks were read from their own source**, not compared by eye:

|                    | Event Junkie (`PulseMark.vue`)                          | SprintPulse (`cdn.sprintpulse.io/assets/logo-7927021e.svg`) |
| ------------------ | ------------------------------------------------------- | ----------------------------------------------------------- |
| Construction       | one `<path>`, `fill="none"`                             | one `<path>`, `fill="none"` (plus an arrowhead)             |
| Line               | ECG/soundwave: flat lead-in, spikes, flat lead-out      | ECG/heartbeat: flat lead-in, spikes, flat lead-out          |
| Caps / joins       | `round` / `round`                                       | `round` / `round`                                           |
| Fill               | horizontal `linearGradient`, `x1=0 → x2=1`              | horizontal `linearGradient`, `x1=0% → x2=100%`              |
| Gradient endpoints | `#823feb` → `#a24df2` → `#d528ce` (violet → magenta)    | `#4f46e5` → `#9333ea` → `#ec4899` (indigo → pink)           |
| Role               | mark left of the wordmark, collapsing to the mark alone | mark left of the wordmark                                   |

**The collision is not "both are pulse lines".** It is that both are a single round-capped stroke of an ECG line, on a left-to-right violet-to-pink gradient,
sitting to the left of a wordmark — the same construction, the same palette direction and the same lockup. The differences are the aspect ratio (ours is
flatter, ~3.9:1 against ~1.6:1) and their arrowhead. That is not enough distance.

**The wordmark itself — a preliminary look, and it is not a trademark search.** A general web search on 2026-08-19 turned up no product, app or company trading
as _Event Junkie_ or _Eventjunkie_ in the German or EU events market; the nearest neighbours are unrelated (_Startup Junkie_, US, different class). That is
weak evidence and worth exactly what it cost: it rules out the obvious, and it says nothing about the registers.

**The register search still has to be done by hand**, because DPMAregister and TMview are session-based applications rather than queryable endpoints. Three
searches, minutes each, all free:

- [DPMAregister](https://register.dpma.de/DPMAregister/marke/einsteiger) — German national marks, `Event Junkie` and `Eventjunkie`
- [EUIPO eSearch](https://www.euipo.europa.eu/en/search) — EU trade marks
- [TMview](https://www.tmdn.org/tmview/) — both of the above plus WIPO in one query

Classes that matter here are **9** (software/apps), **41** (entertainment, arranging events) and **42** (SaaS). A word mark in an unrelated class is not a
problem; one in 9 or 41 is.

### 4b. The direction, with a recommendation — **needs a decision**

Beyond the four still parked above, #475 raises the Trainspotting idea and four others. On Trainspotting: the cultural fit is real, and the execution that
survives both cautions is the film's **poster language** (orange-and-black, mixed-weight condensed type, numbered character strip, stark white ground) rather
than its characters — a face breaks §3's _"never actually about drugs"_ rule outright, and it is also a specific actor's likeness.

**Recommended: the overflowing calendar cell.** A single date square with more entries than fits, spilling past its edge. Drawn and committed as
[`docs/branding/mark-proposal-overflow.svg`](branding/mark-proposal-overflow.svg) so the choice is concrete rather than described.

Why it wins on this project's own criteria rather than on taste:

- **It is the only direction about _events_.** Every other candidate here is about music or about the pun. The product is a calendar of what is on, and the
  thing it does better than its neighbours is coverage — which is precisely what "more than fits" draws.
- **It draws the tagline.** _Can't get enough of Berlin_ is a statement about volume, and this is that statement as a silhouette.
- **It is furthest from where we are leaving.** Waveform, heartbeat, soundbar and equaliser are one neighbourhood, and it is a crowded one — the collision
  above is what a crowded neighbourhood costs.
- **It satisfies §4's principles without needing a designer to start.** Geometric, monochrome, one silhouette, legible at 16 px, and hand-authorable as SVG —
  which is how the current mark was made.

**Runner-up: type-only.** No mark at all, "Event Junkie" set with one deliberate typographic move. Cheapest, ages best, cannot collide with anybody's icon, and
removes the favicon constraint by using a single letter as the app icon. It is the right answer if the honest conclusion is that this project should not be
spending decisions on a logo before launch — and that is a legitimate conclusion.

**Not recommended: the scan line.** A cropped barcode is a horizontal-stripe silhouette, which is the same visual family as an equaliser — it would be trading
one crowded neighbourhood for a neighbouring one.

**The execution plan** — every file that changes, in order, plus the surfaces a `grep` for `PulseMark` will never reach — is in
[BRAND_REFRESH_PLAN.md](BRAND_REFRESH_PLAN.md). It covers [#475](https://github.com/enorm-labs/event-junkie/issues/475) and the visual pass
([#374](https://github.com/enorm-labs/event-junkie/issues/374)) that starts from whatever this section decides.

## 5. Website / visual design — ideas

Grounded in the real stack: **Tailwind CSS v4 + shadcn-vue**, **Geist** type, **oklch** CSS-variable tokens in `events-frontend/src/assets/main.css`, dark mode
via the `.dark` class (see ADR-010). Re-theming is a token edit, so most of the below is low-cost to try.

### 5.1 Colour — introduce ONE electric accent

**Done:** the **UV violet** row below was applied to `--primary` / `--accent` / `--ring` (and the matching sidebar tokens), keeping everything else neutral so
the accent reads like a spotlight in a dark room — AA-verified in both modes. The other rows are kept as alternatives. Candidate accents (drop-in oklch):

| Direction              | Vibe                         | Light `--primary`      | Dark `--primary`       |
| ---------------------- | ---------------------------- | ---------------------- | ---------------------- |
| **UV violet** _(rec.)_ | Club blacklight, after-hours | `oklch(0.55 0.24 295)` | `oklch(0.72 0.20 295)` |
| Electric magenta       | Neon, flyer-pink             | `oklch(0.60 0.25 350)` | `oklch(0.72 0.21 350)` |
| Acid green             | Rave, high-energy            | `oklch(0.72 0.22 150)` | `oklch(0.80 0.20 150)` |
| Berlin red             | Bold, editorial              | `oklch(0.58 0.22 25)`  | `oklch(0.70 0.19 25)`  |

Notes: keep `--background`, `--card`, `--muted`, borders neutral. Verify **WCAG AA** contrast for text on accent and accent on background in _both_ modes
(accessibility is a first-class project value). The existing red `--destructive` must stay visually distinct from any red-ish accent — favours violet/magenta.

### 5.2 Dark-mode-first

Nightlife skews dark. **Done:** dark mode is now the **default for first-time visitors**, set by the pre-paint script in `index.html` so there's no flash. An
explicit light choice is remembered in
`localStorage` and always wins on later visits. The default is unconditional (not gated on
`prefers-color-scheme`) — a deliberate brand call; revisit if it proves user-hostile for light-OS users. The accent is tuned to glow on the dark surface.

### 5.3 Typography

- **Body / UI:** **Geist** — now actually rendering and **self-hosted** via `@fontsource-variable/geist`
  (imported in `main.ts`); the render-blocking Google Fonts request is gone. (A shadcn-scaffold name mismatch had it silently falling back to a system font
  until that was fixed.)
- **Display / hero:** _(open)_ consider a characterful face for big headings (a tight grotesque, or a mono for a "listings/terminal" edge) to add nightlife
  personality; keep Geist for everything functional.

### 5.4 Imagery

Event/venue photos are the hero content but come from many scraped sources, so they clash. Apply a **consistent treatment** — grayscale or a duotone tinted with
the brand accent on cards, revealing full colour on hover / detail pages. Cohesive look, and it makes the accent do double duty.

### 5.5 Motion (subtle)

`tw-animate-css` is available. Ideas: a gently **pulsing "live tonight" dot**, soft card hover-lift, a waveform that animates on the logo. Always gate behind
`prefers-reduced-motion: reduce`.

### 5.6 Page-level notes

- **Home:** lead with _tonight / this week_ — the "next fix." Hero = wordmark + tagline (already the title).
- **Events:** filter-forward (genre/type already exist); make "what's on this weekend" a one-tap default.
- **Calendar:** the signature screen (ADR-011) — brand the "has events" day markers with the accent.
- **Detail pages:** editorial layout; big image, lineup, venue — the place to reveal full-colour imagery.
- **Empty/404/loading:** carry the §3 voice.

## 6. How this maps to code

- **Colour / radius / type tokens** → `events-frontend/src/assets/main.css` (`:root` + `.dark`). Re-theming is CSS-variable edits only (ADR-010).
- **Page titles & tagline** → already implemented in `src/composables/usePageTitle.ts`
  (`APP_NAME`, `TAGLINE`, `HOME_TITLE`) and `index.html` (title + OG/Twitter tags).
- **Favicon / logo** → `events-frontend/public/favicon.svg` (pulse badge) and
  `src/components/BrandLogo.vue` (header lockup).
- **Fonts** → self-hosted `@fontsource-variable/geist`, imported in `src/main.ts`; `--font-*` tokens in
  `main.css`.

## 6a. The GitHub repository's own metadata (#477)

Three fields on the repository page are brand surfaces, and they are the ones nobody thinks of as brand surfaces: the description is what appears in search
results, in the organisation's repository list and under the repo name; the topics are the only discovery mechanism GitHub offers; and the social preview is
what every link to the project unfurls as on Slack, X or Discord.

**They are derived from §1 rather than written fresh**, which is the whole reason they are recorded here instead of only in a settings page. Anything set on
GitHub and written down nowhere drifts, and the previous description proves it — it predated the product, never said _Event Junkie_, described the project as
_simple_ and as _checking_ events, and spent a third of its length on a parenthetical about future scope.

| Field              | Value                                                                                                                                           |
| ------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| **Description**    | `Event Junkie — your always-fresh feed of what's on across Berlin's venues. Concerts, club nights, festivals, and the odd quiz night.`          |
| **Homepage**       | `https://event-junkie.de` — **once it resolves.** Setting it before the deploy points people at nothing, which is worse than empty              |
| **Topics**         | `berlin` `events` `concerts` `nightlife` `kotlin` `spring-boot` `webflux` `vue` `typescript` `kubernetes` `web-scraping` `gitops` `helm` `flux` |
| **Social preview** | Blocked on the mark — [#475](https://github.com/enorm-labs/event-junkie/issues/475) produces it                                                 |

**The description is the one-liner from §1 with the product name in front**, and that is deliberate rather than lazy: a second sentence written for GitHub would
be a fourth line of brand copy to keep in step with the tagline, the positioning line and the one-liner — and §1 already warns what happens when those get used
in each other's places. The positioning line (_The event app Berlin deserves_) is **not** used here for the same reason it is kept out of the hero: it reads as
a claim, and a claim in a repository description is a claim about code.

**The topics are half product and half stack**, on purpose. Somebody searching `berlin events` and somebody searching `spring-boot web-scraping` are looking for
different things and both should find this; the stack half is also what makes the repository useful as the template [#396](https://github.com/enorm-labs/event-junkie/issues/396) wants to extract.

## 7. Open questions / next steps

- [x] Applied the **UV violet** accent to the tokens, AA-verified in both modes (§5.1).
- [x] Prototyped the waveform wordmark and shipped it as the favicon + header lockup (§4).
- [x] Dark mode is the default for new visitors (§5.2).
- [x] Self-hosted Geist via `@fontsource-variable/geist`, so it actually renders with no external request (§5.3).
- [ ] Decide on a display/hero type face vs. staying all-Geist (§5.3).
- [x] Add an `apple-touch-icon` PNG (iOS home screen doesn't render SVG favicons).
- [ ] Register `event-junkie.de` (tracked in the roadmap).

### Design refresh — applying the prototype look app-wide

A sequence that also captures the §3–§5 design ideas not tracked in the checklist above.

- [x] Home hero — ambient violet glow, animated pulse mark, wordmark + tagline — and mono eyebrow section labels (`PulseMark`, `SectionLabel`, motion keyframes
      in `main.css`). _(§5.5, §5.6)_
- [x] Refined event cards + a pulsing "live tonight" dot + hover-lift, gated by reduced-motion. _(§5.5)_
- [x] Events & Calendar: eyebrow headers, filter-forward polish, accent-branded day markers. _(§5.6)_
- [x] Detail pages: editorial layout + eyebrow section labels; desaturate-on-rest image treatment. _(§4, §5.4)_
- [x] Empty / 404 / loading microcopy in the brand voice. _(§3)_

## 8. Localisation — the German register

The site publishes English and German ([ADR-013](adr/ADR-013_LOCALISATION.md)). **German is not a translation layer over English** — both are the brand
speaking, and the pieces below are written from the concept rather than rendered word for word.

### Register: `du`, everywhere

Informal throughout, **including the imprint and the privacy notice**. Two pages in `Sie` on a site that says `du` everywhere else read as boilerplate copied
from a generator, which is the impression a legal page can least afford. Nothing requires the formal register — Art. 12 (1) DSGVO asks for _klare und einfache
Sprache_, and `du` is that. If this ever changes it changes on every page at once.

### The tagline — shipping, not signed off

_Can't get enough of Berlin_ is a pun on the brand premise (§2), and a literal German rendering loses it. Three options were considered:

| Option                                | Reading                                                                                                                    |
| ------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| _**Von Berlin kriegst du nie genug**_ | **Currently shipping.** Keeps the "you can't get enough" flattery and the `du` register; idiomatic rather than translated. |
| _Berlin macht süchtig_                | Closer to the junkie metaphor, further from flattering the user — it praises the city, not the reader.                     |
| Keep the English line on `/de` too    | Legitimate, and common for Berlin brands. Costs the German reader the joke.                                                |

**Still the owner's call.** It ships because a German page needs _a_ tagline, not because the question is closed — changing it is one line in
`src/i18n/messages/de/footer.json` plus one e2e assertion.

### What stays in English

The brand name **Event Junkie** (never _Veranstaltungs-Junkie_), the **beta** marker, and everything sourced from third parties: event titles, venue and
promoter names, artist names, line-ups, genre tags, and Berlin district names. _Mitte_ is _Mitte_ in every language — see
[ADR-013 §3](adr/ADR-013_LOCALISATION.md), which flags `src/lib/districts.ts` as the file that looks translatable and is not.

### Microcopy in voice, not in translation

The English examples in §3 have German counterparts written the same way — for the joke, not for the words. Shipping today:

- Disclaimer: _"Die Event-Daten stammen aus öffentlichen Quellen — alle Angaben ohne Gewähr. Frag im Zweifel bei der Location nach, bevor du losziehst."_
- Beta explanation: _"Warum da beta steht"_ — the section heading on the About page, phrased as the reader's question rather than as a status label.

**Note the vocabulary choice:** _Location_, not _Veranstaltungsort_. It is what Berlin actually says, and the nav label uses it too.

## Glossary

Shorthand used across this doc, the code, and PR descriptions. _(planned)_ marks a term whose implementation is still on the backlog above.

- **Accent** — the single brand hue (UV violet) applied to the `--primary` / `--accent` / `--ring`
  tokens; everything else stays neutral so it reads like a spotlight. See §5.1.
- **Ambient glow** — the soft radial violet light in the home hero, centered on the pulse mark so the mark reads as its source. Fades to transparent on its own
  (no `overflow` clip), never a hard shape.
- **Eyebrow label** — a small, mono, uppercase, letter-spaced heading in the accent, used where a section title goes (e.g. "TONIGHT"). The editorial "listings"
  look. Component: `SectionLabel.vue`.
- **Favicon badge** — the app icon: a rounded, violet-gradient square holding the white pulse. File: `events-frontend/public/favicon.svg`.
- **Home hero** — the top block of the home page: the animated pulse mark, the wordmark, the tagline, and the primary call-to-action, over the ambient glow.
- **Image treatment** — event card thumbnails are desaturated at rest and reveal full colour on hover, so mismatched, scraped photos feel cohesive. Detail-page
  hero images stay full colour. See §5.4; implemented in `EventCard.vue`.
- **Live dot** — a small pulsing accent dot on cards for events happening today, reinforcing liveness. See §5.5; implemented in `EventCard.vue`.
- **Lockup** — the mark and wordmark used together as one unit (e.g. in the header nav). Component: `BrandLogo.vue`.
- **Monogram** — an "EJ" ligature; a parked logo alternative for icon-only use. See §4.
- **oklch** — the perceptual colour space the theme tokens are written in:
  `oklch(lightness chroma hue)`. Neutral tokens have chroma `0`.
- **Pulse mark** — the logomark itself: a single-stroke line that is at once a soundwave, a heartbeat (ECG), and a "hit". Component: `PulseMark.vue`; also the
  favicon glyph.
- **Reversed / single-ink** — the mark or lockup drawn in one flat colour (e.g. white on the accent), for contexts where the gradient can't render.
- **Token** — a CSS-variable design value (colour, radius, font) in `main.css` (`:root` + `.dark`); re-theming means editing tokens, not components. See §6.
- **Wordmark** — "Event Junkie" set as type (accent on "Junkie"), as distinct from the pulse mark. Part of the lockup and the hero.
