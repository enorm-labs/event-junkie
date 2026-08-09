---
slug: admin-dashboard-template
title: Pick an admin dashboard template rather than building the shell from scratch
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:frontend", "size:M"]
priority: P1
status: Backlog
parent: admin-frontend-epic
---

Navigation, tables, forms, filters, pagination, an empty state and a dark mode — all of it solved
work that is not this project's problem.

The public site earns its custom design because it is the product. The admin panel does not: nobody
chooses this app because its admin tables are beautiful, and every hour spent on the shell is an
hour not spent on the data-quality tooling that is the actual point.

**Done when**

- [ ] A kit chosen that fits Vue 3 and the existing tooling
- [ ] Its licence checked against `notices.json` obligations
- [ ] A decision recorded on whether it lives in `events-frontend` or its own app
