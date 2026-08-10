# Branding — Event Junkie

> Status: **living document** (started 2026-07-02). The brand foundation (name, tagline, voice) is
> decided; the **logo** and **visual-design** sections are *ideas to explore*, not committed decisions.
> Related: [VISION_ROADMAP_IDEAS.md](VISION_ROADMAP_IDEAS.md) · [ADR-010 (styling framework)](adr/ADR-010_FRONTEND_STYLING_FRAMEWORK.md).

## 1. Brand foundation

|                          |                                                                                                                                                                           |
|--------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Public name**          | **Event Junkie** (domain: `event-junkie.de`)                                                                                                                              |
| **Internal / repo name** | **Event Checker** (repo, modules, READMEs, ADRs — see the naming rule below)                                                                                              |
| **Tagline**              | *Can't get enough of Berlin* · DE: *Von Berlin kriegst du nie genug* — the German line is **shipping but not signed off**; see [§8](#8-localisation--the-german-register) |
| **Positioning line**     | *The event app Berlin deserves* — **not the tagline**; see below                                                                                                     |
| **One-liner**            | Your always-fresh feed of what's on across Berlin's venues — concerts, club nights, festivals, and the odd quiz night.                                                    |
| **Scope**                | All music, every genre and room size — not just techno. Berlin-only for now. Which *kinds* of event: [EVENT_SCOPE.md](EVENT_SCOPE.md)                                     |

### Tagline vs. positioning line

Two lines exist and they do different jobs. Keeping them apart is deliberate — using either in the other's place is how the voice goes muddy.

|                   | *Can't get enough of Berlin*          | *The event app Berlin deserves*           |
|-------------------|---------------------------------------|-------------------------------------------|
| **Role**          | Tagline                               | Positioning line                          |
| **Speaks about**  | the **user** — it flatters them (§3)  | the **product** — what it is trying to be |
| **Where it goes** | hero, page titles, OG tags, marketing | About page, README, a pitch               |
| **Register**      | playful, in-character                 | plain, sincere                            |

The positioning line is **not a second tagline**, and should not appear in the hero, the page title or the OG tags. It reads as a claim rather than a wink, and
the brand's whole premise (§2) is that the app flatters the user rather than itself — a claim in the hero would undercut the tagline sitting next to it.

**No "that" (decided 2026-08-08).** The line is *"The event app Berlin deserves"*, not *"…the event app **that** Berlin deserves"*. The relative pronoun is
optional in English when the relative clause relativises the object, and dropping it is what makes the line scan as a claim rather than as a sentence someone
started. It also lets the Batman cadence it borrows land unaltered — *"the hero Gotham deserves"* has no *that* either, and the half-echo is doing work the
extra syllable would blunt. **Do not add it back.** (It was written with *that* until this date; if you find that spelling anywhere, it is a leftover.)

