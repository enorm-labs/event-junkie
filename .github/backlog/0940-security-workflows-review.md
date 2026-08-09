---
slug: security-workflows-review
title: Review the security workflows GitHub offers
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:security", "area:ci", "size:S"]
priority: P2
status: Ready
---

Work through
[Actions → Security](https://github.com/enorm-labs/event-checker/actions/new?category=security) and
decide which are worth adding.

CodeQL, dependency review and OWASP Dependency-Check already run. The gaps worth looking at are
secret scanning coverage, and whether anything useful exists for the container images that `v0.2`
will start producing.

**The bar to apply:** a workflow whose findings nobody acts on is worse than no workflow, because it
trains everyone to ignore a red check. Add only what will be read.
