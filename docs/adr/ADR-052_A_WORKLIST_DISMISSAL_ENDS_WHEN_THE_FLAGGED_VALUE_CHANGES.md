# ADR-052: A worklist dismissal ends when the flagged value changes

## Status

**Accepted (2026-10-22) — the operator can mark a data-quality worklist row correct as it is. The dismissal is keyed to the issue, the event and an md5
fingerprint of the values the check flagged. The row stays off the worklist only while that fingerprint matches.**

**Implemented with the decision.** [#3711](https://github.com/enorm-labs/event-junkie/issues/3711) is the change.

Does not supersede anything. [ADR-042](ADR-042_CURATED_VOCABULARIES_STAY_IN_CODE.md) pins a hand-edited field. A dismissal writes no event field and pins
nothing.

## Context

The production worklist of 2026-10-21 held 830 rows. 348 of them were correct as they were. Examples are a title-derived singleton that is a real act, a party
that is a party, and a free event with no price. Nothing recorded that a person judged them. So each `/worklist` run read and judged them again, and a new row was
harder to see among them.

### The constraints a candidate had to satisfy

**A dismissal must never hide a later regression.** A parser change or a new import can change the value on a dismissed event. The worklist must then show
the row again, with no action from the operator.

**A dismissal must judge what the operator saw.** A row can change between the read and the click. The stored judgment must be of the values on the page.

**The report must stay comparable.** The daily snapshot and the regression alert judge shares against a 7-day median. A count that falls because rows were
dismissed is not a better corpus.

## Candidate options

**A dismissal with no fingerprint.** The table stores the issue and the event. It is simple. It hides the next regression on the same event, so it fails the
first constraint.

**A fingerprint of the whole event.** It ends the dismissal on any change, a new description or ticket URL included. Those rows come back for no reason, and
the operator judges them again.

**A fingerprint of the values the check flagged.** Each `QualityIssue` names a SQL expression next to its predicate. It ends the dismissal only when the
judged values change. Costs one expression per issue, kept beside the predicate.

## Decision

**A fingerprint of the values the check flagged.** The database computes it as `md5` of the issue's `fingerprint` expression. `GET /worklist` returns it per
entry. `POST /worklist/dismissals` takes it back and compares it with the value now. A mismatch is a 409, so the stored judgment is of the values on the page.

What settled it: it is the only option that meets the first constraint and does not bring back unchanged rows.

**Most fingerprints include the title.** A new title is a new show, and the judgment of the old show does not hold for it.

**`unreviewedLicence` has no fingerprint.** It counts a licence review that is still to do. Only the review clears the row.

**The report, the snapshot and the regression alert count dismissed rows.** A dismissal is a reading aid for the worklist, not a fix.

## Consequences

- One table, `worklist_dismissal` (V188), one row per issue and event. A second dismissal replaces the fingerprint. `ON DELETE CASCADE` removes it with
  the event. The 03:00 data-quality job deletes the dismissals of past events.
- A new `QualityIssue` must name a fingerprint, or `null` with a reason.
- A fingerprint that is too wide brings a row back early. That costs one more judgment. A fingerprint that is too narrow hides a change. Review a new one for
  the second failure.
- A dismissal that no longer matches stays in the table. It hides nothing. The admin worklist lists it as changed, with an Undo.
- `dismissed_by` is always `operator`. The admin API knows no user.
- A hand-fix proposal gets no dismissal. Its fix pins the field (ADR-042), or a parser fix clears the row.

## References

- [#3711](https://github.com/enorm-labs/event-junkie/issues/3711) — this change
- [ADR-042](ADR-042_CURATED_VOCABULARIES_STAY_IN_CODE.md) — a hand edit pins its field
- `QualityIssue.kt` — the predicate and the fingerprint of each issue
- `.github/prompts/worklist.prompt.md` — how `/worklist` counts and proposes dismissals
