---
slug: responsive-mobile-check
title: Verify responsive design and the look on real mobile devices
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:frontend", "size:S"]
priority: P1
status: Ready
---

*Fast, clean, mobile-first discovery* is a stated guiding principle, and mobile is obviously the
primary context — people decide where to go while out.

**On real devices, not only in a resized browser.** Viewport emulation misses touch target sizes,
scroll behaviour with a soft keyboard open, the address-bar height dance on iOS Safari, and how the
calendar behaves under a thumb.

**Done when**

- [ ] Every primary view checked on a real phone, both platforms if possible
- [ ] The calendar specifically — it is the densest view and the most likely to break
