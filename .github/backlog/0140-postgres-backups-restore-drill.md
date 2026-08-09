---
slug: postgres-backups-restore-drill
title: PostgreSQL backups plus a rehearsed restore
type: Task
milestone: v0.3 — Launch-ready
labels: ["area:infra", "size:L"]
priority: P0
status: Backlog
---

**The load-bearing mitigation of ADR-012's "we own the database" trade, and the highest-risk item
the ADR creates.** `wal-g` or `pgBackRest` streaming WAL and base backups to a Hetzner Storage Box,
plus server snapshots.

**A restore drill is part of the go-live checklist and repeats on a schedule.** An untested backup
is not a backup — it is a belief about a backup. This issue is not done when backups are running;
it is done when a database has actually been restored from them.

This risk disappears entirely if a fallback platform with managed Postgres is chosen instead, which
is one of the things the ADR-012 decision is choosing between.

**Done when**

- [ ] WAL and base backups stream somewhere off the server
- [ ] Server snapshots are configured
- [ ] **A restore has been performed end to end, and the time it took is recorded**
- [ ] The drill is scheduled to repeat, with a named owner
- [ ] Backup retention is decided as a number, because the privacy notice has to state it
