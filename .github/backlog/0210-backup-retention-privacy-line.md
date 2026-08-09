---
slug: backup-retention-privacy-line
title: Give backup retention its own line in the privacy notice
type: Task
milestone: v0.3 — Launch-ready
labels: ["area:legal", "size:S", "blocked"]
priority: P1
status: Blocked
blocked-by: [postgres-backups-restore-drill, logging-decisions]
---

Backup retention is a separate period from log retention, and the notice currently addresses only
one of them.

The two interact, which is the part worth stating explicitly rather than leaving to a reader:
personal data in a backup persists for the backup window regardless of what the live system does
with it, so a deletion request and a restore have to be reconciled somewhere.

**Done when**

- [ ] The privacy notice states a backup retention period as a number
- [ ] That number matches what the backup tooling is actually configured to keep
- [ ] The interaction with deletion requests is addressed, not left implicit
