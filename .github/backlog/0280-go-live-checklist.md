---
slug: go-live-checklist
title: Assemble and run the go-live checklist
type: Task
milestone: v0.3 — Launch-ready
labels: ["area:infra", "size:M"]
priority: P0
status: Backlog
---

Legal, security, SEO, monitoring, alerting, dashboards, backups and recovery — including the
restore drill, which is the one item on it that is easy to nod through and expensive to have
skipped.

This issue is the **checklist itself**: the single document that says what must be true before the
site is public, with each line pointing at the issue that satisfies it. Its value is that it is
read once, in order, on launch day, by someone who is tired.

Note that the items which **cannot be done before there is a deployment** are tracked separately in
the `v1.0 — Go-live` milestone and carry the `needs-deployment` label. They are not blocked on
effort; they are blocked on a live origin. Keeping them out of this list is what stops the checklist
looking permanently unfinished.

**Done when**

- [ ] The checklist exists as a document, not as this issue's description
- [ ] Every line references the issue or evidence that satisfies it
- [ ] It has been walked end to end
