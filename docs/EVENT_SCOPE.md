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
and this is not an aspirational list. The shares come from the 4,889 upcoming events on production in October 2026.
They show the _mix_, not the coverage:

| Type         | Share | What it covers                                                   | Where it comes from                             |
| ------------ | ----: | ---------------------------------------------------------------- | ----------------------------------------------- |
| `CONCERT`    |  ~50% | Live music with a billed lineup, from back rooms to arenas       | `konzert` / `concert`, and most venues' default |
| `COMEDY`     |  ~23% | Stand-up, Kabarett, comedy shows and comedy theatre              | `comedy` / `stand-up` / `kabarett`, a comedian  |
| `PARTY`      |  ~14% | Club nights and parties, one-off or recurring                    | `party`                                         |
| `SHOW`       |   ~7% | Staged performance — burlesque, musicals, variety                | `show`                                          |
| `OTHER`      |   ~3% | The genuine remainder, plus anything a venue labels `sonstiges`  | fallback                                        |
| `READING`    |   ~1% | Literary readings, spoken word, poetry slams                     | `lesung` / `reading`                            |
| `FESTIVAL`   |   <1% | Multi-day or multi-stage events                                  | `festival`                                      |
| `QUIZ`       |   <1% | Pub quizzes and game nights                                      | `quiz`                                          |
| `SCREENING`  |   <1% | Film screenings, open-air cinema, football "public viewing"      | `screening`, `public viewing`                   |
| `EXHIBITION` |   <1% | A run, opening day to closing day; the vernissage is its evening | `ausstellung` / `exhibition` / `vernissage`     |

**`OTHER` is a fallback, not a bin.** `parseOrDefault` logs a warning whenever it resolves to `OTHER`. An
unrecognised label is therefore a signal to extend the mapping, not something that silently accumulates. The 3% share
is a health metric. If it climbs, a venue is using vocabulary nobody mapped.

