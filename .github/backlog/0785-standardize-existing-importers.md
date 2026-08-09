---
slug: standardize-existing-importers
title: Standardize and simplify the existing importers where it helps
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["importer", "refactor", "size:L"]
priority: P2
status: Backlog
---

There are enough importers now for the patterns to be visible — and for the accidental divergences
between them to be visible too. Several bugs on this list are the same bug in several scrapers,
which is the clearest possible signal that shared logic is sitting in copies.

**Concrete instances already identified elsewhere:**

- `stripArtistSuffix` applied to titles but not lineups, in five scrapers
- `arkaoda`'s local country-code strip, which belongs in the shared helper
- the `"<performer> – <show>"` split that Cosmic Comedy has and Admiralspalast needs

**The caution.** Each scraper's oddities are usually load-bearing — they encode something about that
venue's markup, and the KDoc records why. Standardising should mean lifting genuinely shared logic
up, not flattening deliberate differences. `/codebase-audit` says the same thing: do not
re-litigate selector choices.
