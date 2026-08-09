---
slug: register-domain
title: Register event-junkie.de
type: Task
milestone: v0.2 — Deployable
labels: ["area:infra", "size:S", "blocked"]
priority: P0
status: Blocked
blocked-by: [accept-adr-012]
---

The public name is **Event Junkie** and the domain is unregistered. ADR-012 puts Cloudflare in
front for DNS, TLS, CDN and rate limiting on the free plan.

This is a small task holding up a disproportionate amount of work. Until the domain exists,
**every reporting route the project advertises is a dead address** — `hello@event-junkie.de` and
`security@event-junkie.de` are already published in the imprint, the privacy notice, `SECURITY.md`
and `CODE_OF_CONDUCT.md`, including the Code of Conduct's enforcement contact and the
security-disclosure address.

**Done when**

- [ ] `event-junkie.de` is registered
- [ ] DNS is served by Cloudflare, per ADR-012

**Residency nuance worth deciding at the same time.** Cloudflare terminates TLS at its edge, so
strictly German-only processing means either dropping proxy mode or buying the EU data-localisation
add-on. That choice reaches the privacy notice, not just the DNS panel.