**`PARTY` holds every club night.** There is no separate club-night type (#1783). A `PARTY` keeps a lineup that the
venue publishes. Tresor, Matrix and OHM bill their DJs as `PARTY`.

One path discards artists for `PARTY`: `buildArtistsForEventType`, which reads artists from a title when the venue
publishes no lineup. A party title names the night, not an act. The same applies to `FESTIVAL`.

**No new type for dance, theatre or talks.** Dance and theatre stay `SHOW`: Delphi, Admiralspalast and Kesselhaus map
`tanz` and `theater` to `SHOW`. Talks and panels stay `READING` (Urania) or `OTHER`. On 2026-10-09, `OTHER` held 158
of the 5,278 upcoming events on production (3.0%). Of the 86 `OTHER` events from 2026-10-09 to 2026-11-09, only 9 talks
and 2 dance or theatre events fit no existing type. A type for 11 events in a month buys no useful filter. The other
75 are mapping gaps into existing types. Among them are 24 club nights, 15 open stages and jams, 14 concerts and 5
comedians. Issue #2992 fixes these mapping gaps (#332).

**`EXHIBITION` is a _run_.** An event carries an optional end since ADR-029, and an exhibition uses it. It is one row
from opening day to closing day, with `end_date` set and `end_time` empty. The vernissage time is its `start_time`
when the venue states one. A gallery that lists the show once per open day is folded by `collapseExhibitionRuns` in
the importer, so silent green's 23 rows are one row. Gärten der Welt lists the show once, with the run as a date
range, and the importer stores that range. A festival's days are not folded: each has its own lineup
(issue #337).

**`COMEDY` can come from the act.** Tempodrom gives no category for a night billed by a name only. When the headliner is
a comedian or a cabaret performer on Wikidata, the night is `COMEDY`. A cue from the venue always wins.
[ADR-039](adr/ADR-039_A_COMEDIAN_HEADLINER_TYPES_AN_UNTYPED_NIGHT.md) has the rule.

**The spoken language comes only from the venue's own words.** An explicit phrase sets it: "in English", "auf Deutsch",
"Sprache: Deutsch", "OmU". It is set for `COMEDY`, `QUIZ`, `READING`, `SCREENING`, `SHOW` and `OTHER` only. The language of the
description is never used, because a German text often describes an English show (#2523).

**A venue can have one house language for all its shows.** Its importer declares it on one of two grounds. The venue
states the language, and the KDoc quotes the statement with its URL. Or the programme is German in practice, and the
KDoc gives the measured share and the date. German in practice needs at least 90% German descriptions among the
language-type events. It also needs no show in another language without an explicit phrase. A mixed programme gets no
house language. A small sample, or one that is mostly `OTHER`, also gets none. An event whose own text states no
language gets the house language, if the event has a language type. An explicit phrase on the event always wins (#2584).

## 3. What is deliberately excluded

Five exclusions. Each section names the predicates that implement it, so a rule can be revisited without archaeology.

### 3.1 Sport

**Not imported.** Esports is sport. There is no `SPORT` event type, and mapping fixtures to `OTHER` would bury the concerts they sit among. The arenas force the question rather
than avoid it:

- **Uber Arena / Uber Eats Music Hall** — home to ALBA Berlin and the Eisbären. Roughly a third of the listing is
  basketball and ice hockey. `AegOverviewPageScraper.isSport` drops it, matching both the label and the platform's
  numeric taxonomy.
- **The three Velomax halls** — handball, volleyball and basketball are the biggest strand. `VENUE_EVENT_TYPES`
  simply omits `sport`, so an unmapped row is skipped rather than filed.
- **Tempodrom** — the snooker German Masters plays here each January. The venue publishes no category. The scraper drops a
  row when its title or format line names a sport, for example `Snooker` or `Darts`. An esports tournament is sport, so
  `GeoGuessr` and `E-Sport` also drop a row. `World Championship` alone does not, because a music or dance contest can
  use it.

The consequence is worth stating plainly. **An arena's imported event count is well below what its own programme page
shows**, and that is correct rather than a bug.

### 3.2 Participation formats

Guided tours, workshops, yoga and qigong sessions, environmental-education slots, drop-in handicraft afternoons. These are things you _take part in_, not things
you _go and see_. An open stage, a jam session or a karaoke night is not on this list. People come to watch it, and it
is a recurring night (§5).

**Gärten der Welt** set the precedent: 28 of its 41 upcoming rows were park activities. Importing them would have
swamped the actual programme — the Arena concerts, the open-air cinema, the park festivals. It would present a concert
venue as a tour operator. Each venue that mixes these into its programme has one predicate, on the signal its page gives:

| Venue               | Predicate                                            | Signal                                                          |
| ------------------- | ---------------------------------------------------- | --------------------------------------------------------------- |
| Gärten der Welt     | `GaertenDerWeltFieldMapping.isProgrammeCategory`     | The park's activity categories                                  |
| Urania              | `UraniaEventFields.isParticipationFormat`            | The format names a `Workshop`, `Spaziergang` or `Rundgang`      |
| Urban Spree         | `UrbanSpreeOverviewPageScraper.isUrbanSpreeWorkshop` | The `Workshops` category, or a title that opens with `Workshop` |
| silent green        | `SilentGreenEventFields.isSilentGreenTour`           | No category, and a title that names a `Führung` or `Rundgang`   |
| Showfenster Theater | `ShowfensterOverviewPageScraper.isOutOfScope`        | The Eventfrog category `kurse-seminare`: the swing course       |

A title that names an in-scope format wins at Urania. `Das philosophische Pubquiz` has the format `Workshop`, and it is a
quiz. Arcanoa's `Songwriting workshop` stays, because an open stage follows it on the same night.

Note the deliberate asymmetry. A row with **no** category is kept. The park files its one-off evening events under no
category at all: a games night, a quiz show. Dropping an uncategorised row would lose them.

### 3.3 Trade fairs and conferences

Not modelled and not imported. Arena Berlin is the clearest case. All five of its upcoming entries were trade fairs:
deGUT, BUCHBERLIN, Einstieg Berlin. That is why it sits in _Blocked_ despite being trivially scrapable, and the blocker
there was never the markup.

Three imported venues mix one into their programme. Each drops it on the words its page uses:

| Venue                | Predicate                                        | Signal                                                     |
| -------------------- | ------------------------------------------------ | ---------------------------------------------------------- |
| Uber Eats Music Hall | `billsTradeFairOrConference` (`TradeFair.kt`)    | A `…messe`, `Konferenz`, `Conference`, `Kongress` or `B2B` |
| Theater im Delphi    | `billsTradeFairOrConference`                     | The same words in the title                                |
| silent green         | `SilentGreenEventFields.isSilentGreenConference` | `Konferenz` the only category label, or the same words     |

A bare `Messe` is not a fair: SO36 bills a band of that name.

### 3.4 Classical concerts and orchestras

**Not a taste judgement. A data-model one.** Classical fits the existing `CONCERT` type perfectly well. The shape of
the data differs: an orchestra or ensemble plus a conductor plus soloists, rather than a headliner with support. The
`ArtistRole` vocabulary and the genre taxonomy both need a decision before an orchestral house can be imported
honestly.

An orchestra that accompanies a staged show does not make the show a classical concert. Tempodrom's `Roncalli und
Deutsches Symphonie-Orchester Berlin` is a circus gala, so it stays as a `SHOW` and bills no artist.

**RBB Sendesaal is the live example.** Its scraping is solved. The ROC calendar is server-rendered and attributes each
concert to a venue, so `.ConcertListItem-location` is the only filter needed. It sits in _Blocked_ on **scope, not on
scraping**. Answer §5's first question and the importer is a short job.

### 3.5 Children's shows

**Not imported.** A puppet show for a Kita group at 10:00 is not an evening on a stage for an adult audience. The same
holds for a family sing-along on a Saturday afternoon. A show for teenagers with a minimum age, such as "ab 13 Jahre",
stays in scope.

A children's band on a real stage stays, such as `Deine Freunde` at Tempodrom or `Lichterkinder` at Uber Eats Music
Hall. Nothing on those pages marks a show for small children, and adults go to these concerts too.

| Venue                       | Predicate                                                      | Signal                                                                                           |
| --------------------------- | -------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| ufaFabrik                   | `UfaFabrikMonthPageScraper.isChildrensShow`                    | A price per child, per Kita child or per Erzieher\*in                                            |
| Peter Edel, Wühlmäuse, SO36 | `billsChildrensShow` (`ChildrensShow.kt`), on title + subtitle | `für Kinder`, a `Familien-` show, a `Kinder…` format, an age range (`0-18 Monate`, `ab 4 Jahre`) |
| Showfenster Theater         | `ShowfensterOverviewPageScraper.isOutOfScope`                  | The Eventfrog category `kinderveranstaltungen`, or `billsChildrensShow` on title + description   |

`billsChildrensShow` keeps a band named after children (`Muttis Kinder`) and a show for teenagers (`ab 13 Jahre`). A
new venue with children's shows calls it when its page uses these words, or gets its own signal. Record it here.

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

Each of these changes what the app _is_, so **none may be settled by an importer PR.** The first five were decided on
2026-08-08, the recurring nights on 2026-09-30, the children's shows on 2026-10-01, and online-only livestreams on 2026-10-04.
The one that is still open is open on _sequencing_, not on principle.

| Question                                                                                     | Decision                      | Blocked on       | What it costs                                                                                                            |
| -------------------------------------------------------------------------------------------- | ----------------------------- | ---------------- | ------------------------------------------------------------------------------------------------------------------------ |
| **Comedy clubs?** (Comedy Café Berlin, Quatsch Comedy Club, …)                               | ✅ **Yes**                    | nothing          | Cheapest of the eight. Cosmic Comedy is already imported, so this is more venues in a category that exists               |
| **Theatres?** (Volksbühne, Schaubühne, Berliner Ensemble, …)                                 | ✅ **Yes**                    | nothing          | Low. Theater im Delphi, Heimathafen and Bar jeder Vernunft are already imported — coverage, not a new category           |
| **Classical / orchestras?** (Konzerthaus, Philharmonie, RBB Sendesaal, Berliner Symphoniker) | ⏸ **Deferred** — not rejected | the artist model | Medium. `ArtistRole` and the genre vocabulary need extending **first**; the scraping is already solved for RBB Sendesaal |
| **Exhibitions as first-class runs?**                                                         | ✅ **Yes** — done (#337)      | nothing          | Done. ADR-029 gave the row an end; an exhibition is one row from opening to closing day — see §2                         |
| **Sport?**                                                                                   | ❌ **No**                     | —                | Settled. Different venues, different audience, and past the point where this is a music app                              |
| **Recurring bar nights?** (a punk Tresen, an Open Jam, a karaoke night)                      | ✅ **Yes** — if not daily     | nothing          | Low. A night that recurs weekly or fortnightly is programme. A bar that is the same every evening is opening hours       |
| **Children's shows?** (Kita mornings, family afternoons)                                     | ❌ **No**                     | —                | Settled. Not an evening programme for an adult audience; §3.5 holds the rule                                             |
| **Online-only livestream?**                                                                  | ❌ **No**                     | —                | Settled. There is no venue to go to. A public viewing at a venue is a `SCREENING` and stays in                           |

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
