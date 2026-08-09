---
slug: bug-promoter-acronyms
title: Promoter display names lose genuine acronyms
type: Bug
milestone: Phase 2 — Coverage & polish
labels: ["importer", "area:data-quality", "size:S"]
priority: P1
status: Ready
related: [bug-huxleys-deslugified]
---

**What we store.** `TV Noir` as `Tv Noir`; `Bossa FM` as `Bossa Fm`.

**Where.** `PromoterNormalizer.deshout` is a bare title-caser, without the `ACRONYMS` and
short-initialism guards that `ArtistNormalizer` already has.

**The fix.** Share one de-shout between the two normalizers. The logic exists; it is simply not
reachable from the promoter side.

**Fold in at the same time.** Zitadelle's `tip Berlin` / `Tip` should collapse onto one spelling via
`NAME_CORRECTIONS`.

**The compounding case worth testing against.** Where the descriptor strip runs first, Gärten der
Welt's `HB Music` loses `Music` and is then de-shouted to `Hb` — a display name that no longer
names anything. Fixing de-shout alone does not fix that one; the order of the two steps is the
second half of the bug.

**Needs a `--full` re-seed?** Not strictly — display-only, and slugs are case-insensitive and
unaffected. But existing rows keep their casing until re-created, so a re-seed is what makes the
fix visible.
