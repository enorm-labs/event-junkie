---
slug: enforce-schema-d-ts-current
title: Enforce that events-frontend/src/api/schema.d.ts is current
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:ci", "area:frontend", "size:L"]
priority: P2
status: Backlog
---

`schema.d.ts` is generated from the BFF's `/v3/api-docs` and committed, and **nothing checks it**.

**The failure mode.** Change a BFF response DTO without regenerating, and the frontend keeps
type-checking cleanly against an API that no longer exists — failing only at runtime, in the
browser, for a visitor.

**What a check would cost.** A CI job that boots Postgres, runs the importer for the Flyway
migrations, starts the BFF, regenerates and fails on a non-empty diff. That is a JVM in a frontend
workflow that currently has none, plus a coupling between the two pipelines.

**Deliberately deferred until the BFF's public API stops changing daily** — a gate that fails every
other day gets disabled, and then the check is worse than nothing because it also looks like it
exists. The failure mode and the manual step are documented in `events-frontend/AGENTS.md`
§API Communication until then.
