---
slug: create-more-agent-skills
title: Create the remaining prompts, skills and agents
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:agents", "size:L"]
priority: P2
status: Ready
---

The existing skills cover the repetitive mechanical work well. What is missing is the judgement-heavy
work — the places where an agent currently starts from nothing every time.

**Checklist rather than eight issues**, because each is an hour or two and they share a house style:

- [ ] **Feature planning + spec creation** — interview → spec → plan; see
      [spec-kit](https://github.github.com/spec-kit/)
- [ ] **Code review agent**
- [ ] **Documentation-update agent**
- [ ] **Security agent**
- [ ] **UI/UX agent**
- [ ] **Refactoring / code-quality agent** (behaviour-preserving)
- [ ] **Architecture-review agent**
- [ ] **ADR-authoring prompt** — there are at least four ADRs waiting to be written, and the format
      is consistent enough to be worth a prompt

Note that the three issue-workflow skills (`/new-issue`, `/next-issue`, `/start-issue`) are tracked
by the tracker migration, not here.
