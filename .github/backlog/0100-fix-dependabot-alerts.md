---
slug: fix-dependabot-alerts
title: Clear the open Dependabot security alerts
type: Task
milestone: v0.3 — Launch-ready
labels: ["area:security", "size:M"]
priority: P0
status: Ready
---

Work through the open advisories at
[Security → Dependabot](https://github.com/enorm-labs/event-checker/security/dependabot).

The `/security-report` skill already reconciles Dependabot against the OWASP Dependency-Check
findings and triages them, so the first step is running it rather than reading the list cold — a
fair number of raw alerts turn out to be transitive, unreachable, or already suppressed with a
recorded reason.

**Done when**

- [ ] Every open alert is either fixed, or dismissed with a reason that would still convince
      someone reading it in six months
- [ ] Anything deferred has an issue, not just a dismissal
