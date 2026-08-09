---
slug: protect-public-api
title: Protect the public BFF API — rate limiting and abuse control
type: Task
milestone: v0.3 — Launch-ready
labels: ["area:security", "area:bff", "size:M"]
priority: P0
status: Backlog
---

The BFF will be publicly reachable and unauthenticated by design. It needs limits before it is
exposed, not after the first scraper finds it.

ADR-012 gets part of the way there: Cloudflare's free plan in front gives proxied DNS, edge caching
and rate limiting. What is left is deciding what belongs at the **application** level — per-IP
limits, expensive-query guards, and what a limited response looks like to a legitimate client.

**Residency nuance.** Cloudflare terminates TLS at its edge. Strictly German-only processing means
dropping proxy mode or buying the EU data-localisation add-on — which also removes the edge rate
limiting, and therefore changes how much of this has to be done in the application.

**Done when**

- [ ] A decided split between edge and application limits, written down
- [ ] Application-level limits implemented for whatever the edge does not cover
- [ ] A load test confirms the limits engage without breaking normal browsing