**German:** the About page already ships it inside a sentence — *"Weil ich die Event-App bauen wollte, die Berlin verdient."* That works because the line sits
in prose there. A standalone German form (*"Die Event-App, die Berlin verdient"*) is **not signed off**, and would need the same written-not-translated
treatment as everything else in [§8](#8-localisation--the-german-register) before it goes anywhere on its own. Note that the omission above does not transfer:
German has no zero relative pronoun, so *"die"* stays in either form. The two languages simply differ here — that is not a drift between them to be "fixed".

### Naming rule

- **Public / user-facing surfaces** (page titles, home hero, About copy, OG tags, the eventual domain and marketing) use **Event Junkie**.
- **Internal / technical surfaces** (repository name `event-checker`, Gradle modules, package identifiers, DB schema, ADRs, developer docs) stay **Event
  Checker**. Keeping an internal codename distinct from the public brand is deliberate — don't "fix" these to Event Junkie.
- **Infrastructure and hosting are the one exception, decided 2026-08-10**, and they use **`event-junkie`**: the Hetzner Cloud project, resource labels, server
  and node paths (`/opt/event-junkie/`), config filenames, and the object-storage buckets.

**Why infrastructure breaks the rule.** Every other internal surface is read next to the source — a package name sits beside a module, a schema beside a
migration, and the repository's own name is right there. Infrastructure is read next to a *domain*: you reach it because `event-junkie.de` is misbehaving, and
the console, the labels and the paths you land in should say the same word the browser does. A codename there adds a translation step at the one moment nobody
has spare attention for it.

The boundary is the repository edge. Anything checked into source keeps **Event Checker**; anything that exists in Hetzner, on a node's filesystem, or in a
bucket takes **`event-junkie`**. The OpenTofu in `infra/` describes the second, so it uses the second throughout — see `infra/AGENTS.md`.

## 2. The concept — why "Junkie"

The name works because a junkie's traits map cleanly onto what the product does:

| Junkie trait                             | Product truth                                                               |
|------------------------------------------|-----------------------------------------------------------------------------|
| Always chasing the next **hit**          | A "hit" is both a drug hit *and* a music hit — every event is the next one. |
| Always knows where to **score**          | The app *is* the source: the one place that always knows what's on.         |
| Wired into the scene, ahead of the crowd | An always-fresh feed so you know before it sells out.                       |
| Feeds a **habit**, comes back nightly    | Discovery you return to; you never come up dry.                             |

**Metaphor to lean on:** the user is the *junkie*; the app is quietly the *dealer/source*. Name the audience (Junkie); let "source / score / hit / fix / feed
the habit" show up in the *copy*. Words that carry the double meaning — **hit**, **score** — are the strongest.

## 3. Voice & tone

Playful, self-aware, a little nocturnal — never actually about drugs. It flatters the user ("you can't get enough") rather than the app. Confident and
in-the-know, but warm, not edgy-for-its-own-sake.

**Do:** short, punchy, wink-y; nightlife/music vocabulary; treat FOMO as the enemy. **Don't:** glorify substance abuse, be crude, or over-explain the joke. Keep
it PG-13 and inclusive.

Great places to let the voice show — **microcopy**:

- Empty state: *"Nothing on tonight? In Berlin? Unlikely — try a wider date range."*
- End of list: *"That's the lot. Go touch some grass (or don't)."*
- 404 / not found: *"This one's gone. Like last call — you snooze, you lose."*
- Loading: *"Scoring the latest…"*

Tagline alternatives explored (kept for reference / A-B testing): *Never miss a hit* · *Highly addictive* · *Feed the habit* · *Your dealer for Berlin
nightlife* · *Know before the crowd*.

## 4. Logo — directions to explore

**Done:** direction #1 below (pulse / waveform wordmark) was prototyped and shipped — the pulse mark is the favicon (`events-frontend/public/favicon.svg`) and,
paired with the wordmark, the header lockup (`src/components/BrandLogo.vue`, collapsing to just the mark on mobile). The other directions stay parked as
alternatives. The principles that guided it:

- **Monochrome-first.** The UI theme is currently all-grayscale; the mark must read in a single ink and invert cleanly for dark mode. Design in black/white, add
  the accent (§5) as a highlight only.
- **Favicon-legible.** It has to survive at 16–32 px and as an emoji-style tab/app icon. Favour one strong silhouette.
- **Ship as SVG**, inline-able (the artifact/title system and CSP prefer self-contained assets).

Candidate directions (ordered by how well they fuse *music + the "junkie" concept*):

1. **Pulse / waveform wordmark** *(recommended lead).* "Event Junkie" set in the site font, with a small ECG-heartbeat / audio-waveform line replacing the
   crossbar of a letter or underlining the word. Fuses **heartbeat + music waveform + "never miss a beat" + addiction**. The waveform alone becomes the favicon.
2. **"EJ" monogram.** A tight ligature of E + J for the app icon / favicon; pairs with the wordmark for full-lockup use.
3. **Pin + play.** A Berlin map-pin whose "hole" is a play triangle or a music note — literally "events at venues." Very legible small; a touch more literal /
   less witty.
4. **Wristband / ticket stub.** A club wristband or torn ticket — instant "nightlife entry." Characterful, but busier at favicon size.
5. **The live dot.** A single filled circle — a "hit," a record, a dot on a calendar day — that **pulses**
   when something's on tonight. Minimal, animatable, unbeatable as a favicon; leans on motion for meaning.

**Shipped:** #1 (waveform wordmark) + its standalone favicon glyph, in both inks.

## 5. Website / visual design — ideas

Grounded in the real stack: **Tailwind CSS v4 + shadcn-vue**, **Geist** type, **oklch** CSS-variable tokens in `events-frontend/src/assets/main.css`, dark mode
via the `.dark` class (see ADR-010). Re-theming is a token edit, so most of the below is low-cost to try.

### 5.1 Colour — introduce ONE electric accent

**Done:** the **UV violet** row below was applied to `--primary` / `--accent` / `--ring` (and the matching sidebar tokens), keeping everything else neutral so
the accent reads like a spotlight in a dark room — AA-verified in both modes. The other rows are kept as alternatives. Candidate accents (drop-in oklch):

| Direction              | Vibe                         | Light `--primary`      | Dark `--primary`       |
|------------------------|------------------------------|------------------------|------------------------|
| **UV violet** *(rec.)* | Club blacklight, after-hours | `oklch(0.55 0.24 295)` | `oklch(0.72 0.20 295)` |
| Electric magenta       | Neon, flyer-pink             | `oklch(0.60 0.25 350)` | `oklch(0.72 0.21 350)` |
| Acid green             | Rave, high-energy            | `oklch(0.72 0.22 150)` | `oklch(0.80 0.20 150)` |
| Berlin red             | Bold, editorial              | `oklch(0.58 0.22 25)`  | `oklch(0.70 0.19 25)`  |

Notes: keep `--background`, `--card`, `--muted`, borders neutral. Verify **WCAG AA** contrast for text on accent and accent on background in *both* modes
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
- **Display / hero:** *(open)* consider a characterful face for big headings (a tight grotesque, or a mono for a "listings/terminal" edge) to add nightlife
  personality; keep Geist for everything functional.

### 5.4 Imagery

Event/venue photos are the hero content but come from many scraped sources, so they clash. Apply a **consistent treatment** — grayscale or a duotone tinted with
the brand accent on cards, revealing full colour on hover / detail pages. Cohesive look, and it makes the accent do double duty.

### 5.5 Motion (subtle)

`tw-animate-css` is available. Ideas: a gently **pulsing "live tonight" dot**, soft card hover-lift, a waveform that animates on the logo. Always gate behind
`prefers-reduced-motion: reduce`.

### 5.6 Page-level notes

- **Home:** lead with *tonight / this week* — the "next fix." Hero = wordmark + tagline (already the title).
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
  in `main.css`). *(§5.5, §5.6)*
