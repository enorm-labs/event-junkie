---
slug: logging-context
title: Always attach context to log lines — event id, artist id, source
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:infra", "importer", "size:M"]
priority: P1
status: Ready
---

A log line that says a parse failed, without saying which event, source or artist it failed on, only
tells you that something is wrong — and the importer processes thousands of records per run.

**Worth doing before monitoring lands, not after.** Structured, contextual logs are what make alerts
actionable; an alert pointing at an unstructured log is an alert that starts a search rather than
ending one.

**Done when**

- [ ] A convention for what context every log line carries — at minimum the source slug, and the
      entity id where there is one
- [ ] Applied across the importer's scraping and upsert paths
- [ ] Structured output, so the monitoring stack can filter on the fields rather than grep the text
