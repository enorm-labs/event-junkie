---
slug: admin-ai-assisted-fixing
title: AI-assisted checking and fixing, human-in-the-loop
type: Feature
milestone: Phase 2 — Coverage & polish
labels: ["area:frontend", "area:data-quality", "size:L", "needs-decision"]
priority: P2
status: Backlog
parent: admin-frontend-epic
related: [dq-pillar-4-ai-assisted]
---

Cross-check stored data against the event's source page and **propose** fixes (Spring AI), with a
human making the decision.

**Same capability as Pillar 4.** Decide it once, in the ADR that Pillar 4 calls for — including the
open question of a local LLM versus an API or subscription. Two independent answers to that question
is the failure mode worth avoiding here; it is exactly how a project ends up with two model
integrations.

This issue is the **review surface**: showing a proposal, the evidence behind it, and accept/reject.
The capability itself belongs to Pillar 4.
