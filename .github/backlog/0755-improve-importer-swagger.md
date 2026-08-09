---
slug: improve-importer-swagger
title: Improve the importer's Swagger UI to match the BFF's
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["importer", "documentation", "size:S"]
priority: P2
status: Ready
---

The BFF's OpenAPI surface is documented properly — summaries, descriptions, examples, response
codes. The importer's is not, and it is the API an operator actually has to drive by hand today.

Good first issue: the pattern to copy already exists in the same repository, so it is annotation
work rather than design work.
