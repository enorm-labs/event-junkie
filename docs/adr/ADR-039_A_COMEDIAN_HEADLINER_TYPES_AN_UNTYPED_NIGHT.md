# ADR-039: A comedian headliner types an untyped night as comedy

## Status

**Accepted (2026-10-03) — when a venue gives no cue for a night, the occupation of its headliner on Wikidata can type it. A comedian, a stand-up
comedian or a cabaret performer types the night `COMEDY`. A cue from the venue always wins. Kabarett is `COMEDY` at every venue.**

**Implemented in [#2314](https://github.com/enorm-labs/event-junkie/issues/2314).** The operator made both decisions in that issue on 2026-10-03.

**Builds on [ADR-031](ADR-031_ARTIST_IDENTITY_HUB.md) and supersedes nothing.** ADR-031 put an `EXACT` MusicBrainz match before any enrichment. This
decision reads one more fact through the same Wikidata link. ADR-031 did not decide whether an artist fact can change an event. This decision does.

## Context

Tempodrom publishes no category. About a third of its programme is comedy or Kabarett. A format line such as `COMEDY - Clubtour 2026` types some of
these nights since #2323. Most comedy nights carry only a name: `Dieter Nuhr` with the line `Live 2026`. The importer stored them as `CONCERT`. On
2026-10-02 about 20 of 60 upcoming Tempodrom rows were comedy stored as concerts.

The only signal outside the page is the act itself. Our `dieter-nuhr` row has an `EXACT` MusicBrainz match. Its Wikidata item Q76152 lists the occupation
(P106) cabaret performer, and also musician. A hand list of comedians at Tempodrom was rejected, because it goes out of date with every tour.

Bar jeder Vernunft and ufaFabrik typed Kabarett as `SHOW`. The decision in #2261 kept Kabarett a show unless the venue said comedy. The operator reversed
that for this issue.

### The constraints a candidate had to satisfy

- **No network call on the import path.** An import reads stored data only (ADR-031: lookups run on their own tick).
- **The venue's word wins.** A category, a format line, a title keyword or a genre cue types the night before any artist fact.
- **Only CC0 data is stored** (ADR-031). Wikidata is CC0. One flag per artist is stored, not the list of occupations.
- **A re-import must not undo the type.** Change detection compares the scraped row with the stored row. A rule that applies only to stored rows makes
  every import write the old type again.

## Decision

**A flag on the artist, a flag on the event, and one rule that applies in two places.**

- **`artist.comedian`** is true when Wikidata P106 names comedian (Q245068), stand-up comedian (Q18545066) or cabaret performer (Q15214752). It is false
  when P106 names none of them, and NULL until the artist lookup tick reads it. Only rows with an `EXACT` match and a Wikidata link are read.
- **`event.type_is_fallback`** is true when the scraper's type is its own default, because the venue gave no cue. A scraper sets it. Only Tempodrom sets it
  today. A festival title or a genre cue that changes the type clears it.
- **The rule**: a fallback night with a comedian headliner is `COMEDY`. A comedian occupation wins over a musician occupation.
- **On import**, `EventUpsertService` applies the rule before it builds the rows. It reads the comedian flags of the headliners in one query.
- **On the artist lookup tick**, after the MusicBrainz and Discogs passes, the occupation pass reads up to 100 rows. Then one `UPDATE` applies the rule to
  every stored night that is not over. This reaches a night imported before its artist was read.
- **Kabarett, Musikkabarett and Musik-Kabarett are `COMEDY`** in the shared category table and at both venues.

The reason that settled it: the venue gives no other signal, and the importer already reads Wikidata for the artist.

## Consequences

- **A musician who is also a comedian is a comedy night at a fallback venue.** Helge Schneider plays concerts too. At Tempodrom his night is now `COMEDY`.
  The operator accepted this.
- **A venue must opt in.** A music venue that defaults to `CONCERT` does not set the flag. The venue is the music signal there. A new opt-in is a change to
  one scraper.
- **A wrong Wikidata occupation types a night wrongly.** The flag is read once per row. After a Wikidata fix, a person sets the column to NULL so the
  next tick reads it again.
- **An act without an `EXACT` match stays `CONCERT`.** On 2026-10-03, 71 of 144 upcoming Tempodrom `CONCERT` rows had none. Osan Yaran and Kanan Gill
  are among them.
- **A comedy night stays `COMEDY` when its artist stops being a comedian.** The next import rebuilds the type from the scrape.
- **The flag reaches no reader.** It is not in the admin API or the public API. LEGAL.md §7.3a and both privacy notices name it.
- **One more request per artist at Wikimedia.** At 250 ms a request, the backfill of a few thousand rows takes a few hours of ticks.

## When to revisit

- **If the replay after a release shows a music night typed `COMEDY` more than once a month.** Then require that no headliner is a musician.
- **If a second venue needs the rule.** Then decide whether the flag belongs in `inferConcertVenueType` for every venue.

## References

- [#2314](https://github.com/enorm-labs/event-junkie/issues/2314) — the issue and both decisions
- [#2323](https://github.com/enorm-labs/event-junkie/pull/2323) — the format-line half
- [#2261](https://github.com/enorm-labs/event-junkie/issues/2261) — Kabarett as a show, reversed here
- [ADR-031](ADR-031_ARTIST_IDENTITY_HUB.md) — the MusicBrainz match and the Wikidata link this reads
- [Wikidata P106](https://www.wikidata.org/wiki/Property:P106) · [Q245068](https://www.wikidata.org/wiki/Q245068) ·
  [Q18545066](https://www.wikidata.org/wiki/Q18545066) · [Q15214752](https://www.wikidata.org/wiki/Q15214752)
