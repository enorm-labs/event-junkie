---
slug: path-specific-instructions
title: Path-specific agent instruction files, at least backend and frontend
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:agents", "documentation", "size:M"]
priority: P2
status: Ready
---

`events-frontend/AGENTS.md` already exists and is used. The gap is doing this deliberately and
consistently across the modules, rather than where someone happened to need it.

Also worth adopting [path-specific custom instructions](https://docs.github.com/en/copilot/how-tos/copilot-on-github/customize-copilot/add-custom-instructions/add-repository-instructions#creating-path-specific-custom-instructions)
for Copilot, which reads a different file than Claude Code does.

**The principle to hold to** — recorded already, and worth restating because it is what keeps this
from sprawling: **AGENTS.md stays the source of truth.** Path-specific files carry what is genuinely
local to a subtree, not a copy of the general rules.
