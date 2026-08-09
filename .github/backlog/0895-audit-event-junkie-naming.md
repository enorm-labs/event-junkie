---
slug: audit-event-junkie-naming
title: Audit that all user-facing surfaces read "Event Junkie"
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:frontend", "documentation", "size:S"]
priority: P1
status: Ready
---

Public name is **Event Junkie**; internal and repo name stays **Event Checker**.

**Do not "fix" internal identifiers** — that split is the BRANDING naming rule, not an inconsistency
to clean up. Module names, packages, the database schema and the repository itself stay
`event-checker` deliberately.

What this audits is only what a visitor sees: page titles, meta tags, the manifest, error pages,
emails, and the OG/Twitter card tags.

Good first issue, and a genuinely useful one before launch — a stray internal name on a public page
is the kind of thing that is invisible to the person who wrote it.

**References** — [BRANDING.md](../../docs/BRANDING.md) §Naming rule
