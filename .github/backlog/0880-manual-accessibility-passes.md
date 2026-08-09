---
slug: manual-accessibility-passes
title: Manual accessibility passes — keyboard-only and screen reader
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:frontend", "area:legal", "size:M"]
priority: P1
status: Ready
---

A keyboard-only walkthrough and a screen-reader pass.

**Why the automated checks are not enough, stated precisely:** `axe` reliably finds roughly a third
of WCAG issues. The two automated checks in CI **cannot certify WCAG 2.1 AA no matter how thorough
they get** — passing them is evidence of nothing beyond what they test.

Required if a conformance statement is ever wanted for the live site
([LEGAL.md §12](../../docs/LEGAL.md)), which is why the accessibility statement is deferred until
this happens rather than written alongside the other legal pages.

**Done when**

- [ ] Every primary flow completable with a keyboard alone
- [ ] A screen-reader pass over the list, detail and calendar views
- [ ] Findings filed individually
