---
slug: scrape-multiple-languages
title: Scrape events in both English and German where the source offers it
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["importer", "area:data-quality", "size:L"]
priority: P2
status: Backlog
---

The site is localised; the data is not. Every event title and description is stored in whatever
language the venue published it in, so a German-language visitor and an English-language visitor see
the same strings.

**First step is an audit, not a change:** which event sources are actually multi-language? Berghain
is the known case. The answer determines whether this is worth building at all — if three sources
publish both languages, a per-source language field is a lot of machinery for very little.

**Done when**

- [ ] EVENT_DATA_SOURCES.md records which sources publish in more than one language
- [ ] A decision on whether to store both, based on that count
