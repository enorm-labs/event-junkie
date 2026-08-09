---
slug: check-link-previews
title: Check a real link preview in Slack, WhatsApp and iMessage
type: Task
milestone: v1.0 — Go-live
labels: ["area:seo", "size:S", "needs-deployment"]
priority: P1
status: Blocked
blocked-by: [meta-injection-transport]
---

The per-page tags exist in the DOM now, but **these scrapers do not run JavaScript**, so they still
read the site-level tags out of the served HTML. That is the concrete defect ADR-014 exists to fix,
and it only closes when the injector lands.

Checking a real preview is the only way to know it worked. Every intermediate check — the tags
being in the DOM, the injector responding correctly to `curl` — can pass while the preview stays
wrong, because each scraper has its own quirks about which tag it trusts.

**Done when**

- [ ] An event link previews correctly in Slack, WhatsApp and iMessage
- [ ] A venue link and an artist link too — they use different tag sources
