# ADR-058: A decade that the title bills as a party is a genre

## Status

**Accepted (2026-11-11). An event with no source tag and no tag from its acts takes the decades that its title or subtitle bills as a party. Examples are
`80er/90er Party` and `Die 80er Party`. The word `Party` must follow the decade. The tag has the origin `DESCRIPTION`, and it counts toward the three genres
that the event's own text gives.**

Amends rule 4 of [ADR-047](ADR-047_DERIVED_GENRE_TAGS.md). The operator decided this on the report queue of 2026-11-11.

## Context

ADR-047 reads genre words only from the description. It also skips `80s`, `90s` and `2000s` there, because prose uses them in another sense ("since the
80s"). So a decade night whose description is empty, or whose description says `80er-Party`, gets no tag. Clärchens Ballhaus's `80er/90er Party im
Spiegelsaal` and SO36's `DANCING WITH TEARS IN YOUR EYES – Die 80er Party` had none.

The vocabulary already holds the decade tags `80s`, `90s` and `2000s`.

## Decision

1. **Only a decade followed by `Party` counts.** `80er/90er Party`, `90s-Party` and `80'S & 90'S PARTY` give their decades. A bare `the 80s` gives nothing.
2. **Only the title and the subtitle are read.** A decade party in the description stays prose. The description rules of ADR-047 do not change.
3. **The tag comes before the description's genre words, and the first three stay.** The origin is `DESCRIPTION`, because both are the event's own text.
4. **Every other rule of ADR-047 holds.** A source tag wins. Only a concert, party or festival gets the tag. A tag from the acts comes first.

## Consequences

- **About six untagged events in the next weeks get a decade tag.** These are SO36 four times, Clärchens Ballhaus and Ballhaus Wedding.
- **The event page names the origin as the description,** although the words are in the title. A separate origin needs a schema change, and the visitor
  sees the same mark.
- **`Swing` is not read from the title.** Artists such as "Mood II Swing" carry the word, so the rule stays with decades.

## When to revisit

When a decade tag from the title is wrong on the event page or in the data-quality worklist.
