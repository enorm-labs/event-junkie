---
applyTo: "events-frontend/src/**/*.vue,events-frontend/src/**/*.ts,events-frontend/src/assets/main.css"
paths:
    - "events-frontend/src/**/*.vue"
    - "events-frontend/src/**/*.ts"
    - "events-frontend/src/assets/main.css"
---

# Design Constraints

What the result may look like. [vue](vue.instructions.md) covers how to write the component; this file covers what it is allowed to be.

[docs/BRANDING.md](../../docs/BRANDING.md) argues the decisions in prose; prose is a vibe, and where a vibe and a rule conflict a model sides with the vibe.
BRANDING.md keeps the argument; this file keeps the answer.

## 1. The forbidden list

Each line names the issue that decided it.

| Forbidden                                                       | Instead                                                                                 | Decided by                                                      |
| --------------------------------------------------------------- | --------------------------------------------------------------------------------------- | --------------------------------------------------------------- |
| A card surface on anything that is not an event or a venue tile | Space, or one hairline rule                                                             | [#1240](https://github.com/enorm-labs/event-junkie/issues/1240) |
| Any `shadow-*`                                                  | Nothing. There is no shadow anywhere in `src/` and no reason to be the first            | [#1241](https://github.com/enorm-labs/event-junkie/issues/1241) |
| A lift, scale or translate on hover                             | A colour change the content already justifies, like the poster's grayscale reveal       | [#1241](https://github.com/enorm-labs/event-junkie/issues/1241) |
| An eyebrow label above a page title                             | The `h1` alone. `SectionLabel` is for a section that has no other heading               | [#1239](https://github.com/enorm-labs/event-junkie/issues/1239) |
| A row of pills carrying facts                                   | A meta line, `·` separated, with one coloured word for state                            | [#1248](https://github.com/enorm-labs/event-junkie/issues/1248) |
| Gradients                                                       | A flat token                                                                            | [#1250](https://github.com/enorm-labs/event-junkie/issues/1250) |
| Emoji as an icon                                                | `@lucide/vue`, or an inline SVG with `aria-hidden`                                      | [#1250](https://github.com/enorm-labs/event-junkie/issues/1250) |
| A centred column of text as a page's opening                    | Left-aligned, unless the thing centred is symmetrical artwork                           | [#1243](https://github.com/enorm-labs/event-junkie/issues/1243) |
| An arbitrary Tailwind value outside `components/ui/**`          | A built-in utility, then a `@theme` token. See the ladder in [vue](vue.instructions.md) | —                                                               |
| A dependency whose classes nothing in `src/` uses               | Delete it                                                                               | [#1238](https://github.com/enorm-labs/event-junkie/issues/1238) |

**Two rules survive as deliberate exceptions**, and adding to that list needs the same argument they carry: the `beta` pill in the header, which is a link to
its own explanation, and the non-scheduled status pill on an event, which must not read as another word in a grey row.

### How a rule leaves this list

Build the thing the rule forbids on a branch and look at it against real data — every row above was decided that way, and two candidates were rejected on a
screenshot ([#1242](https://github.com/enorm-labs/event-junkie/issues/1242), [#1247](https://github.com/enorm-labs/event-junkie/issues/1247)). If it is
better, delete the row and say in the pull request what changed. A row removed with a picture is a decision; one removed because it was inconvenient is drift.

## 2. Colour

The tokens are in `src/assets/main.css`, `:root` for light and `.dark` for dark. **Never a hex value, never a raw palette utility** (`bg-emerald-500`), because
neither flips with the theme.

| Token                | Light                        | Dark                         | For                            |
| -------------------- | ---------------------------- | ---------------------------- | ------------------------------ |
| `--background`       | `oklch(1 0 0)`               | `oklch(0.145 0 0)`           | The page                       |
| `--foreground`       | `oklch(0.145 0 0)`           | `oklch(0.985 0 0)`           | Body text                      |
| `--card`             | `oklch(1 0 0)`               | `oklch(0.205 0 0)`           | The one raised surface         |
| `--muted-foreground` | `oklch(0.556 0 0)`           | `oklch(0.708 0 0)`           | Secondary text                 |
| `--primary`          | `oklch(0.55 0.24 295)`       | `oklch(0.72 0.2 295)`        | The accent, and the focus ring |
| `--destructive`      | `oklch(0.577 0.245 27.325)`  | `oklch(0.704 0.191 22.216)`  | Sold out, cancelled            |
| `--success`          | `oklch(0.508 0.118 165.612)` | `oklch(0.765 0.177 163.223)` | Free                           |
| `--border`           | `oklch(0.922 0 0)`           | `oklch(1 0 0 / 10%)`         | Every rule and edge            |

**One accent, and it is violet.** Chroma lives on `primary`, `accent` and `ring`; everything else is neutral, so the accent reads as a spotlight
(BRANDING §5.1).

**Colour is never the only carrier of meaning.** `Sold out` is the word `Sold out`; the colour is emphasis on top of it. New pairs clear 4.5:1 for text and
3:1 for a boundary, measured against the ground they sit on rather than against the value they were picked for.

**The `--chart-*` and `--sidebar-*` sets are unused on purpose** (shadcn registry defaults); a token we added ourselves and stopped using gets deleted.

## 3. Type

Six steps, named for the role rather than the size, declared as `@theme` tokens so Tailwind generates the utility (BRANDING §5.8).

| Utility           | Size / leading | For                                                |
| ----------------- | -------------- | -------------------------------------------------- |
| `text-meta`       | 12 / 16 px     | The smallest live text                             |
| `text-body`       | 14 / 20 px     | Secondary copy, and a card's lines below its title |
| `text-card-title` | 17 / 22 px     | A card title where the card is small               |
| `text-lede`       | 20 / 28 px     | A card title, and a detail page's subtitle         |
| `text-section`    | 24 / 30 px     | `h2`                                               |
| `text-page`       | 30 / 36 px     | `h1`                                               |

**A seventh step needs an argument, not a `text-4xl`.** The faces are Geist and Geist Mono, both self-hosted; the mono is the eyebrow device and the footer's
version string, not decoration (BRANDING §5.3).

## 4. Space

| Value              | Where                                    |
| ------------------ | ---------------------------------------- |
| `p-4 sm:p-8`       | Every page shell                         |
| `space-y-6`        | Listings and prose                       |
| `space-y-8`        | Detail pages                             |
| `space-y-12`       | Home, the only page with a hero          |
| `gap-x-8 gap-y-12` | The card grid: 32 px across, 48 px down  |
| `max-w-3xl`        | Reading measure — detail pages and prose |
| `max-w-5xl`        | Listings                                 |

A fourth section rhythm needs a stated reason before it is added (BRANDING §5.7). The grid's two gaps differ on purpose: across parts two posters that each
have their own edge, down parts one card's last line of text from the next card's poster.

## 5. Components

- **A card is an event tile or a venue tile, and nothing else.** It has no border, fill, shadow or radius: the poster is the card
  ([#1246](https://github.com/enorm-labs/event-junkie/issues/1246)). Its poster box is 3:2, filled with `object-cover`, chosen by measuring crop loss across
  the real corpus (BRANDING §5.4).
- **Chrome is a hairline rule, not a box.** The filter bar is the only chrome on a list page.
- **An empty state offers a control**, not only a sentence ([#1266](https://github.com/enorm-labs/event-junkie/issues/1266)).
- **A missing image is a designed poster**, never a grey rectangle. One upcoming event in nine has no flyer.
- **Below `sm` the poster bleeds to both viewport edges** through the shell's `p-4`, with `CARD_POSTER_CLASS` — a phone shows one card per row, and ground
  either side of a picture that _is_ the card buys nothing. A title poster bleeds with it (a card with no flyer is a normal card, not a fallback); the card's
  text keeps the inset. From `sm` up the margins stand.
- **A hover effect needs a second trigger for a touch device.** Tailwind 4 compiles every `hover:` inside `@media (hover: hover)`, so a `group-hover:` reveal
  is dead code on a phone. `useViewportFocus` marks the card crossing the middle tenth of the viewport, and the same utility is written again as
  `group-data-focus/poster:`. A pointer device gets `:hover` alone.
- **A heading level belongs to the page**, not to the component — see [vue](vue.instructions.md), which owns the `as` prop rule and the `heading-order` gate.
- **The compact view is the one place a list has no posters** ([#1371](https://github.com/enorm-labs/event-junkie/issues/1371)): `EventRow` / `VenueRow`,
  one text row each in a single column, `CARD_LIST_CLASS` drawing a hairline between rows and nothing around them, carrying what the card carries under its
  poster and no more. The poster view stays the default. **The rows render no image element at all** — a hidden `<img>` is still downloaded.
