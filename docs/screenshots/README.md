# Screenshots

Pictures of the running product, for the README and anywhere else a reader needs to see it rather than read about it.

| File                                   | What it shows                                                               | Taken      |
| -------------------------------------- | --------------------------------------------------------------------------- | ---------- |
| [`events-dark.png`](events-dark.png)   | The events list — filter bar over the poster grid, dark theme, 1400×900 @2× | 2026-09-24 |
| [`events-light.png`](events-light.png) | The same list in the light theme, which the toggle is the only way into     | 2026-09-24 |

**The date is the point of the table.** Nothing here can go stale loudly. A screenshot of last year's UI renders
exactly as well as one of today's. The date next to it is the only signal a reader gets. Update the date when you
retake, and leave the old one visible in the history rather than pretending it was always current.

## When these go stale

**Not on a schedule, and not when the data changes.** The events are scraped and turn over daily, and the calendar's
emptiness depends on which day of the month you look. A screenshot that chased the content would churn constantly, for
reasons that have nothing to do with the product.

They go stale when the **design** changes. Concretely, retake `events-dark.png` after any change to:

- `src/App.vue` — the header and footer are in every shot
- `src/components/EventCard.vue`, `EventPoster.vue`, `EventFilterBar.vue` — the things the events shot is actually of
- `src/assets/main.css` — the theme tokens, which move everything at once
- `docs/branding/` — a new mark changes the header

## How to retake

**Take them from the live site**, `https://event-junkie.de/en/events`. It has real data and no development overlay,
and it needs nothing running locally.

Four things are easy to get wrong, and each one shipped a worse picture the first time:

1. **Clear the stored theme, do not toggle it.** `localStorage.removeItem('theme')` and reload. Toggling gives you
   whatever this machine was last set to. Clearing gives you what a first-time visitor gets, which is dark by default
   (BRANDING §5.2). The light shot is the exception and needs `localStorage.setItem('theme', 'light')` before the
   first paint, because nothing else reaches that palette.
2. **Load the posters at the bottom edge.** The posters below the fold load lazily. The strip of the third row in
   the frame stays empty until the page scrolls. Scroll down and back, then wait until every image in and just
   below the frame is complete. A card whose event has no image shows the placeholder on purpose.
3. **Capture at 2×** — `deviceScaleFactor: 2`, so 1400×900 becomes 2800×1800. The 1× version saves about 38 kB and
   looks soft on every retina display. That is a poor trade for a README's one image.
4. **From a local dev server, hide the Vue devtools overlay.** It injects `#__vue-devtools-container__` and
   `#vue-inspector-container`, and both render a floating button into the middle of the frame. Set `display: none` on
   them before capturing. The BFF also has to run, or the list renders its error state.

**Captions carry no counts.** `PRODUCT_OVERVIEW.md` already warns against restating the source count because it drifts, and the event total drifts faster. The
screenshot shows its own numbers, and asserting them in prose beside it only creates something else to keep in
step.
