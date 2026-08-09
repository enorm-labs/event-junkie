---
slug: rederive-load-test-weights
title: Re-derive perf/load.js session weights from real traffic
type: Task
milestone: v1.0 — Go-live
labels: ["area:ci", "size:S", "needs-deployment"]
priority: P2
status: Blocked
blocked-by: [deploy-to-cloud]
---

The weights are currently 55% events list, 25% calendar, 20% venues — a considered guess, and
labelled as one in the script.

**A load test's p95 only describes traffic that could actually occur.** A wrong mix produces a
confident number about a session nobody has, which is worse than no number: it gets quoted.

Needs analytics or access logs, so it is blocked on a deployment rather than on effort.

**References** — `perf/load.js`, `perf/README.md`
