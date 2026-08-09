---
slug: bff-sitemap-detail-routes
title: BFF-served sitemap for detail routes
type: Feature
milestone: Phase 2 — Coverage & polish
labels: ["area:bff", "area:seo", "size:M"]
priority: P1
status: Ready
---

Events, venues, artists and promoters.

**It belongs in the BFF** because the BFF holds the data and can leave out events that have already
happened. The frontend build cannot enumerate them without giving up its independence from the
database — which is a property worth keeping, and the reason this is not simply a build step.

**What already exists**, so it is not re-done here: the static-route sitemap, `robots.txt`,
`hreflang`, canonical URLs, per-page head tags and `schema.org` structured data — see
[ADR-014](../../docs/adr/ADR-014_RENDERING_STRATEGY.md) and `events-frontend/AGENTS.md` §SEO
surfaces.

The only other remaining SEO work is the meta injector, which needs a deployment and is tracked in
`v1.0`.
