---
slug: template-repository
title: Create a template repository from this project's tooling
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["documentation", "area:ci", "size:L"]
priority: P2
status: Backlog
---

Enterprise and private. The tooling here — workflows, agent instructions, skills, prompts, issue
forms, the Conventional Commits label pipeline — took real effort and is almost entirely
project-agnostic.

**Checklist rather than sub-issues:**

- [ ] `.github/` with workflows, instructions, skills, prompts and agents
- [ ] README, CONTRIBUTING, LICENSE and the rest of the health files
- [ ] **Check for good existing templates first** — including the OTR service template — and add
      scaffolding rather than starting from a blank repository

The first checklist item is the one worth doing first: this is only worth building if nothing
adequate already exists.
