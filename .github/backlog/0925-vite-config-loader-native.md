---
slug: vite-config-loader-native
title: Opt in to Vite's native config loader
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:frontend", "blocked", "size:S"]
priority: P2
status: Blocked
---

The config chain already carries the explicit `.ts` imports the native loader needs
(`events-frontend/AGENTS.md` §Config-loader imports), and `vite build --configLoader native` was
verified working on Node 24.

**The only blocker is the engine floor:** it fails on Node 22, which has no unflagged type-stripping.
**Unblocked once `engines.node` is `>=24`.**

Not urgent — the current `bundle` loader works. But doing it deliberately beats being moved by a Vite
major, which is the usual way this kind of thing gets done.
