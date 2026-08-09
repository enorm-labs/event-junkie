---
slug: admin-submission-review-queue
title: Submission review queue with plausibility checks
type: Feature
milestone: Phase 2 — Coverage & polish
labels: ["area:frontend", "area:data-quality", "size:L"]
priority: P2
status: Backlog
parent: admin-frontend-epic
---

For anything entered by hand or sent in from outside.

**Machine checks first, human decision last:**

1. **Search for near-duplicates before accepting** — the same event usually already exists from the
   venue's own listing, and this is the failure that would otherwise fill the site with doubles
2. **Check the source URL resolves** and belongs to the claimed venue
3. Then approve or decline

The ordering is the design. A human asked to spot duplicates across thousands of events will miss
them; a human asked to confirm a machine's duplicate candidate will not.

**This queue is what makes public submission safe to open.** The public submission UI is Phase 3;
building the queue first means the feature can launch when it is ready rather than waiting on
moderation tooling afterwards.
