---
slug: proofread-all-pages
title: Read every page in both languages, as a reader rather than as its author
type: Task
milestone: v0.3 — Launch-ready
labels: ["area:frontend", "area:legal", "size:M"]
priority: P1
status: Backlog
---

One deliberate reading pass over the site's prose. The legal pages and About especially: they are
the longest prose on the site, they were written fastest, and each exists as **two independent
documents that no test can compare for *meaning***.

The key-parity test proves every German key exists and is not a copy of the English one. It cannot
tell you a translation is good.

**What to look for**

- German that reads as translated English
- claims that are no longer true
- the `du` register slipping into `Sie`
- anything a reader would have to be the author to understand

**Do the German pass reading only the German**, not comparing it to the English — comparing finds
mistranslations but misses German that is merely bad.

*(Merged from two backlog items — the both-language read-through and the German translation review.
They are one pass, and splitting them meant reading the same pages twice.)*

**References** — `events-frontend/src/i18n/messages/de/`
