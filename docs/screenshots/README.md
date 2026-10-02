# Screenshots

Pictures of the running product, for the README and anywhere else a reader needs to see it rather than read about it.

| File                                                 | What it shows                                                               | Taken      |
| ---------------------------------------------------- | --------------------------------------------------------------------------- | ---------- |
| [`events-dark.png`](events-dark.png)                 | The events list — filter bar over the poster grid, dark theme, 1400×900 @2× | 2026-10-02 |
| [`events-light.png`](events-light.png)               | The same list in the light theme, which the toggle is the only way into     | 2026-10-02 |
| [`events-mobile-dark.png`](events-mobile-dark.png)   | The same list on a phone, dark theme, 390×844 @2×                           | 2026-10-02 |
| [`events-mobile-light.png`](events-mobile-light.png) | The phone list in the light theme                                           | 2026-10-02 |

**The date is the point of the table.** Nothing here can go stale loudly. A screenshot of last year's UI renders
exactly as well as one of today's. The date next to it is the only signal a reader gets. Update the date when you
retake, and leave the old one visible in the history rather than pretending it was always current.

## When these go stale

**Not on a schedule, and not when the data changes.** The events are scraped and turn over daily, and the calendar's
emptiness depends on which day of the month you look. A screenshot that chased the content would churn constantly, for
reasons that have nothing to do with the product.

They go stale when the **design** changes. Retake them after a release that changes:

- `src/App.vue` — the header and footer are in every shot
- `src/components/EventCard.vue`, `EventPoster.vue`, `EventFilterBar.vue` — the things the events shot is actually of
- `src/assets/main.css` — the theme tokens, which move everything at once
- `docs/branding/` — a new mark changes the header

## How to retake

Run the script after the release that carries the design change:

```sh
scripts/readme-screenshots.sh                        # production into docs/screenshots, and sets the dates
scripts/readme-screenshots.sh <origin> <output-dir>  # another origin or directory, as a dry run
```

**Take them from production**, not from a local dev server. Production has real data and no development overlay. A
design change reaches production only with a release. Install the frontend first: `npm ci` and
`npx playwright install chromium` in `events-frontend/`.

The script sets the theme before the first paint, loads the posters at the bottom edge and captures at 2×. Its header
gives the reasons. Look at every file before you commit it. A card without an event image shows the placeholder
on purpose.

**Captions carry no counts.** `PRODUCT_OVERVIEW.md` already warns against restating the source count because it drifts, and the event total drifts faster. The
screenshot shows its own numbers, and asserting them in prose beside it only creates something else to keep in
step.
