---
slug: register-role-mailboxes
title: Register the role mailboxes and confirm mail arrives
type: Task
milestone: v0.3 — Launch-ready
labels: ["area:legal", "area:infra", "size:S", "blocked"]
priority: P0
status: Blocked
blocked-by: [register-domain]
---

`hello@event-junkie.de` and `security@event-junkie.de` are **already published** — in the imprint,
the privacy notice, `SECURITY.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md` and `SUPPORT.md`.

Until the domain exists, every confidential reporting route the project advertises is a dead
address: the Code of Conduct's enforcement contact, the security-disclosure address, and the
name-removal route for artists and organisers. Right now the private GitHub advisory form is
carrying all three purposes at once, which is why `.github/ISSUE_TEMPLATE/config.yml` has a contact
link labelled for security that is really for privacy requests.

**Done when**

- [ ] Both mailboxes exist
- [ ] A test message to each actually arrives somewhere a human reads
- [ ] The advisory-form workaround in `config.yml` is revisited now that a real address exists

This is infrastructure, not proofreading — the read-through of those same files is a separate issue.
