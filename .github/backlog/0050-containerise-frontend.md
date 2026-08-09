---
slug: containerise-frontend
title: Containerise events-frontend (Node build → nginx)
type: Task
milestone: v0.2 — Deployable
labels: ["area:infra", "area:frontend", "size:M"]
priority: P0
status: Ready
---

Multi-stage Node build serving `dist/` from nginx, with:

- history-mode `try_files` fallback, so a deep link does not 404
- immutable caching for `/assets/*`, `no-cache` for `index.html`
- a **relative `/api` base URL**, so one image serves every stage

Same-origin is what keeps CORS out of the picture entirely and makes session cookies first-party
for the planned authentication. That is the real reason for the nginx container, not packaging
taste.

**This inverts if a PaaS fallback is taken.** A per-GB-RAM platform bills €25–30/month to run an
nginx container that serves static files. There, the SPA goes to a static host or CDN and the BFF
gets an explicit CORS allowlist instead — a different design, not a cheaper version of this one.

**References** — `docs/adr/ADR-012_CLOUD_PLATFORM.md`
