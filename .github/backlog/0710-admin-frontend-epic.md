---
slug: admin-frontend-epic
title: Admin frontend — one place to operate the importers and curate the data
type: Feature
milestone: Phase 2 — Coverage & polish
labels: ["size:XL", "area:frontend", "importer"]
priority: P1
status: Backlog
---

**The outcome.** One place where an operator can see what the importers are doing, fix what they got
wrong, and add what they could not reach — instead of `psql`, `curl` and the IntelliJ HTTP Client.

**Why now.** Data quality is the product's stated differentiator (*"better to show fewer, correct
events than a noisy firehose"*), and today there is no way to act on a quality finding except
editing the database by hand. Pillar 1 will produce a worklist; without somewhere to work it, it
produces a report nobody can action.

**Start with the API, not the UI.** Importer API endpoints plus an admin IntelliJ HTTP Client
collection; the interface can follow. `EventSourceController` already exposes per-source status and
retry — build on it rather than beside it.

**Definition of done.** An operator who is not the author can find a failed import, retry it,
find the events it produced with missing fields, and fix them — without a terminal.

**Depends on** the auth work: every one of these surfaces is privileged, and shipping any of it
before there is a login is shipping an open admin panel.
