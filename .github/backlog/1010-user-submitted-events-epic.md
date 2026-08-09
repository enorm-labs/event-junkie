---
slug: user-submitted-events-epic
title: User and venue submitted events, with review before publish
type: Feature
milestone: Phase 3 — Accounts & personalization
labels: ["size:XL", "area:frontend", "area:data-quality"]
priority: P2
status: Backlog
related: [admin-submission-review-queue, cover-venues-without-importers]
---

**The outcome.** A venue or a visitor can submit an event, and it appears only after a human has
approved it.

**Why it matters.** It is the only route to coverage for venues that will never have an importer —
no website, or a programme published only on Instagram — and those are disproportionately the small
and underground events the product exists to surface.

**Definition of done.** A submission from outside becomes a published event without anyone touching
a database, and a bad submission is declined without anyone touching a database either.

**What it needs**

- RBAC, so approval is a role rather than a person (Keycloak)
- **automatic plausibility checks ahead of the human decision** — near-duplicate search, source-URL
  validation. The queue that does this is tracked separately, in Phase 2, deliberately: building it
  first means this feature launches when it is ready instead of waiting on moderation tooling
  afterwards

**The risk to design against.** Every open submission form is eventually found by spam. The
near-duplicate and URL checks are the first filter; deciding what the second one is — rate limits,
an account requirement, moderation SLAs — belongs in this epic rather than in a panic later.
