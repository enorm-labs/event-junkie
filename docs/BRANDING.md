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

## 4. Logo — the principles, and the directions that lost

**The logo is decided and shipped: see [§4b](#4b-the-direction--decided-2026-08-23-475).** This section is kept for the two things that outlived the decision —
the principles below, which are not the reason anything was replaced and therefore still bind, and the candidate directions, which are the record of what was
considered. Every idea, and the case against each, is in [LOGO_IDEAS.md](LOGO_IDEAS.md).

The principles:

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

**None of these was adopted.** #1 shipped and was then replaced — it is the pulse mark, and §4a is why. #2 survives in an unexpected form: the EJ monogram is
the mark, though drawn as separate letters rather than the ligature described above. #5 survives as a UI motif rather than a logo (§5.5). The rest stay parked.

**The first principle above is the one that decided it**, repeatedly and against every expectation: _favicon-legible_ is not a constraint you satisfy at the end,
it is the one that eliminates candidates. §4b's own recommendation died on it.

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

**The figurative check is a different search from the word check, and a better one than reverse image search.** #475 asks for the mark to be reverse image
searched, and that instinct is right but the tool is wrong: TinEye and Google Images are built to find copies of a photograph, and on a flat monochrome logo
they return stock-icon noise. **eSearch plus and TMview both do AI image search** — upload one PNG and they rank visually similar marks by colour, shape and
texture across roughly 57 million figurative applications, free and without an account. That searches _registered trade marks_, which is the actual risk, rather
than the whole web, which is not.

Do both, on both adopted assets. Reverse image search still has a job — it is what would surface an unregistered mark in active use, which is exactly what
SprintPulse was.

### 4a bis. What the checks have returned so far — 2026-08-23

**Text prior-art, run and recorded. It rules out the obvious and is not a substitute for either search above.**

- **The wordmark: reconfirmed clear.** No product, app or company trading as _Event Junkie_ or _Eventjunkie_ in the German or EU events market. Nearest
  neighbour remains _Startup Junkie_ (US, unrelated class).
- **"EJ" is not a clear field in events, though nothing found is a design collision.** Several small entities trade under it — _EJ Entertainment Ltd_ (CA), _EJB
  Entertainment_, _EJ Events_ (US), and a recording artist billed as _EJ_. None is a software product and none was found using a badge mark, but the two letters
  are in use in class 41 adjacent territory and the register check should be run on `EJ` as a figurative mark, not only on the wordmark.
- **EJ monogram templates are abundant on the stock marketplaces.** That is a distinctiveness signal rather than a collision: a two-letter monogram is weak to
  protect precisely because everybody has one. It does not stop us using it — the badge's job is to be an app icon, not to be defended — but it should not be
  mistaken for an ownable asset.
- **The stamp device is common and unremarkable.** Nothing specific found in Berlin nightlife. Its distinctiveness comes from the wordmark inside it, which is
  the thing the register check covers, so its figurative risk is materially lower than the badge's.

**Run 2026-08-23 — no hits, on any search.** All four, and naming them is the point of recording it:

| Search                                                                   | Against                                  | Result |
| ------------------------------------------------------------------------ | ---------------------------------------- | ------ |
| [TMview](https://www.tmdn.org/tmview/) image search                      | EJ badge, club stamp — classes 9, 41, 42 | none   |
| [EUIPO eSearch plus](https://www.euipo.europa.eu/en/search) image search | EJ badge, club stamp                     | none   |
| [DPMAregister](https://register.dpma.de/DPMAregister/marke/einsteiger)   | `Event Junkie`, `Eventjunkie`, `EJ`      | none   |
| Google Lens / TinEye                                                     | EJ badge, club stamp                     | none   |

**What that establishes, stated at its actual weight.** The wordmark and both adopted assets are clear of registered figurative marks in the classes that
matter, and clear of anything a web-wide image search surfaces in active use. That is the check the pulse mark never got, and it is the difference between
_"we noticed and changed it before launch"_ and a recollection.

**What it does not establish.** It is not a legal clearance opinion, no search is exhaustive, and none of it speaks to the **distinctiveness** point above —
`EJ` remains a two-letter monogram that everybody has, which affects what could be protected rather than what may be used. If the badge ever needs defending
rather than merely using, that is the conversation, and it is a lawyer's rather than this document's.

**Re-run the image searches if the artwork changes materially** — in particular when the stamp's text is converted to outlines and its distress redrawn, since
that changes the shape a similarity search matches on.

### 4b. The direction — **decided 2026-08-23 (#475)**

**There is no single logo. There are three surfaces, and each gets the thing that works on it.** That is the decision, and it is a different shape from the
question this section originally asked — which was "which mark wins", and had the overflowing calendar cell as its recommendation.

| Surface                                                           | What goes there                                                                                                                                                       |
| ----------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`favicon.svg`, `favicon.ico`** — 16 px                          | **Small badge** — [`…-ej-badge-small.svg`](branding/mark-ej-badge-small.svg)                                                                                          |
| **Header, below `sm`** — 24 px                                    | **Small badge** — the same file                                                                                                                                       |
| **Header, `sm` and up**, and the **footer**                       | The **wordmark** alone                                                                                                                                                |
| **`apple-touch-icon`** (180 px), merch — 96 px and up             | **Stamp badge** — [`…-ej-badge-stamp.svg`](branding/mark-ej-badge-stamp.svg)                                                                                          |
| **Home hero**, **About page**                                     | The **tagline stamp**, per locale — [`…-tagline-en.svg`](branding/lockup-club-stamp-tagline-en.svg) / [`…-tagline-de.svg`](branding/lockup-club-stamp-tagline-de.svg) |
| **README, GitHub social preview, site `og:image`** — English-only | The **tagline stamp**, English — [`…-tagline-en.svg`](branding/lockup-club-stamp-tagline-en.svg)                                                                      |

**There is no pictorial mark in the UI chrome at all.** This is §4b's own runner-up — type-only — with a badge doing the one job type cannot do, which is being
an app icon. The parked directions (pin+play, ticket stub, live dot, monogram-as-ligature) stay parked, and the two marks drawn during this decision — the
overflowing calendar cell and the beanie — were **dropped on 2026-08-23** once the stamp direction was chosen. Their drawings are recoverable from git history
with their geometry notes intact, and what was learned from rendering them is kept in [LOGO_IDEAS.md](LOGO_IDEAS.md) rather than deleted with them.

**The typeface is Rubik Distressed, and the licence is why.** It is **SIL Open Font License 1.1**, so commercial and logo use are permitted outright, with
nothing to buy and no record to keep. It was chosen over Dharma Punk and RUBBER STAMP, both of which are **free for personal use only** — and outlining their
glyphs would not have fixed that, because outlining is a technical step rather than a grant of rights. A mark cleared against every trademark register (§4a bis)
and then set in an unlicensed font would have been cleared of the wrong risk.

**There are three size rungs, and the boundaries are measured rather than assumed.** Rubik Distressed is a display face and its floor is high: rendered as a
true-size ladder, the split badge is an illegible blob at 32 px, guessable at 48, resolves roughly at 64, and is comfortable only from **96 px**, where the
distress starts reading as distress rather than as noise.

| Rung        | Sizes        | Letters                                      |
| ----------- | ------------ | -------------------------------------------- |
| **Icon**    | 16 px        | Drawn — geometric, frameless, no font at all |
| **Chrome**  | 24 px        | Drawn — the same file                        |
| **Display** | 96 px and up | Set — Rubik Distressed, split and framed     |

**Small-size variants with simplified letterforms are normal practice for a distressed identity**, not a compromise: the drawn letters are what the distressed
ones look like once the distress stops being resolvable. The icon rung also keeps a property the display rung cannot have — it depends on no font, which
`favicon.svg` needs because browsers are served the SVG itself.

**The display badge's text is already outlined**, extracted from the font with `fontTools` and embedded as `<path>`. That removes the _"outline before it
ships"_ blocker this file used to carry and makes it self-contained. It also removes a trap: **a renderer silently substitutes a default face when a font is
missing**, so a `<text>` version of this rendered as clean Helvetica and looked perfectly fine — a logo that is wrong in a way nothing complains about.

**The display badge is the lockup with the words taken out.** Same −2.5° tilt, same face, same square corners, same frame-only wear — one frame rather than the
lockup's two, because a double rule closes into a thick band at 32 units where it reads cleanly at 320. Its sizing was matched to the lockup **by density rather
than by arithmetic**: three settings were rendered against it, and the one where _EJ_ fills its frame the way _EVENT JUNKIE_ fills its own is what makes the two
read as one family instead of two stamps.

**The hero stamp carries the tagline, and therefore ships per locale — decided 2026-08-23.** The live tagline that sat beneath the hero is removed, so the
stamp is now the only place it appears. That makes the caption localised artwork rather than language-neutral: _Can't get enough of Berlin_ on `/en` and _Von
Berlin kriegst du nie genug_ on `/de`, which is what [§8](#8-localisation--the-german-register) requires — German is the brand speaking, not a translation layer
over English.

**The two files are not one file with the words swapped.** The German line is 31 characters against the English 26, and at the English caption settings it
overflows the inner frame. Each has its own sizing; neither number transfers. The _BERLIN_ variant stays as the language-neutral option for any surface that
needs one.

**Square surfaces take a badge, wide surfaces take a lockup.** That is what separates the two rows above, and it is worth stating because they read as
overlapping: `apple-touch-icon` is a square, so it gets the stamp badge; the README and the social previews are wide, so they get the stamp with the wordmark
in it. A badge stretched across a 1200×630 card wastes the card, and a lockup squeezed into a square icon is unreadable.

**Badge and wordmark are never shown together.** They are the same two letters, so a badge beside its own wordmark is tautological. Splitting them across the
`sm` breakpoint also solves a constraint that already exists: the header packs a lockup, a beta badge, four nav links and two icon controls, and at ~390 px that
row overflowed and pushed the controls off-screen — which is why the wordmark is hidden below `sm` today and why `e2e/smoke.spec.ts` guards it. The badge is
narrower than the wordmark, so it is what mobile can afford.

**Why the recommendation did not survive.** The overflowing calendar cell was recommended here on reasoning that no rendering supported. Rasterised at 16 px it
is a clipboard: the hanger ticks merge into the top edge and become a clamp, the entry lines close into a block, and the escaping line — the whole idea — is a
fuzzy stub. The concept is still the strongest on paper; the drawing does not deliver it. That is criterion 1 of [LOGO_IDEAS.md](LOGO_IDEAS.md), and it is the
criterion that decides logos.

**The club stamp is a lockup, not a mark, and the distinction is load-bearing.** Distress is fine detail by definition. At 96 px wide the wordmark still reads
and _BERLIN_ is already a smudge; at 16 px it is a grey rectangle. It cannot be asked to fill an icon slot, which is exactly why the badge exists.

**One thing must happen before the stamp ships**, down from two. Its wordmark is Rubik Distressed and **already outlined**; what remains is the secondary line
_BERLIN_, still `<text>`, which should be set in **Geist** — the site's own face, already self-hosted — and then outlined. _BERLIN_ is deliberately **not**
distressed: in the same face it degrades to a row of specks long before the wordmark does, and a clean secondary line is how real stamps are built anyway. The
frame wear still wants a hand redraw, since the current ellipses are algorithmic.

**Still open: whether the stamp lands in the home hero now or after the visual pass.** Black ink, distressed and high-contrast is a different visual language
from the UV-violet, soft-glow, dark-mode look §5.1 records as shipped, and putting it in the hero is a partial answer to [#374](https://github.com/enorm-labs/event-junkie/issues/374)
made inside #475. The README and the social preview sit outside the app and can take it immediately; the hero is the one placement worth holding.

**On Trainspotting**, unchanged: the cultural fit is real, and the execution that survives both cautions is the film's **poster language** (orange-and-black,
mixed-weight condensed type, numbered character strip, stark white ground) rather than its characters — a face breaks §3's _"never actually about drugs"_ rule
outright, and it is also a specific actor's likeness. Note that the club stamp is a quieter member of the same family, and that is not an accident.

**Not adopted: the scan line.** A cropped barcode is a horizontal-stripe silhouette, which is the same visual family as an equaliser — it would be trading one
crowded neighbourhood for a neighbouring one.

**Every candidate, the bar each was held to, and the case against each** is in [LOGO_IDEAS.md](LOGO_IDEAS.md). This section holds the decision; that file holds
the arguments. The execution plan — every file that changes, in order, plus the surfaces a `grep` for `PulseMark` will never reach — is in
[BRAND_REFRESH_PLAN.md](BRAND_REFRESH_PLAN.md).

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

**Re-examined against the stamp identity, 2026-08-23 — and kept.** The question was fair: the identity went monochrome, so the accent is now the only colour
in the system, and violet was chosen when the hero was a violet glow. Three things settled it.

**The palette is more constrained than it looks, because two hues already carry meaning.** This is not a taste argument, it is what `BaseBadge.vue` spends:

| Token                 | Meaning                                                                          | How often                         |
| --------------------- | -------------------------------------------------------------------------------- | --------------------------------- |
| `--destructive` (red) | **Sold out**, cancelled, non-scheduled status, errors, the calendar's now-marker | Frequent — cards and detail pages |
| `--success` (green)   | **Free**                                                                         | Frequent                          |
| `--muted`             | Genre tags                                                                       | Everywhere                        |

So **red is out** — which is a genuine loss, since red is _the_ rubber-stamp ink, but a red accent beside a red _Sold Out_ pill on the same card is exactly the
collision the note above warns about. **Orange goes with it**, and takes the Trainspotting direction along. **Acid green is out** because `--success` owns it.
That leaves violet or magenta; blue reads as a link and yellow cannot hold AA in light mode.

**What clashed with the stamp was the glow, not the hue.** The soft radial blur is what made violet feel like an aesthetic the stamp was replacing, and it is
deleted (§4b). Flat, hard-edged violet on near-black is a different object — and it has a name: **monochrome artwork plus one fluorescent spot ink**, which is
the Berlin flyer convention and precisely what this system now is.

**Violet is a stamp ink.** Alongside black and blue it is one of the three classic stamp-pad inks — bank endorsements, library date stamps, school marks. It is
not in tension with a rubber stamp; it is what rubber stamps have been inked with for a century.

**So the accent stays**, and this is recorded so it is not reopened cold. Magenta is the only realistic alternative and would cost an AA re-verification in both
modes for a hue shift of roughly 55°. **Worth trying rather than deciding** (a #374 question): `ClubStamp` uses `currentColor`, so printing the stamp _in_ the
accent on a secondary surface — the About page, the 404 — is a one-class change, and a violet stamp is authentic in a way a violet glow never was.

### 5.2 Dark-mode-first

Nightlife skews dark. **Done:** dark mode is now the **default for first-time visitors**, set by the pre-paint script in `index.html` so there's no flash. An
explicit light choice is remembered in
`localStorage` and always wins on later visits. The default is unconditional (not gated on
`prefers-color-scheme`) — a deliberate brand call; revisit if it proves user-hostile for light-OS users. The accent is tuned to glow on the dark surface.

### 5.3 Typography

- **Body / UI:** **Geist** — now actually rendering and **self-hosted** via `@fontsource-variable/geist`
  (imported in `main.ts`); the render-blocking Google Fonts request is gone. (A shadcn-scaffold name mismatch had it silently falling back to a system font
  until that was fixed.)
- **Display / hero:** **decided 2026-08-23 — stay with Geist.** [#377](https://github.com/enorm-labs/event-junkie/issues/377) asked this when the hero carried a
  giant live `<h1>`, which was the one place a display face would clearly have paid. That `<h1>` is now `sr-only` and the stamp carries it as artwork, so the
  largest live heading on the site is `text-3xl` — 30 px page and detail titles. The brand's own display face cannot help either: **Rubik Distressed floors at
  96 px** (§4b), so the face that gives the identity its personality is structurally unusable as live type. And most remaining headings are **scraped content** —
  event and venue titles — where a characterful face is a liability against long strings and whatever the venues type. The personality was bought in the stamp,
  at a size where legibility is not at risk.
- **Wordmark:** **Rubik**, self-hosted via `@fontsource-variable/rubik` (OFL-1.1), on the header and footer wordmark and **nowhere else**. Rubik Distressed —
  the stamp's face — is derived from it, so the two share a skeleton and the wordmark rhymes with the artwork. The distressed face itself cannot come down here:
  rendered at 18 px it is legible but its distress reads as **dirt rather than texture**, since the counters fill in and the strokes look eroded rather than
  pressed. That is a smaller size than the 96 px badge floor implies, because a twelve-character word carries its own shape — it is still below where the
  texture survives.
  **The cost, measured:** 34.5 kB for the latin subset, against **2.4 kB** if the eleven glyphs were outlined instead. Outlining is the cheaper lever if the
  weight ever matters; the font is kept because it leaves the wordmark as **live text** — the home link's accessible name, selectable, and scaling with the
  reader's own font settings.
- **Mono:** **Geist Mono** — shipped, self-hosted via `@fontsource-variable/geist-mono` (OFL-1.1, same family as the sans). This was the half of the typography
  question nobody had logged. `--font-mono` was **not defined**, so Tailwind's system stack applied and the **eyebrow label** — a named brand device (§5.6) — rendered as SF Mono on a Mac,
  Consolas on Windows and DejaVu on Linux. Pinning it makes _all-Geist_ true rather than approximate. Only the `latin` subset is ever fetched — `unicode-range`
  leaves the other five unloaded — so the cost is one 23 kB woff2.
- **`--font-heading` is wired**, and was a dead token until 2026-08-23 — defined as `var(--font-sans)` and read by nothing, so it advertised a lever that was
  not connected. A base rule on `h1`–`h6` now consumes it. Identical to `--font-sans` today, so it changes nothing visually; the point is that changing the
  heading face is a one-line token edit rather than a sweep through every view.

### 5.4 Imagery

Event/venue photos are the hero content but come from many scraped sources, so they clash. Apply a **consistent treatment** — grayscale or a duotone tinted with
the brand accent on cards, revealing full colour on hover / detail pages. Cohesive look, and it makes the accent do double duty.

### 5.5 Motion (subtle)

`tw-animate-css` is available. Shipping: a gently **pulsing "live tonight" dot** and a soft card hover-lift, both gated behind
`prefers-reduced-motion: reduce`, which is not optional here.

**The logo does not move, and that is a decision rather than an omission** (§4b, decision C). The old mark's `ej-draw` / `ej-beat` keyframes went with it —
both depended on `pathLength="1"` and a single continuous stroke, which neither the badge nor the stamp has. A mark that does not need to move is not a worse
mark, and a rubber stamp that animates is a contradiction.

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
- **Logo, source of truth** → `docs/branding/*.svg`. Everything below is a **build** of one of those, never a fresh drawing; a drift between the two is how the
  old mark survived in three places at once.
- **Favicon / app icons** → `events-frontend/public/favicon.svg`, `favicon.ico`, `apple-touch-icon.png`, `og-image.png`. The favicon differs from its source in
  one way only: its letters get an opaque ground, because a knockout's letters would take the colour of the browser tab strip.
- **Logo in the app** → `src/components/EjBadge.vue` (icon and chrome), `ClubStamp.ts` + `ClubStamp.{en,de}.vue` (the lockup, lazy-loaded per locale) and
  `src/components/BrandLogo.vue` (the header lockup — badge below `sm`, wordmark from `sm`).
- **Fonts** → self-hosted `@fontsource-variable/geist`, imported in `src/main.ts`; `--font-*` tokens in
  `main.css`.

## 6a. The GitHub repository's own metadata (#477)

Three fields on the repository page are brand surfaces, and they are the ones nobody thinks of as brand surfaces: the description is what appears in search
results, in the organisation's repository list and under the repo name; the topics are the only discovery mechanism GitHub offers; and the social preview is
what every link to the project unfurls as on Slack, X or Discord.

**They are derived from §1 rather than written fresh**, which is the whole reason they are recorded here instead of only in a settings page. Anything set on
GitHub and written down nowhere drifts, and the previous description proves it — it predated the product, never said _Event Junkie_, described the project as
_simple_ and as _checking_ events, and spent a third of its length on a parenthetical about future scope.

| Field              | Value                                                                                                                                                         |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Description**    | `Event Junkie — your always-fresh feed of what's on across Berlin's venues. Concerts, club nights, festivals, and the odd quiz night.`                        |
| **Homepage**       | `https://event-junkie.de` — **once it resolves.** Setting it before the deploy points people at nothing, which is worse than empty                            |
| **Topics**         | `berlin` `events` `concerts` `nightlife` `kotlin` `spring-boot` `webflux` `vue` `typescript` `kubernetes` `web-scraping` `gitops` `helm` `flux`               |
| **Social preview** | `docs/branding/social-preview.png` — 1280×640, the English tagline stamp on ink. **Rendered; still to upload**, which is a settings page rather than a commit |

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
- [x] Decided the display/hero type face question: **stay all-Geist**, and make that true by pinning the mono to Geist Mono (§5.3, #377).
- [x] Add an `apple-touch-icon` PNG (iOS home screen doesn't render SVG favicons).
- [ ] Register `event-junkie.de` (tracked in the roadmap).

### Design refresh — applying the prototype look app-wide

A sequence that also captures the §3–§5 design ideas not tracked in the checklist above.

- [x] Home hero — ambient violet glow, animated pulse mark, wordmark + tagline — and mono eyebrow section labels (`PulseMark`, `SectionLabel`, motion keyframes
      in `main.css`). _(§5.5, §5.6)_
- [x] **Superseded 2026-08-23 (#475).** The glow, the pulse mark and its keyframes are gone; the hero is the club stamp, per locale, over nothing. The line
      above stays because it records what was true when it was ticked — the eyebrow labels it also names are untouched and still shipping.
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

Shorthand used across this doc, the code, and PR descriptions. _(planned)_ marks a term whose implementation is still on the backlog above; _(retired)_ marks one
kept because older sections still use it, not because it still exists.

- **Accent** — the single brand hue (UV violet) applied to the `--primary` / `--accent` / `--ring` tokens; everything else stays neutral so it reads like a
  spotlight. See §5.1. Since 2026-08-23 it appears nowhere in the logo — the identity is monochrome ink and the accent is a UI colour only.
- **Ambient glow** _(retired)_ — the soft radial violet light that sat behind the home hero, centred on the pulse mark so the mark read as its source. Deleted
  with the mark: its whole premise was that the mark is a light source, and a rubber stamp is ink. See §4b.
- **Club stamp** — the wordmark lockup: _EVENT JUNKIE_ in Rubik Distressed inside a worn, square, 2.5°-tilted double frame. Two captions with two jobs —
  _BERLIN_ for the bilingual app, the **tagline** for English-only surfaces (README, social previews, merch), because the tagline is localised and _BERLIN_ is
  not. Files: [`branding/lockup-club-stamp.svg`](branding/lockup-club-stamp.svg) and its `-tagline` sibling. Component: `ClubStamp.vue`.
- **EJ badge** — the logomark: the monogram in a square. Two constructions, split by size rather than taste — the **small badge** (drawn letters, no frame) for
  16–24 px, and the **stamp badge** (Rubik Distressed, framed, tilted) from 96 px. See §4b. Component: `EjBadge.vue`.
- **Eyebrow label** — a small, mono, uppercase, letter-spaced heading in the accent, used where a section title goes (e.g. "TONIGHT"). The editorial "listings"
  look. Component: `SectionLabel.vue`. Unrelated to the pulse mark despite the shared vocabulary, and deliberately untouched by the mark replacement.
- **Favicon badge** — the app icon: the small badge as a **flat ink** square holding the EJ monogram — no gradient and no accent ground (decided 2026-08-23).
  File: `events-frontend/public/favicon.svg`. It needs **explicit fills** rather than the in-app knockout, or its letters take the colour of the tab strip
  behind them.
- **Home hero** — the top block of the home page: the club stamp, the tagline as live text beneath it, and the primary call-to-action. The `<h1>` is `sr-only`,
  because the stamp carries the name as artwork and the live text has to survive for search and screen readers.
- **Image treatment** — event card thumbnails are desaturated at rest and reveal full colour on hover, so mismatched, scraped photos feel cohesive. Detail-page
  hero images stay full colour. See §5.4; implemented in `EventCard.vue`.
- **Ink / wear** — the eroded texture on the frames, drawn as grey ellipses in an SVG `mask`. Grey rather than black on purpose: a mask is luminance-based, so
  grey **thins** the stroke where black would punch a hole, and ink thins. Never applied over letters — distress across a counter closes it.
- **Knockout** — letters cut out of a solid shape as transparent holes rather than drawn in a second colour. It is why the badge survives small sizes (the eye
  reads the surrounding mass) and why the **favicon** needs a separate build with opaque fills.
- **Live dot** — a small pulsing accent dot on cards for events happening today, reinforcing liveness. See §5.5; implemented in `EventCard.vue`.
- **Lockup** — a fixed arrangement of brand elements reproduced as one unit, never rearranged or respaced. From letterpress: type and blocks were assembled in a
  metal frame (a _chase_) and **locked up** with expanding wedges so nothing shifted under the press. Two here — the **header lockup** (`BrandLogo.vue`: badge
  or wordmark, never both) and the **club stamp**, which is the full lockup of wordmark plus caption.
- **Monogram** — the letters `EJ`, drawn as separate forms rather than a ligature. No longer a parked alternative: since 2026-08-23 it is the mark. See §4b.
- **oklch** — the perceptual colour space the theme tokens are written in: `oklch(lightness chroma hue)`. Neutral tokens have chroma `0`.
- **Pulse mark** _(retired)_ — the previous logomark: a single-stroke line that was at once a soundwave, a heartbeat (ECG) and a "hit". Replaced because it
  collided with **sprintpulse.io** — see §4a, which is the reason this entry is kept.
- **Reversed / single-ink** — the mark drawn in one flat colour. Since the gradient was dropped this is no longer a separate variant: the badge is already one
  flat colour.
- **Rubik Distressed** — the display face, under the **SIL Open Font License 1.1**, so commercial and logo use are permitted with nothing to buy. Used only from
  96 px up, where its distress resolves; below that the letters are drawn instead. Always **outlined** in artwork, never `<text>`.
- **Size rung** — which construction a surface gets, decided by measurement rather than taste: **icon** (16 px) and **chrome** (24 px) use drawn letters,
  **display** (96 px and up) uses Rubik Distressed. See §4b.
- **Token** — a CSS-variable design value (colour, radius, font) in `main.css` (`:root` + `.dark`); re-theming means editing tokens, not components. See §6.
- **Wordmark** — "Event Junkie" set as type (accent on "Junkie"), as distinct from the badge. Used alone in the header from `sm` up and in the footer, and set
  in Rubik Distressed inside the club stamp.
