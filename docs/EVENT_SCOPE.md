# Event Scope — what belongs in Event Junkie

What kinds of event this app carries, what it deliberately leaves out, and which questions are still open. The one-line version: **if a Berlin venue puts it on
a stage in the evening, it is in scope.**

This document is the standing reference for that question. Related, and deliberately not duplicated here:
[EVENT_DATA_SOURCES.md](EVENT_DATA_SOURCES.md) tracks _which venues_ are imported;
[DATA_MODEL.md](DATA_MODEL.md) describes the schema; the [issue tracker](https://github.com/enorm-labs/event-junkie/issues) holds the actionable backlog.

---

## 1. The rule

Scope is decided by **format**, not by genre. A venue's evening programme is in; things that happen to be listed on the same page but are not a programme are
out.

That rule has one deliberate corollary, and it is the most useful thing in this document:

> **The venue decides what kind of night it is.**

Where a venue publishes its own category — Astra's "Konzert", Badehaus's "Quiz", Bar jeder Vernunft's genre — that label is mapped rather than second-guessed.
It stops the importers from encoding one person's taste about whether a burlesque revue is a `SHOW` or a `CONCERT`, and it means a venue that reclassifies its
own programme is followed automatically. The mapping table lives in
[`EventTypeMapping.kt`](../events-importer/src/main/kotlin/de/norm/events/scraper/EventTypeMapping.kt), with venue-specific labels passed in per scraper rather
than polluting the shared table.

## 2. Event types in the model

Ten values on [`EventType`](../events-core/src/main/kotlin/de/norm/events/event/Event.kt). Every one is in real use — this is not an aspirational list. Counts
are from the development database (3166 events across 86 sources) and are illustrative of the _mix_, not of coverage:

| Type         | Share | What it covers                                                     | Where it comes from                             |
| ------------ | ----: | ------------------------------------------------------------------ | ----------------------------------------------- |
| `CONCERT`    |  ~62% | Live music with a billed lineup, from back rooms to arenas         | `konzert` / `concert`, and most venues' default |
| `PARTY`      |  ~19% | DJ nights, one-off parties                                         | `party`                                         |
| `SHOW`       |  ~11% | Staged performance — cabaret, burlesque, comedy, musicals, variety | `show`                                          |
| `OTHER`      |   ~3% | The genuine remainder, plus anything a venue labels `sonstiges`    | fallback                                        |
| `READING`    |   ~2% | Literary readings, spoken word, poetry slams                       | `lesung` / `reading`                            |
| `FESTIVAL`   |   ~1% | Multi-day or multi-stage events                                    | `festival`                                      |
| `EXHIBITION` |   ~1% | Gallery shows and openings                                         | `ausstellung` / `exhibition` / `vernissage`     |
| `QUIZ`       |   <1% | Pub quizzes and game nights                                        | `quiz`                                          |
| `SCREENING`  |   <1% | Film screenings, open-air cinema, football "public viewing"        | `screening`, `public viewing`                   |
| `CLUB_NIGHT` |   <1% | A recurring club night distinct from a one-off party               | venue-specific labels                           |

**`OTHER` is a fallback, not a bin.** `parseOrDefault` logs a warning whenever it resolves to `OTHER`, so an unrecognised label is a signal to extend the
mapping rather than something that silently accumulates. The 3% share is a health metric: if it climbs, a venue has started using vocabulary nobody has mapped.

**`CLUB_NIGHT` is a real distinction, not a loose synonym for `PARTY` — and it is load-bearing.** Only 8 events carry it, which makes it look like a candidate
for merging into `PARTY`. It is not, and the reason is in the code rather than in taste:

```kotlin
// ArtistNameMapping.kt — buildArtistsForEventType
if (eventType == EventType.FESTIVAL.name || eventType == EventType.PARTY.name) return emptyList()
```

**Typing a night as `PARTY` discards its lineup.** migas maps its `playing` category to `CLUB_NIGHT` deliberately for exactly this reason: there the _title is
the artist_, so all 8 of its events would lose their artist link on a merge. `PARTY` would also misdescribe the venue, which is a seated listening bar.

So the definition to work from: **`CLUB_NIGHT` is a DJ set where the booked act is the draw** — the artist matters and is extracted. `PARTY` is a night where
the event is the draw and no lineup is claimed. Map to whichever of those is true of the venue, and do not "tidy" one into the other.

_(That `PARTY` and `FESTIVAL` discard artists at all is a separate and larger question — it affects far more than these 8 rows, and it is tracked
in [issue #332](https://github.com/enorm-labs/event-junkie/issues/332).)_

**`EXHIBITION` means an _opening_, not a _run_.** A `vernissage` has a start time on one evening and imports correctly; an exhibition that runs for six weeks
does not fit the model at all, because an event carries a date, not a date range. The type name promises more than the data delivers, and that gap is the
subject of the deferred decision in §5 — not something to work around in a scraper.

## 3. What is deliberately excluded

Four exclusions, each implemented in exactly one place so it can be revisited without archaeology.

### 3.1 Sport

**Not imported.** There is no `SPORT` event type, and mapping fixtures to `OTHER` would bury the concerts they sit among. The arenas force the question rather
than avoid it:

- **Uber Arena / Uber Eats Music Hall** — home to ALBA Berlin and the Eisbären; roughly a third of the listing is basketball and ice hockey. Dropped in
  `AegOverviewPageScraper.isSport`, which matches both the label and the platform's numeric taxonomy.
- **The three Velomax halls** — handball, volleyball and basketball are the biggest strand. `VENUE_EVENT_TYPES`
  simply omits `sport`, so an unmapped row is skipped rather than filed.

The consequence is worth stating plainly: **an arena's imported event count is well below what its own programme page shows**, and that is correct rather than a
bug.

### 3.2 Participation formats

Guided tours, workshops, yoga and qigong sessions, environmental-education slots, drop-in handicraft afternoons. These are things you _take part in_, not things
you _go and see_.

The precedent was set by **Gärten der Welt**: 28 of its 41 upcoming rows were park activities. Importing them would have swamped the actual programme — the
Arena concerts, the open-air cinema, the park festivals — and presented a concert venue as a tour operator. One predicate,
[`isProgrammeCategory`](../events-importer/src/main/kotlin/de/norm/events/scraper/gaertenderwelt/GaertenDerWeltFieldMapping.kt), holds the rule, and it is the
line to change to revisit it.

Note the deliberate asymmetry: a row with **no** category is kept, because the park files its one-off evening events (a games night, a quiz show) under no
category at all, and dropping uncategorised rows would lose them.

### 3.3 Trade fairs and conferences

Not modelled and not imported. Arena Berlin is the clearest case — all five of its upcoming entries were trade fairs (deGUT, BUCHBERLIN, Einstieg Berlin), which
is why it sits in _Blocked_ despite being trivially scrapable. The blocker there was never the markup.

### 3.4 Classical concerts and orchestras

**Not a taste judgement — a data-model one.** Classical fits the existing `CONCERT` type perfectly well, but the shape of the data differs: an orchestra or
ensemble plus a conductor plus soloists, rather than a headliner with support. The `ArtistRole` vocabulary and the genre taxonomy both need a decision before an
orchestral house can be imported honestly.

**RBB Sendesaal is the live example.** Its scraping was solved in the 3 August re-check — the ROC calendar is server-rendered and attributes each concert to a
venue, so `.ConcertListItem-location` is the only filter needed — and it went back to _Blocked_ on **scope, not on scraping**. Answer §5's first question and
the importer is a short job.

## 4. What is in scope, and sometimes surprises people

- **Not just live music.** A theatre, a comedy club or an arena-scale room is in scope. **Bar jeder Vernunft** set that precedent: its programme is imported,
  with the venue's own genre deciding whether a night is a `CONCERT` or a
  `SHOW`.
- **Not just techno.** This is the point of the project, and it is worth repeating in a scope document because Berlin aggregators have a strong pull in that
  direction. Punk, jazz, indie, metal, cabaret and singer-songwriter nights are as in scope as a Berghain listing.
- **Not just ticketed events.** Free events are detected and badged at import.
- **Venue categories imported today**: clubs (52), bars (35), techno clubs (31), concert halls (30), open-air spaces (13), arenas (3), theatres (2), comedy
  clubs (1).

## 5. Coverage decisions

Each of these changes what the app _is_, so **none may be settled by an importer PR.** All five were decided on 2026-08-08; the two that are still open are open
on _sequencing_, not on principle.

| Question                                                                                     | Decision                      | Blocked on       | What it costs                                                                                                            |
| -------------------------------------------------------------------------------------------- | ----------------------------- | ---------------- | ------------------------------------------------------------------------------------------------------------------------ |
| **Comedy clubs?** (Comedy Café Berlin, Quatsch Comedy Club, …)                               | ✅ **Yes**                    | nothing          | Cheapest of the five. Cosmic Comedy is already imported, so this is more venues in a category that exists                |
| **Theatres?** (Volksbühne, Schaubühne, Berliner Ensemble, …)                                 | ✅ **Yes**                    | nothing          | Low. Theater im Delphi, Heimathafen and Bar jeder Vernunft are already imported — coverage, not a new category           |
| **Classical / orchestras?** (Konzerthaus, Philharmonie, RBB Sendesaal, Berliner Symphoniker) | ⏸ **Deferred** — not rejected | the artist model | Medium. `ArtistRole` and the genre vocabulary need extending **first**; the scraping is already solved for RBB Sendesaal |
| **Exhibitions as first-class runs?**                                                         | ⏸ **Deferred**                | the time model   | Medium. A run of weeks needs a date range in the schema and a display decision. Openings already import fine — see §2    |
| **Sport?**                                                                                   | ❌ **No**                     | —                | Settled. Different venues, different audience, and past the point where this is a music app                              |

**What the two yeses unlock.** Comedy and theatre venues can now be moved out of [Blocked](EVENT_DATA_SOURCES.md)
and scaffolded like any other source — no ADR, no model change, no further discussion. Prioritise them by programme richness as usual.

**What the two deferrals mean in practice.** Deferred is not rejected: both are wanted, and both are blocked on a model change that has to land first. Do not
import an orchestral house by flattening its programme into headliner-plus-support — the resulting data would be wrong in a way that is expensive to unpick
later. RBB Sendesaal stays in _Blocked_ until `ArtistRole` grows a conductor and a soloist.

**Sport is settled, and the exclusions in §3.1 are its implementation.** Reopening it means reopening `isSport` and the Velomax type map, not just a
documentation edit.

## 6. Changing scope

If you are adding an importer and the venue's programme does not obviously fit:

1. **Check this document first.** If the answer is here, follow it.
2. **If it is a listed open question, do not settle it in an importer PR.** Say so in the PR and leave the venue in _Blocked_ with the reason. That is exactly
   what RBB Sendesaal is doing.
3. **If it is genuinely new**, add a row here with the reasoning, and put the implementation behind one named predicate — `isSport`, `isProgrammeCategory` —
   rather than scattering conditions through a parser. Every exclusion above is one line to find and one line to change, and that property is worth protecting.
