---
slug: housekeeping-delete-policy
title: Housekeeping — a deletion policy for old events and orphaned artists
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:data-quality", "size:M", "needs-decision"]
priority: P1
status: Backlog
related: [browse-past-events-archive]
---

Two halves, and the second is the one causing damage now.

**Old events.** No policy for when they leave the database. Ties into the archive view — deciding to
keep them is also a product decision, not only a storage one.

**Artists that lose their last event.** Nothing garbage-collects them, so a name the importer stops
deriving simply stays. **12 artist rows currently have zero event links**, including
`Corrupted Blood Club Show` — which a since-fixed parsing rule invented and no longer mints.

They are invisible in the UI, because every list joins through `event_artist`. **But they hold
slugs.** So a real act arriving later under the same name collides with a ghost, and the collision
surfaces as a mysterious slug suffix on a legitimate artist.

**Done when**

- [ ] A decided retention policy for events, consistent with the archive decision
- [ ] Orphaned artists cleaned up, and something that keeps them from accumulating
