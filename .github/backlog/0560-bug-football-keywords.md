---
slug: bug-football-keywords
title: The football keywords cannot tell a match screening from a football talk
type: Bug
milestone: Phase 2 — Coverage & polish
labels: ["importer", "area:data-quality", "size:M"]
priority: P2
status: Backlog
related: [bug-screening-german-compounds]
---

**What happens.** `SCREENING_TITLE_KEYWORDS` (`EventTypeMapping.kt`) carries `fussball` and
`11freunde` for Lido's and Astra's public viewings.

**Result.** Colosseum's `Der Fussball mein Leben & Ich` — an on-stage evening with Thomas Schaaf,
ticketed through the 11Freunde shop — is typed `SCREENING` rather than left `OTHER`.

**The signal that actually separates them is the screening verb, not the sport.** A public viewing
says *Public Viewing*, *Live-Screening* or *Übertragung*, or names a fixture
(`EM Italien - Albanien`). A talk names people.

**The fix.** Narrow the sport words to those contexts.

**Needs a `--full` re-seed?** Yes — it touches Lido and Astra as well as Colosseum, so it needs a
re-seed and a diff.
