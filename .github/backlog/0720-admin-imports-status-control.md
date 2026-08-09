---
slug: admin-imports-status-control
title: Imports status and control — see failures, trigger an import
type: Feature
milestone: Phase 2 — Coverage & polish
labels: ["area:frontend", "importer", "size:M"]
priority: P1
status: Backlog
parent: admin-frontend-epic
---

**As** an operator
**I want** to see per-source import state — especially **failed** imports — and trigger an import on
demand
**so that** a broken scraper is something I notice rather than something a visitor notices.

This is the highest-value child of the epic and the one closest to already existing:
`EventSourceController` exposes per-source status and retry today.

**Worth building in from the start:** a source stuck in `RUNNING`. A Gradle compile during a running
import reloads DevTools mid-flight and strands the source in `RUNNING` forever, which currently
requires a manual database edit to clear. The UI should be able to see that state and reset it.

**Done when**

- [ ] Per-source last run, duration, event count and outcome
- [ ] Failures surfaced with their error, not just a red dot
- [ ] Trigger and retry from the UI
- [ ] A stuck `RUNNING` source can be cleared
