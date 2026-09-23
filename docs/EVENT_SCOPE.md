# Event Scope — what belongs in Event Junkie

## The short version

**If a Berlin venue puts it on a stage in the evening, it is in scope.** Scope is decided by **format**, not by genre,
and **the venue decides what kind of night it is** (§1). What is deliberately left out, and which questions are still
open, is §3 and §5.

This document is the standing reference for that question. Related, and deliberately not duplicated here:

- [EVENT_DATA_SOURCES.md](EVENT_DATA_SOURCES.md) tracks _which venues_ are imported
- [DATA_MODEL.md](DATA_MODEL.md) describes the schema
- the [issue tracker](https://github.com/enorm-labs/event-junkie/issues) holds the actionable backlog

---

## 1. The rule

Scope is decided by **format**, not by genre. A venue's evening programme is in. Things listed on the same page that
are not a programme are out.

That rule has one deliberate corollary, and it is the most useful thing in this document:

> **The venue decides what kind of night it is.**

Where a venue publishes its own category, that label is mapped rather than second-guessed — Astra's "Konzert",
Badehaus's "Quiz", Bar jeder Vernunft's genre. It stops the importers from encoding one person's taste about whether a
burlesque revue is a `SHOW` or a `CONCERT`. It also means a venue that reclassifies its own programme is followed
automatically. The mapping table lives in
[`EventTypeMapping.kt`](../events-importer/src/main/kotlin/de/norm/events/scraper/EventTypeMapping.kt). A
venue-specific label is passed in per scraper, rather than polluting the shared table.

## 2. Event types in the model

Ten values on [`EventType`](../events-core/src/main/kotlin/de/norm/events/event/EventEnums.kt). Every one is in real use,
and this is not an aspirational list. The counts come from the development database and illustrate the _mix_, not the
coverage:

| Type         | Share | What it covers                                                     | Where it comes from                             |
| ------------ | ----: | ------------------------------------------------------------------ | ----------------------------------------------- |
| `CONCERT`    |  ~62% | Live music with a billed lineup, from back rooms to arenas         | `konzert` / `concert`, and most venues' default |
| `PARTY`      |  ~19% | Club nights and parties, one-off or recurring                      | `party`                                         |
| `SHOW`       |  ~11% | Staged performance — cabaret, burlesque, comedy, musicals, variety | `show`                                          |
| `OTHER`      |   ~3% | The genuine remainder, plus anything a venue labels `sonstiges`    | fallback                                        |
| `READING`    |   ~2% | Literary readings, spoken word, poetry slams                       | `lesung` / `reading`                            |
| `FESTIVAL`   |   ~1% | Multi-day or multi-stage events                                    | `festival`                                      |
| `EXHIBITION` |   ~1% | A run, opening day to closing day; the vernissage is its evening   | `ausstellung` / `exhibition` / `vernissage`     |
| `QUIZ`       |   <1% | Pub quizzes and game nights                                        | `quiz`                                          |
| `SCREENING`  |   <1% | Film screenings, open-air cinema, football "public viewing"        | `screening`, `public viewing`                   |

**`OTHER` is a fallback, not a bin.** `parseOrDefault` logs a warning whenever it resolves to `OTHER`. An
unrecognised label is therefore a signal to extend the mapping, not something that silently accumulates. The 3% share
is a health metric. If it climbs, a venue is using vocabulary nobody mapped.

**`PARTY` holds every club night.** There is no separate club-night type (#1783). A `PARTY` keeps a lineup that the
venue publishes. Tresor, Matrix and OHM bill their DJs as `PARTY`.

One path discards artists for `PARTY`: `buildArtistsForEventType`, which reads artists from a title when the venue
publishes no lineup. A party title names the night, not an act. That rule is tracked in
[issue #332](https://github.com/enorm-labs/event-junkie/issues/332).

**`EXHIBITION` is a _run_.** An event carries an optional end since ADR-029, and an exhibition uses it. It is one row
from opening day to closing day, with `end_date` set and `end_time` empty. The vernissage time is its `start_time`
when the venue states one. A gallery that lists the show once per open day is folded by `collapseExhibitionRuns` in
the importer, so silent green's 23 rows are one row. A festival's days are not folded: each has its own lineup
(issue #337).

## 3. What is deliberately excluded

Four exclusions, each implemented in exactly one place so it can be revisited without archaeology.

### 3.1 Sport

**Not imported.** There is no `SPORT` event type, and mapping fixtures to `OTHER` would bury the concerts they sit among. The arenas force the question rather
than avoid it:

- **Uber Arena / Uber Eats Music Hall** — home to ALBA Berlin and the Eisbären. Roughly a third of the listing is
  basketball and ice hockey. `AegOverviewPageScraper.isSport` drops it, matching both the label and the platform's
  numeric taxonomy.
- **The three Velomax halls** — handball, volleyball and basketball are the biggest strand. `VENUE_EVENT_TYPES`
  simply omits `sport`, so an unmapped row is skipped rather than filed.

The consequence is worth stating plainly. **An arena's imported event count is well below what its own programme page
shows**, and that is correct rather than a bug.

### 3.2 Participation formats

Guided tours, workshops, yoga and qigong sessions, environmental-education slots, drop-in handicraft afternoons. These are things you _take part in_, not things
you _go and see_.

**Gärten der Welt** set the precedent: 28 of its 41 upcoming rows were park activities. Importing them would have
swamped the actual programme — the Arena concerts, the open-air cinema, the park festivals. It would present a concert
venue as a tour operator. One predicate holds the rule:
[`isProgrammeCategory`](../events-importer/src/main/kotlin/de/norm/events/scraper/gaertenderwelt/GaertenDerWeltFieldMapping.kt).
That is the line to change to revisit it.

Note the deliberate asymmetry. A row with **no** category is kept. The park files its one-off evening events under no
category at all: a games night, a quiz show. Dropping an uncategorised row would lose them.

### 3.3 Trade fairs and conferences

Not modelled and not imported. Arena Berlin is the clearest case. All five of its upcoming entries were trade fairs:
deGUT, BUCHBERLIN, Einstieg Berlin. That is why it sits in _Blocked_ despite being trivially scrapable, and the blocker
there was never the markup.

### 3.4 Classical concerts and orchestras

**Not a taste judgement. A data-model one.** Classical fits the existing `CONCERT` type perfectly well. The shape of
the data differs: an orchestra or ensemble plus a conductor plus soloists, rather than a headliner with support. The
`ArtistRole` vocabulary and the genre taxonomy both need a decision before an orchestral house can be imported
honestly.

**RBB Sendesaal is the live example.** Its scraping is solved. The ROC calendar is server-rendered and attributes each
concert to a venue, so `.ConcertListItem-location` is the only filter needed. It sits in _Blocked_ on **scope, not on
scraping**. Answer §5's first question and the importer is a short job.

## 4. What is in scope, and sometimes surprises people

- **Not just live music.** A theatre, a comedy club or an arena-scale room is in scope. **Bar jeder Vernunft** set that precedent: its programme is imported,
  with the venue's own genre deciding whether a night is a `CONCERT` or a
  `SHOW`.
- **Not just techno.** This is the point of the project, and a scope document should repeat it, because Berlin
  aggregators pull hard in that direction. Punk, jazz, indie, metal, cabaret and singer-songwriter nights are as in
  scope as a Berghain listing.
- **Not just ticketed events.** Free events are detected and badged at import.
- **Venue categories imported today**: clubs (52), bars (35), techno clubs (31), concert halls (30), open-air spaces (13), arenas (3), theatres (2), comedy
  clubs (1).

## 5. Coverage decisions

Each of these changes what the app _is_, so **none may be settled by an importer PR.** All five were decided on
2026-08-08. The one that is still open is open on _sequencing_, not on principle.

| Question                                                                                     | Decision                      | Blocked on       | What it costs                                                                                                            |
| -------------------------------------------------------------------------------------------- | ----------------------------- | ---------------- | ------------------------------------------------------------------------------------------------------------------------ |
| **Comedy clubs?** (Comedy Café Berlin, Quatsch Comedy Club, …)                               | ✅ **Yes**                    | nothing          | Cheapest of the five. Cosmic Comedy is already imported, so this is more venues in a category that exists                |
| **Theatres?** (Volksbühne, Schaubühne, Berliner Ensemble, …)                                 | ✅ **Yes**                    | nothing          | Low. Theater im Delphi, Heimathafen and Bar jeder Vernunft are already imported — coverage, not a new category           |
| **Classical / orchestras?** (Konzerthaus, Philharmonie, RBB Sendesaal, Berliner Symphoniker) | ⏸ **Deferred** — not rejected | the artist model | Medium. `ArtistRole` and the genre vocabulary need extending **first**; the scraping is already solved for RBB Sendesaal |
| **Exhibitions as first-class runs?**                                                         | ✅ **Yes** — done (#337)      | nothing          | Done. ADR-029 gave the row an end; an exhibition is one row from opening to closing day — see §2                         |
| **Sport?**                                                                                   | ❌ **No**                     | —                | Settled. Different venues, different audience, and past the point where this is a music app                              |

**What the two yeses unlock.** A comedy or theatre venue can be moved out of [Blocked](EVENT_DATA_SOURCES.md) and
scaffolded like any other source. No ADR, no model change, no further discussion. Prioritise them by programme richness
as usual.

**What the two deferrals mean in practice.** Deferred is not rejected. Both are wanted, and both are blocked on a
model change that has to land first. Do not import an orchestral house by flattening its programme into
headliner-plus-support. The resulting data would be wrong in a way that is expensive to unpick later. RBB
Sendesaal stays in _Blocked_ until `ArtistRole` grows a conductor and a soloist.

**Sport is settled, and the exclusions in §3.1 are its implementation.** Reopening it means reopening `isSport` and the Velomax type map, not just a
documentation edit.

## 6. Changing scope

If you are adding an importer and the venue's programme does not obviously fit:

1. **Check this document first.** If the answer is here, follow it.
2. **If it is a listed open question, do not settle it in an importer PR.** Say so in the PR and leave the venue in _Blocked_ with the reason. That is exactly
   what RBB Sendesaal is doing.
3. **If it is genuinely new**, add a row here with the reasoning. Put the implementation behind one named predicate —
   `isSport`, `isProgrammeCategory` — rather than scattering conditions through a parser. Every exclusion above is one
   line to find and one line to change, and that property is worth protecting.
