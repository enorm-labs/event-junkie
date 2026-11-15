# ADR-059: The data-quality check reads the stored flyers of listed sources

## Status

**Accepted (2026-11-15). For a music night with no act, at a source on an allowlist, the data-quality check sends the stored flyer to the model. It
proposes the acts and the start time that the flyer prints. A person accepts or dismisses each suggestion. A flyer that the site withholds is never read.**

Decided in [#5048](https://github.com/enorm-labs/event-junkie/issues/5048), option 3. It extends
[ADR-041](ADR-041_AI_ASSISTED_DATA_QUALITY.md) from text to images. It uses the image reading of
[ADR-049](ADR-049_INSTAGRAM_DRAFTS_READ_THE_POST_IMAGES.md), which covers Instagram posts only.

## Context

On 2026-11-15, 359 upcoming party, concert and festival nights on production had no act and a stored image. Five of nine sampled flyers named acts that
the site did not show. The sample favoured flyers likely to name acts, so the real share is lower. Theme nights, such as salsa or an 80s party, name no act.

The constraints:

- **The model only suggests.** ADR-041 rule 3.
- **One monthly token budget covers all data-quality calls.** ADR-041 rule 5. An image costs about 1,000 to 1,600 input tokens on Haiku (ADR-049).
- **A flyer often shows a photograph of a person.** Anthropic receives such images already for the Instagram drafts, under the same DPA.
- **The images are already stored**, in object storage behind imgproxy (ADR-019). No new fetch from a venue is necessary.

## Candidate options

1. **No flyer reading.** Missing acts stay a fill by hand.
2. **Every music night with no act and a stored image.** The widest reach, and the most calls on theme nights that name nothing.
3. **Only the sources on an allowlist.** Fewer calls and fewer suggestions to dismiss. A source that starts to print acts is missed until someone adds it.

## Decision

**Option 3.** The first list holds six sources. Their sampled flyers named acts. The sources are `badehaus`, `club-ost`, `frannz-club`, `maxxim`, `m-bia`
and `slaughterhouse`. #5052 widens it by the share of suggestions that a person accepts.

The rules:

1. **The allowlist is configuration**: `app.data-quality.flyer.sources`, set from the chart value `importer.flyerLineup.sources`. A source joins without a
   code change.
2. **A withheld image is never read.** Such an event stores no `image_url`, so it is never a candidate.
3. **A candidate is a scheduled, upcoming party, concert or festival.** It has no act and no pinned lineup. Its stored image was not read before.
4. **Every value occurs in the flyer text.** The model copies the text of the flyer into `flyerText`. Each act, the start time and each evidence passage must
   occur in that text. The text must name the event's day, because one flyer can bill several dates.
5. **The suggestions are `artists` and `startTime`**, under the check name `flyer-lineup`. A `startTime` is proposed only when the event has none.
6. **The pass runs inside the nightly classification pass**, with its key, model, lock and monthly budget. A pass that the budget stopped reads no flyer. The
   flyer pass has its own cap, `max-per-run`.
7. **A flyer is read once.** `event_flyer_check` stores the event and the image hash. A new image for the same night is read again.

## Consequences

- **Lineups appear for nights that billed them only on the flyer**, after a person accepts them in the admin app.
- **Stored event images go to Anthropic.** LEGAL.md §7.3a and section 5 of both privacy notices name them. The categories of data do not change.
- **The model can invent text from an image.** The validator cannot see the image. The person who reviews the suggestion compares it with the flyer.
- **A flyer that bills several nights can give the acts of another night.** The day check catches a flyer that does not name the event's day. It cannot
  catch acts listed under the wrong date of a flyer that names both.
- **An accepted act list makes the first act the headliner**, as for every `artists` suggestion.
- **A doors time on a flyer is not proposed.** The admin app cannot apply a `doorsTime` suggestion yet (#5051).

## When to revisit

- Fewer than half of the `flyer-lineup` suggestions of a source are accepted.
- The flyer pass uses more of the monthly budget than the text pass.
- The admin app can show the flyer beside the suggestion.

## References

- [#5048](https://github.com/enorm-labs/event-junkie/issues/5048), the decision issue, with the measurements
- [#4822](https://github.com/enorm-labs/event-junkie/issues/4822), VOID Club, where hand pins were chosen for times alone
- [ADR-041](ADR-041_AI_ASSISTED_DATA_QUALITY.md), [ADR-049](ADR-049_INSTAGRAM_DRAFTS_READ_THE_POST_IMAGES.md) and
  [ADR-050](ADR-050_ONE_SHARED_ANTHROPIC_CLIENT.md)
- `docs/LEGAL.md` §7.3a, the processor entry