- [x] Refined event cards + a pulsing "live tonight" dot + hover-lift, gated by reduced-motion. *(§5.5)*
- [x] Events & Calendar: eyebrow headers, filter-forward polish, accent-branded day markers. *(§5.6)*
- [x] Detail pages: editorial layout + eyebrow section labels; desaturate-on-rest image treatment. *(§4, §5.4)*
- [x] Empty / 404 / loading microcopy in the brand voice. *(§3)*

## 8. Localisation — the German register

The site publishes English and German ([ADR-013](adr/ADR-013_LOCALISATION.md)). **German is not a translation layer over English** — both are the brand
speaking, and the pieces below are written from the concept rather than rendered word for word.

### Register: `du`, everywhere

Informal throughout, **including the imprint and the privacy notice**. Two pages in `Sie` on a site that says `du` everywhere else read as boilerplate copied
from a generator, which is the impression a legal page can least afford. Nothing requires the formal register — Art. 12 (1) DSGVO asks for *klare und einfache
Sprache*, and `du` is that. If this ever changes it changes on every page at once.

### The tagline — shipping, not signed off

*Can't get enough of Berlin* is a pun on the brand premise (§2), and a literal German rendering loses it. Three options were considered:

| Option                                | Reading                                                                                                                    |
|---------------------------------------|----------------------------------------------------------------------------------------------------------------------------|
| ***Von Berlin kriegst du nie genug*** | **Currently shipping.** Keeps the "you can't get enough" flattery and the `du` register; idiomatic rather than translated. |
| *Berlin macht süchtig*                | Closer to the junkie metaphor, further from flattering the user — it praises the city, not the reader.                     |
| Keep the English line on `/de` too    | Legitimate, and common for Berlin brands. Costs the German reader the joke.                                                |

**Still the owner's call.** It ships because a German page needs *a* tagline, not because the question is closed — changing it is one line in
`src/i18n/messages/de/footer.json` plus one e2e assertion.

### What stays in English

The brand name **Event Junkie** (never *Veranstaltungs-Junkie*), the **beta** marker, and everything sourced from third parties: event titles, venue and
promoter names, artist names, line-ups, genre tags, and Berlin district names. *Mitte* is *Mitte* in every language — see
[ADR-013 §3](adr/ADR-013_LOCALISATION.md), which flags `src/lib/districts.ts` as the file that looks translatable and is not.

### Microcopy in voice, not in translation

The English examples in §3 have German counterparts written the same way — for the joke, not for the words. Shipping today:

- Disclaimer: *"Die Event-Daten stammen aus öffentlichen Quellen — alle Angaben ohne Gewähr. Frag im Zweifel bei der Location nach, bevor du losziehst."*
- Beta explanation: *"Warum da beta steht"* — the section heading on the About page, phrased as the reader's question rather than as a status label.

**Note the vocabulary choice:** *Location*, not *Veranstaltungsort*. It is what Berlin actually says, and the nav label uses it too.

## Glossary

Shorthand used across this doc, the code, and PR descriptions. *(planned)* marks a term whose implementation is still on the backlog above.

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
