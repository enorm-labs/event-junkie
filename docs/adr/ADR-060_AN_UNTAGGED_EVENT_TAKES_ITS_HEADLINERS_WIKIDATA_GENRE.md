# ADR-060: An untagged event takes its headliner's Wikidata genre

## Status

**Accepted (2026-11-17). The importer reads the genres (P136) of an act's Wikidata item and stores them on the artist. An event that has no tag after
the rules of ADR-047 takes the mapped genres of its billed acts. Such a tag has the origin `WIKIDATA`.**

Decided and implemented in [#5222](https://github.com/enorm-labs/event-junkie/issues/5222) (`V265`).

**Amends [ADR-047](ADR-047_DERIVED_GENRE_TAGS.md) and supersedes nothing.** ADR-047 rejected Wikidata P136 for now, because it adds a stored artist
attribute. Its "When to revisit" names a check of LEGAL.md §7.3a as the condition. This ADR makes that check. The rules of ADR-047 stay as they are, and
this decision adds a fourth origin after them. [ADR-039](ADR-039_A_COMEDIAN_HEADLINER_TYPES_AN_UNTYPED_NIGHT.md) reads P106 through the same Wikidata
link. This decision reads one more property of the same item.

## Context

A "Report a problem" report on event 14578 (Maffay + Oerding, Waldbühne) asked for a genre. Waldbühne's text licence is `PROHIBITED`, so the importer
stores no description and derives no description tag. None of its 18 upcoming music events had a genre tag. The same gap holds for each source with a
prohibited text licence and no genre field.

The measurement of 2026-11-17 used the public API of production and the Wikidata query service. It covered the events from 2026-11-17 to 2027-12-31:

| Count                                                        |   Value |
| ------------------------------------------------------------ | ------: |
| Upcoming events                                              |    7430 |
| Events with no genre tag                                     |    5012 |
| Untagged concerts, parties and festivals with a billed act   |     927 |
| Billed acts on those events                                  |    1294 |
| Acts with an `EXACT` match and a Wikidata item that has P136 |     301 |
| Of those, acts whose P136 maps onto the vocabulary           |     281 |
| **Events whose headliner gives a mapped genre**              | **270** |

The 927 events have no tag after the rules of ADR-047. Thus each of the 270 is a new tag. The gain is at the large touring venues: Columbiahalle 35,
Tempodrom 26, Uber Arena 22, Admiralspalast 17 and Waldbühne 16 of 18. Clubs gain almost nothing, because their acts seldom have a Wikidata item.

A sample of 25 events showed 22 correct tags. Wikidata can be wrong: Roland Kaiser has "blues" beside "schlager music". A support act can also mislead.
The blink-182 night took the genres of Billy Talent when every billed act counted.

### The constraints a candidate had to satisfy

- **The data must allow reuse on a public site.** Wikidata is CC0. MusicBrainz genres (CC BY-NC-SA) and Discogs genres (API terms) stay rejected, as
  ADR-047 records.
- **No network call on the import path** (ADR-031). The lookup tick reads Wikidata. An import reads stored data only.
- **A derived tag must not look like the venue's word** (ADR-047 rule 7).
- **No new category of personal data and no new recipient** without a change to LEGAL.md §7.3a and to the Hetzner AVV.

## The LEGAL.md check

The check of §7.3 and §7.3a gives these results:

- **The category exists.** A genre of an act is a public, professional fact. It goes into the row "Personal master data", beside the comedian flag of
  ADR-039. The Hetzner AVV covers that row, so the AVV needs no change.
- **The recipient exists.** The importer already sends a Wikidata id to the Wikimedia Foundation for the picture, the occupation and the Wikipedia lead.
  A read of P136 sends the same id and nothing else.
- **No new data subject.** The read applies only to an artist row that exists and has an `EXACT` match.
- **The removal route of §7.3 covers it.** A removed artist loses its genres with its other attributes.

The implementation PR must change three documents in the same change:

- LEGAL.md §7.3: a paragraph after the comedian paragraph.
- LEGAL.md §7.3a: the genres in the "Personal master data" row.
- The privacy notice: the Wikidata sentence in `PrivacyView.de.vue` and `PrivacyView.en.vue`.

## Candidate options

1. **Wikidata P136 on the artist, derived onto untagged events.** It fills 270 events. It needs a stored attribute, a map from Wikidata items to tags,
   and the documents above.
2. **Leave these events untagged.** No cost. The event page, "More like this" and the programme families of the large venues stay without a genre.

## Decision

**Option 1.** The reach is 270 events at the venues that the report named, and the LEGAL.md check finds no new category and no new recipient.

The rules:

1. **The artist stores Wikidata items, not tag names.** The lookup tick reads P136 for each artist with an `EXACT` match and a Wikidata link. It stores the
   item ids, for example `Q3071`. A change to the map then applies at the next import, with no new read. It also makes the unmapped items countable.
2. **A hand-written map takes an item id to a tag of the vocabulary.** An item that is not in the map gives no tag. The map uses item ids, not labels.
   `Q3071` (punk rock) has no English label, and 19 headliners carry it.
3. **`WIKIDATA` comes last.** An event takes a `WIKIDATA` tag only when the rules of ADR-047 give it no `SOURCE`, `ARTIST` or `DESCRIPTION` tag. Thus no
   existing tag changes.
4. **A support act does not count.** The rules 2 and 5 of ADR-047 apply too. Only a concert, a party or a festival gets the tag. Each import computes
   the tag again. A pinned event keeps its tags.
5. **At most three tags, in the order of the item's statements.** This keeps most of the noise out, for example Roland Kaiser's "blues".
6. **The event page names Wikidata as the origin**, in the same way as the other derived origins.

## Consequences

- **A new origin value.** `GenreTagOrigin` gains `WIKIDATA`, and `event_genre_tag.origin` needs a migration.
- **The map is a new thing to maintain.** A new genre in Wikidata gives no tag until someone adds it to the map. The stored item ids make the misses
  countable, so a data-quality metric can show them.
- **Wikidata's errors become our tags.** The event page marks the tag as derived. A wrong genre on Wikidata is corrected on Wikidata, and the next
  lookup reads the correction.
- **One more Wikidata read per artist.** The lookup tick already reads P106 for each such artist, at the Wikimedia pace. The P136 read adds one request
  per row, or none when both properties come in one read.
- **A tag reaches an event at the next import after the lookup.** An act that is new to the database has no genre until the tick reads it.
- **Three documents change with the implementation**: LEGAL.md §7.3, LEGAL.md §7.3a and the privacy notice in both languages.

## When to revisit

When the worklist shows many wrong `WIKIDATA` tags. Also when the share of unmapped items rises above the share measured here (20 of 301 acts).

## References

- [#5222](https://github.com/enorm-labs/event-junkie/issues/5222) — the decision and the measurement
- [ADR-047](ADR-047_DERIVED_GENRE_TAGS.md) — derived genre tags, which this ADR amends
- [ADR-039](ADR-039_A_COMEDIAN_HEADLINER_TYPES_AN_UNTYPED_NIGHT.md) — the P106 read through the same Wikidata link
- [ADR-031](ADR-031_ARTIST_IDENTITY_HUB.md) — the `EXACT` match before any enrichment, and the lookup tick
- [#3339](https://github.com/enorm-labs/event-junkie/issues/3339), [#2389](https://github.com/enorm-labs/event-junkie/issues/2389) — ADR-047's
  decision, and the rejection of MusicBrainz genres
- [Wikidata P136](https://www.wikidata.org/wiki/Property:P136) — the genre property
