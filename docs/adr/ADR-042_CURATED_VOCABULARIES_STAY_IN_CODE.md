# ADR-042: Curated vocabularies stay in code, and a hand edit pins its field on that row

## Status

**Accepted (2026-10-04) — the denylists, synonym maps and corrections stay in Kotlin, changed by pull request with tests. A field that an operator edits by
hand on one row is pinned, and the importer does not overwrite a pinned field. A fix that applies to more than one row goes into the vocabulary.**

**Implemented in [#2590](https://github.com/enorm-labs/event-junkie/issues/2590)** for events: `event.pinned_fields`, and
`DELETE /api/admin/events/{id}/pins/{field}` removes a pin. **Implemented in [#2636](https://github.com/enorm-labs/event-junkie/issues/2636)**
for an artist's name: `artist.name_pinned`, and `DELETE /api/admin/artists/{id}/pins/name` removes the pin.

**Does not supersede anything.** [ADR-041](ADR-041_AI_ASSISTED_DATA_QUALITY.md) decides that a model only suggests. This ADR decides where an accepted
suggestion goes.

## Context

[#323](https://github.com/enorm-labs/event-junkie/issues/323) asked whether `NON_ARTIST_NAMES`, `NAME_CORRECTIONS`, the genre synonyms and `ACRONYMS`
should move into tables that a steward edits without a release. `docs/DATA_QUALITY_STRATEGY.md` §6 left it open until Pillar 4 needed live editing.

Three facts decided it:

- **The vocabularies change often, and review catches mistakes.** `ArtistNameMapping.kt` has 1,669 lines and had 53 commits between 2026-09-04 and
  2026-10-04. Each one came with a test.
- **There is no steward without a release.** The operator works through agent sessions and pull requests. The admin UI that a table would need
  ([#341](https://github.com/enorm-labs/event-junkie/issues/341)) does not exist.
- **The real gap is the overwrite.** [#345](https://github.com/enorm-labs/event-junkie/issues/345) and [#474](https://github.com/enorm-labs/event-junkie/issues/474)
  both stop at the same question: a hand-fixed value is gone after the next import. A table of vocabulary does not answer that for a value that is wrong
  on one row only.

## Candidate options

1. **Code, and pin a hand-edited field.** Review and tests stay. A one-row fix survives the import.
2. **Code, a pin, and a table of display-name corrections.** As option 1, plus a slug-keyed table that replaces the data migration that each
   `NAME_CORRECTIONS` change needs today (V059, V080). More schema for one saved migration per rename.
3. **Every vocabulary in tables.** A fix without a release. No review, no test, no diff that says why a name is on a list, and a cache to invalidate.

## Decision

**Option 1.** The vocabularies keep their tests and their review. The pin closes the gap that #345 and #474 actually have.

The rules:

1. **A fix that applies to a pattern goes into the vocabulary**, by pull request with a test. The next import applies it to every row.
2. **A fix that applies to one row is a hand edit.** It pins each field it changes. The importer keeps a pinned field and updates every other field.
3. **A pin is visible and removable.** The admin API returns which fields of a row are pinned, and an operator can remove a pin. A pin that nobody can see
   becomes a stale value that nobody can explain.

**The reason that settled it** is that only one of the two problems needed solving. Live editing of the vocabularies has no user. The overwrite of a
hand edit stops two issues.

## Consequences

- A rename in `NAME_CORRECTIONS` still needs a slug-keyed data migration for the rows that exist. Option 2 would have removed that, and it stays open if
  the renames become frequent.
- The importer has a new rule to respect on every update. A field that the source corrects later stays wrong on a pinned row until someone removes the pin.
- Pillar 4's loop closes through pull requests. A model finding that a person accepts becomes a vocabulary change or a pinned edit, not a live table row.

## When to revisit

- An admin UI exists and someone other than the operator fixes data.
- Renames that need a data migration happen more than about once a week.

## References

- [#323](https://github.com/enorm-labs/event-junkie/issues/323), the decision issue
- [#345](https://github.com/enorm-labs/event-junkie/issues/345) and [#474](https://github.com/enorm-labs/event-junkie/issues/474), which wait on the overwrite answer
- `docs/DATA_QUALITY_STRATEGY.md` §6
- `ArtistNameMapping.kt`, `ArtistNormalizer.kt`, `NameCasing.kt`, `GenreNormalizer.kt`
