# Screenshots

Pictures of the running product, for the README and anywhere else a reader needs to see it rather than read about it.

| File                                 | What it shows                                                             | Taken      |
| ------------------------------------ | ------------------------------------------------------------------------- | ---------- |
| [`events-dark.png`](events-dark.png) | The events list — filter bar over the card grid, dark theme, 1400×900 @2× | 2026-08-23 |

**The date is the point of the table.** Nothing here can go stale loudly: a screenshot of last year's UI renders exactly as well as one of today's, and the only
signal a reader gets is the date next to it. Update the date when you retake, and leave the old one visible in the history rather than pretending it was always
current.

## When these go stale

**Not on a schedule, and not when the data changes.** The events are scraped and turn over daily; the calendar's emptiness depends on which day of the month you
look. A screenshot that chased the content would churn constantly for reasons that have nothing to do with the product.

They go stale when the **design** changes. Concretely, retake `events-dark.png` after any change to:

- `src/App.vue` — the header and footer are in every shot
- `src/components/EventCard.vue`, `EventFilterBar.vue` — the two things the events shot is actually of
- `src/assets/main.css` — the theme tokens, which move everything at once
- `docs/branding/` — a new mark changes the header

## How to retake

Three things are easy to get wrong, and all three shipped a worse picture the first time they were tried:

1. **Clear the stored theme, do not toggle it.** `localStorage.removeItem('theme')` and reload. Toggling gives you whatever this machine was last set to; clearing
   gives you what a first-time visitor gets, which is dark by default (BRANDING §5.2).
2. **Hide the Vue devtools overlay.** The dev server injects `#__vue-devtools-container__` and `#vue-inspector-container`; both render a floating button into the
   middle of the frame. Set `display: none` on them before capturing.
3. **Capture at 2×** — `deviceScaleFactor: 2`, so 1400×900 becomes 2800×1800. The 1× version saves about 38 kB and looks soft on every retina display, which is a
   poor trade for a README's one image.

The BFF has to be running, or the list renders its error state. See the frontend README.

**Captions carry no counts.** `PRODUCT_OVERVIEW.md` already warns against restating the source count because it drifts, and the event total drifts faster. The
screenshot shows its own numbers; asserting them in prose beside it only creates something else to keep in step.
