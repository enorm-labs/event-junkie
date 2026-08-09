---
slug: sitemap-hreflang-accepted
title: Confirm the sitemap and hreflang pairs are accepted
type: Task
milestone: v1.0 — Go-live
labels: ["area:seo", "size:S", "needs-deployment"]
priority: P1
status: Blocked
blocked-by: [google-search-console]
---

Search Console reports sitemap parse errors and unreciprocated `hreflang` pairs explicitly, which
is the only place either failure becomes visible — both are silent in the browser and pass every
test in the repo.

Also confirm `robots.txt` and `sitemap.xml` are genuinely **served from the origin**, not merely
present in `dist/`. Those are different facts, and the deployment is where they diverge.

**Done when**

- [ ] Sitemap reads without errors in Search Console
- [ ] No unreciprocated `hreflang` pairs reported
- [ ] `curl` against the live origin returns both files
