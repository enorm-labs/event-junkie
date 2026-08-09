---
slug: enrich-promoters
title: Enrich promoters — description, image, corrected display names
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:data-quality", "importer", "size:M"]
priority: P2
status: Backlog
related: [bug-promoter-acronyms]
---

Promoter rows carry a name and little else, and the name is often wrong — see the de-shout bug,
which turns `TV Noir` into `Tv Noir`.

A promoter detail page exists and has almost nothing to show. Enriching it is what makes "who books
this kind of night" a question the site can answer, which is one of the few things the incumbents do
badly.

**Order matters here:** fix the display names first. Enriching a row whose name is wrong means the
enrichment is attached to a name nobody searches for.
