---
slug: dq-pillar-4-ai-assisted
title: Pillar 4 (Systematize) — AI-assisted data quality in the importer
type: Feature
milestone: Phase 2 — Coverage & polish
labels: ["area:data-quality", "importer", "size:XL", "needs-decision"]
priority: P2
status: Backlog
blocked-by: [curated-vocabulary-storage]
related: [admin-ai-assisted-fixing]
---

One capability, several uses — detect and extract artist names from titles, validate event types,
enrich missing fields (genres, event types), and fix bad values (artist names, promoter names),
cross-checking the event's source page and the wider web where useful.

**Runs *after* the deterministic normalizers**, human-in-the-loop via the admin review UI. That
ordering is the design: the deterministic rules are cheap, testable and auditable, and anything they
already handle should never reach a model.

**Needs an ADR — *AI-Assisted Data Quality*.** New external dependency, cost and latency, and
non-deterministic output in a pipeline whose entire value proposition is correctness.

> Unnumbered on purpose: this ADR has been pre-assigned a number twice and lost it twice, to the
> cloud-platform and localisation decisions. It gets one when it is written.

Open question shared with the admin tooling issue: local LLM versus an API or subscription. Decide
it **once**, in the ADR, not separately in two places.
