---
slug: anonymous-first-decision
title: Decision — does personalization need an account at all?
type: Task
milestone: Phase 3 — Accounts & personalization
labels: ["needs-decision", "area:frontend", "area:legal", "size:M"]
priority: P1
status: Backlog
---

**The question.** Can follows, favourites and saved searches live on the device
(localStorage/IndexedDB), with a login only for cross-device sync and for notifications that must be
delivered server-side?

**Why this is the first thing to settle in Phase 3, before any of it is built:**

> **Anonymous-first can grow an account later. Account-first cannot be made anonymous without a
> rebuild.**

**It is also the cheaper answer under GDPR** — no account is no personal data, no processor
agreement for identity, no deletion workflow, no password reset flow to secure.

**What it blocks.** Everything in the accounts epic, and it changes the shape of the notification
work rather than merely delaying it: device-local follows cannot drive a server-sent notification,
so the decision determines whether notifications need identity or merely a push subscription.

**Options**

1. **Device-local only** — no accounts at all until something genuinely requires one
2. **Device-local, with optional login for sync** — the migration path has to be designed up front
3. **Account-first** — simplest to reason about, hardest to walk back
