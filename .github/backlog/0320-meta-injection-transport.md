---
slug: meta-injection-transport
title: Build the meta-injection transport
type: Feature
milestone: v1.0 — Go-live
labels: ["area:seo", "area:infra", "size:L", "needs-deployment"]
priority: P0
status: Blocked
blocked-by: [deploy-to-cloud]
---

**As** anyone sharing an event link in Slack, WhatsApp or iMessage
**I want** the preview to show that event's title and description
**so that** the link says what it is instead of showing the generic site title.

The component rewrites `<title>`, `og:*`, `twitter:*` and `canonical` per route before the response
leaves our infrastructure. Leading candidate is a **Cloudflare Worker using `HTMLRewriter`**; the
alternative is a small k3s sidecar, which costs more operationally but keeps all processing in
Germany.

**It must fail open.** A slow or failing BFF has to yield the unmodified shell, never an error
page. A broken preview is a cosmetic problem; a broken page is not.

If the Worker is chosen, that is a **[LEGAL.md §7.7](../../docs/LEGAL.md) change to raise, not to
assume** — it moves page processing to a third country.

*(The other half — computing the tags — needs no deployment and is tracked separately under
Phase 2.)*

**References** — [ADR-014 §Decision 3](../../docs/adr/ADR-014_RENDERING_STRATEGY.md)
