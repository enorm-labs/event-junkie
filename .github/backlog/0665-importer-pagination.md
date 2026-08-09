---
slug: importer-pagination
title: Scrape all available events via pagination, not just the first page
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["importer", "size:M"]
priority: P1
status: Ready
---

Several importers read only the first page of a listing and stop.

**migas is the cheapest concrete case and the one that needs new plumbing.** Its "Load More" button
POSTs `action=load_events&paged=<n>` to `wp-admin/admin-ajax.php` and returns the same markup
fragment — a GET ignores `paged` entirely. The button's `data-pages` attribute states the total page
count up front, **so the loop is bounded and terminating**, which is the part that usually makes
pagination risky.

**What blocks it:** `HtmlFetcher` is GET-only and would need a form-POST fetch first. That is the
reusable piece — several other sources will want it.

**Blast radius at migas:** 10 of 12 upcoming events at capture. The importer currently sees two.

**Done when**

- [ ] `HtmlFetcher` supports a form POST
- [ ] migas walks its pages, bounded by `data-pages`
- [ ] A survey of which other importers are silently truncating
