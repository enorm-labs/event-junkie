---
slug: fix-diff-snapshot-empty-baseline
title: Fix dev-env.sh diff-snapshot when the baseline snapshot is empty
type: Bug
milestone: Phase 2 — Coverage & polish
labels: ["area:agents", "size:S"]
priority: P1
status: Ready
---

**What happens.** With a fresh database, the baseline snapshot file is empty. `diff-snapshot` uses
the `NR == FNR` awk idiom to load the first file — but **awk never reads a record from an empty
file**, so the *second* file is loaded as the baseline instead.

**Result.** Every new source is reported `GONE` / "lost events" instead of `new`. The tool cries wolf
in exactly the situation where its output is most likely to be trusted: a fresh environment, where
the operator has no prior expectation to check it against.

**The fix.** Guard on `FILENAME == ARGV[1]` (or `ARGIND == 1`) instead of `NR == FNR`.

**Where** — `scripts/dev-env.sh`, `diff-snapshot`.

Good first issue: one-line fix, and the failure is easy to reproduce with an empty file.
