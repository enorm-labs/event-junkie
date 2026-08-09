---
slug: reduce-group-genres
title: Reduce or group the displayed genres
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:frontend", "area:data-quality", "size:M", "needs-decision"]
priority: P1
status: Backlog
---

There are too many distinct genre tags for the filter to be usable. A filter with dozens of options,
many of them near-synonyms and several of them formats rather than genres, is a list the visitor
scrolls past.

**Needs a grouping or taxonomy decision** — both UX and data. The two halves cannot be separated: a
UI grouping over an unmapped vocabulary is a lie, and a data taxonomy with no UI is invisible.

Related to the Heimathafen taxonomy work, which will *add* 560 terms' worth of vocabulary, and to
the curated-vocabulary decision, which decides where a synonym map would live. Worth sequencing
after both rather than doing this twice.
