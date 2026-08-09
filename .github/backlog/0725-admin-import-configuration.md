---
slug: admin-import-configuration
title: Import configuration — manage sources and their schedules
type: Feature
milestone: Phase 2 — Coverage & polish
labels: ["area:frontend", "importer", "size:M"]
priority: P2
status: Backlog
parent: admin-frontend-epic
---

**As** an operator
**I want** to add, edit, disable and reschedule event sources
**so that** onboarding a venue or pausing a broken one does not need a deploy.

Today a source is created by POSTing JSON from `dev-seed.http`, and its schedule is code.

**The constraint that shapes this:** ADR-008 pins the importer to one replica with `Recreate`
precisely so two schedulers never run. Anything here that changes scheduling has to respect that —
a UI that lets an operator create overlapping schedules for one source recreates the problem the
deployment strategy exists to prevent.
