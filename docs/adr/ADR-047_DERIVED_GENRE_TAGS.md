# ADR-047: An untagged event gets its genre from its acts, then from its description

## Status

**Accepted (2026-10-15). An event whose source publishes no genre tag gets derived tags. First, it takes the source tags that its billed acts carry on their
other events. If it still has no tag, it takes the genre words of its own description. Each tag records its origin: `SOURCE`, `ARTIST` or `DESCRIPTION`.
The event page marks a derived tag. A source tag always wins.**

Decided in [#3339](https://github.com/enorm-labs/event-junkie/issues/3339), options 1 and 3. Implemented in #3339 (`V161`).

## Context

On 2026-10-15, production had 2013 events in the next 30 days. 1240 of them had no genre tag, and 735 of these were concerts, parties or festivals. Without
a tag, "More like this" can match only on the venue. The venue programme families (#2389) also stay empty for 46 of 98 venues.

The constraints:

- **No new outbound call and no new stored artist attribute.** Each one is a new processor or a new category of personal data (LEGAL.md §7.3, §7.3a).
- **The reuse terms of a genre source must allow a public site.** MusicBrainz genres are CC BY-NC-SA. The Discogs API terms forbid storing more than the
  importer stores today.
- **The event page must not show a derived tag as the venue's own words.**

## Candidate options

1. **The same artist's other events.** Our own data. It helps only acts that also play at a venue that publishes genres.
2. **Wikidata genre (P136).** CC0, but a new stored artist attribute, and only larger acts have an item.
3. **The event's own description.** The `GenreNormalizer` vocabulary over the text. A description can name the genre of another act.
4. **MusicBrainz genres.** Rejected: CC BY-NC-SA.
5. **Discogs genres or styles.** Rejected: the API terms.

## Decision

**Options 1 and 3, in that order.** The two use only data that the importer already stores. Option 1 alone fills 31 of the 735 events in the 30-day window,
so option 3 gives most of the gain.

The rules:

1. **A source tag always wins.** The importer derives tags only for an event with no `SOURCE` tag. When the source publishes a genre later, the derived tags
   go. A derived tag that the source then names becomes a `SOURCE` tag.
2. **Only a concert, a party or a festival gets a derived tag.** A reading about punk is not a punk night.
3. **`ARTIST`: the `SOURCE` tags of the billed acts' other events, past or upcoming.** A support act does not count. A derived tag never feeds another
   derivation. The three most frequent tags stay.
4. **`DESCRIPTION`: the vocabulary words of the description, only when rule 3 gives nothing.** A word or two adjacent words must be a key of
   `GENRE_SYNONYMS`, so the text never creates a new genre. A support or guest marker ends the part of a sentence that counts. The markers
   are "support", "Support von", "special guest", "plus" and "+". It also ignores a key that prose uses in another sense, such as "house", "world" or "swing". It ignores a short quoted
   span. Such a span is usually the title of an album, a song or a tour. Thus the album title "Whiskey Tango Foxtrot" gives no Tango.
   The first three genres stay.
5. **Each import computes the derived tags again** for the events of its source. An event that the admin API pinned keeps its tags (ADR-042).
6. **A derived tag counts as a tag everywhere else.** "More like this" reads it. The venue programme families and the genre filter read it too.
7. **The public event detail names the origin of each derived tag** in `derivedGenreTags`. The event page shows a derived genre with a dotted underline and
   a text that names the origin. Screen readers read the text.

## Consequences

- **An act's new tag reaches its other events at their next import, not before.** The import of one source does not update the events of another source.
- **A description can still tag a night wrongly**, for example a genre word in a sentence with no support marker. The mark on the event page tells the
  visitor that the tag is not the venue's word.
- **A card in a list shows a derived tag like a source tag.** Only the event detail carries the origin.
- **No LEGAL.md change.** The derivation reads only stored data and stores only tags.

## When to revisit

When the event page or the data-quality worklist shows derived tags that are often wrong. Also when Wikidata (option 2) becomes worth a check of LEGAL.md
§7.3a.
