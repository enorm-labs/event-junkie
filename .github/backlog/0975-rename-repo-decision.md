---
slug: rename-repo-decision
title: Decision — rename the repo and internal references to event-junkie
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["needs-decision", "documentation", "size:XL"]
priority: P2
status: Backlog
---

**The question.** Should `event-checker` become `event-junkie` throughout, collapsing the
public/internal name split?

**This reverses the current BRANDING naming rule** (§ "Naming rule"), which says the internal name
stays. If pursued, BRANDING.md changes with it — otherwise the repo contradicts its own documented
convention, which is worse than either name.

**Scope, and it is large:** repo name, Gradle modules, packages, database schema, ADRs, docs.

**What it buys:** one fewer thing to explain, and no more "don't fix the internal identifiers" note
in every onboarding document. **What it costs:** a rewrite touching every file, broken external
links, and a database migration.

Worth deciding rather than drifting, but there is no urgency and a good case for "no".
