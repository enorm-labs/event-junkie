---
slug: social-layer-epic
title: Social layer — friends, activity and invitations
type: Feature
milestone: Phase 4 — Social & ecosystem
labels: ["size:XL", "area:frontend", "area:bff", "blocked"]
priority: P2
status: Blocked
blocked-by: [accounts-follows-notifications-epic]
---

**The outcome.** Someone can see where their friends are going and bring them along.

**Why it matters.** It is the answer to the fifth product-test question — *where are my friends
going, and can I bring them?* — and going out together is the actual use case. Seeing where someone
went is the weaker half of it.

**Definition of done.** Two people who both use the site end up at the same event because of it.

**What it absorbs**

- connect with friends; see which events they are interested in or going to
- follow other users; an activity timeline
- **invite friends to a specific event** — the part that is genuinely the point
- **ranking by popularity (RSVPs) and by artist popularity**
- **collaborative recommendations** — "people going to this also go to that". Distinct from the
  content-based related-events list in Phase 2, and it needs RSVP volume before it says anything
  useful, so it follows the social layer rather than leading it
- richer venue and artist profiles, with links; venues browsable by genre and location

**Held back deliberately: ratings and reviews for events and promoters.** That needs a *whether*
before a *when* — it creates permanent moderation duty, and a handful of reviews reads worse than
none at all.
