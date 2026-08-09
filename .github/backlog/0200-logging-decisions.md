---
slug: logging-decisions
title: Settle the logging decisions the privacy notice depends on
type: Task
milestone: v0.3 — Launch-ready
labels: ["area:legal", "area:infra", "size:M"]
priority: P0
status: Backlog
---

Four open questions ([LEGAL.md §7.5.1](../../docs/LEGAL.md)):

1. Do Traefik and the nginx container log real client IPs?
2. Are they truncated?
3. What is the retention period?
4. **Where is retention actually enforced?**

The privacy notice currently states an *intended* seven days. It must state the **configured** one.
A notice that describes a period the system does not honour is worse than a vaguer one, because it
is a specific false claim.

**The interaction that is easy to miss:** if logs on disk are captured by `wal-g` snapshots or
server snapshots, the effective log retention is the **backup** window, not the rotation one. Check
this against the final design rather than assuming — assuming it is exactly how a notice ends up
stating a period the system does not honour.
