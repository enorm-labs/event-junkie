---
slug: google-search-console
title: Set the site up in Google Search Console
type: Task
milestone: v1.0 — Go-live
labels: ["area:seo", "size:S", "needs-deployment"]
priority: P0
status: Blocked
blocked-by: [deploy-to-cloud]
---

Verify ownership of `event-junkie.de` — a DNS TXT record via Cloudflare is the least fragile
method — add **both** locale trees, and submit `sitemap.xml`.

Nothing else in the SEO verification group can be checked until this exists, and it is the only
free source of truth for how Google actually sees the site. Everything else is inference.

**Done when**

- [ ] Ownership verified by DNS TXT
- [ ] Both locale trees added as properties
- [ ] `sitemap.xml` submitted and showing as read
