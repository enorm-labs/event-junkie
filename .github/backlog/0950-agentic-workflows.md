---
slug: agentic-workflows
title: Evaluate agentic workflows for continuous refactoring and docs
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:agents", "area:ci", "size:M"]
priority: P2
status: Backlog
---

[GitHub Agentic Workflows](https://github.github.com/gh-aw/) — scheduled agents that open PRs for
documentation drift, refactoring and similar.

**The relevant risk for this repository:** a large share of its value is in prose that encodes
decisions — AGENTS.md, the ADRs, the KDoc that records *why* a parser makes a trade-off. An agent
that "tidies" that prose can quietly delete the reasoning while leaving the sentence, which is the
single worst failure mode available here.

So the evaluation is less "does it work" and more "what can it be allowed to touch". Somewhere
between test coverage and dependency notes is probably the safe zone.
