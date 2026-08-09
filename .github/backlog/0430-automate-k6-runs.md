---
slug: automate-k6-runs
title: Automate the k6 runs, once there is somewhere worth pointing them
type: Task
milestone: v1.0 — Go-live
labels: ["area:ci", "size:M", "needs-deployment"]
priority: P2
status: Blocked
blocked-by: [staging-stage]
---

Deliberately deferred, and worth restating why: a GitHub runner is too noisy to baseline against,
and a CI run against an empty database would only re-assert what the Testcontainers tests already
cover with real data.

Two follow-ups, in order:

1. Point `perf/smoke.js` and `perf/load.js` at **staging** from a scheduled workflow
2. **Store the results over time rather than gating on a threshold.** A p95 that has drifted 40%
   over two months is the signal; a single red build is not. Prometheus remote-write into the
   monitoring stack is the natural home.

Step 2 is the one that matters and the one most likely to be skipped in favour of a threshold,
which would produce a flaky gate and then get disabled.

**References** — [perf/README.md](../../perf/README.md) §Why there is no CI workflow (yet)
