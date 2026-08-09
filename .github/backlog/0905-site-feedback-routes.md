---
slug: site-feedback-routes
title: Route "missing event or venue" and general feedback from the site to the tracker
type: Feature
milestone: Phase 2 — Coverage & polish
labels: ["area:frontend", "size:S"]
priority: P1
status: Ready
---

**As** a visitor who spots a missing venue or wrong data
**I want** an obvious way to tell someone
**so that** the thing I noticed actually gets fixed.

**The receiving end already exists** — `.github/ISSUE_TEMPLATE/` has forms for a missing or wrong
event, for suggesting a venue, and contact links for questions and ideas. Nothing on the site points
at any of them.

So this is deep-linking, not building: prefilled links to the right form, from the right places — a
"something wrong with this event?" link on event pages, a "venue missing?" link on the venues list,
and a general feedback route in the footer.

**One decision:** whether requiring a GitHub account is acceptable. It is the cheapest possible
moderation filter and also a real barrier for a non-technical visitor, and this site's audience is
not developers.

*(Merged from two backlog items — the missing-venue form and the feedback form are the same
mechanism pointed at different templates.)*
