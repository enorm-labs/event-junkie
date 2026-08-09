---
slug: bug-huxleys-deslugified
title: Huxleys' genre and promoter are stored de-slugified
type: Bug
milestone: Phase 2 — Coverage & polish
labels: ["importer", "area:data-quality", "size:S"]
priority: P1
status: Ready
related: [bug-promoter-acronyms]
---

**What the source publishes.** WordPress taxonomy slugs on the `article` element.

**What we store.** The slug, title-cased word by word. So a stylised genre loses its punctuation —
`kpop` becomes `Kpop` rather than `K-Pop` — and a legal form comes back as
`Concert Concept Veranstaltungs Gmbh`.

**The fix.** A corrections map for the known slugs, in the same place as the promoter de-shout fix.
The slug set per venue is small and closed, which is what makes a map the right tool here rather
than a rule.

**Needs a `--full` re-seed?** Display-only, but existing rows keep their spelling until re-created.
