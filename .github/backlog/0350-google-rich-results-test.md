---
slug: google-rich-results-test
title: Run the Google Rich Results Test on a real event page
type: Task
milestone: v1.0 — Go-live
labels: ["area:seo", "size:S", "needs-deployment"]
priority: P1
status: Blocked
blocked-by: [deploy-to-cloud]
---

The `schema.org` output is verified against Google's documented requirements and covered by unit
and e2e tests — but **never against Google itself**.

This is the one place the structured data could still be wrong in a way nothing in CI catches: the
tests assert what the documentation says, and the documentation and the validator do not always
agree.

**Done when**

- [ ] A real event page passes the Rich Results Test
- [ ] Any warning it raises is either fixed or recorded as accepted with a reason
