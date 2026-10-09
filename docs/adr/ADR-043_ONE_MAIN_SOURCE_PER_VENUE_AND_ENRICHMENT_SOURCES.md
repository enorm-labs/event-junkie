# ADR-043: Each venue has one main source, and other sources only enrich the events it lists

## Status

**Accepted (2026-10-04) — the main source of a venue is the venue's own site, feed, ticket shop or official account. It creates, updates and removes that
venue's events. An enrichment source, such as a promoter site or a fan site, fills empty fields on events that the main source lists. It never creates an
event, and it never changes a value that the main source set. An enrichment event matches a main event on venue, date and a start time within one hour.**

**The mechanism is implemented, and no source uses it yet** ([#2593](https://github.com/enorm-labs/event-junkie/issues/2593)).
`event_source.role` is `MAIN` or `ENRICHMENT`, and `EventEnrichmentService` runs an enrichment source's import. The first
enrichment source needs its licence review and the promoter's permission first ([#3002](https://github.com/enorm-labs/event-junkie/issues/3002)).
The Sisyphos importer already works this way inside one importer ([ADR-038](ADR-038_SISYPHOS_PROGRAMME_FROM_THE_CLUB_CALENDAR.md)).
The club's calendar is the main source. The ticket shop and sisy.fan add fields.

**Does not supersede anything.** [ADR-036](ADR-036_SISYPHOS_WEEKENDS_FROM_SISY_FAN.md) and ADR-038 stay as they are, and this ADR makes their pattern
the rule. [ADR-030](ADR-030_RELOCATED_IS_THE_ORIGIN.md) still decides a show that moves to another house.

## Context

[#324](https://github.com/enorm-labs/event-junkie/issues/324) asked for a venue per event, so that a promoter's listing becomes importable. An event's venue
comes from its `event_source` row: `EventUpsertService.upsertAndCleanup(scrapedEvents, venueId, …)` takes one venue for the whole call.

Ten promoter sources wait on this in `docs/EVENT_DATA_SOURCES.md` § Promoters. Their listings name the venue per event. Most of their shows are at venues
that we import already: about 30 of Puschen's 35. At those venues a promoter often knows more than the venue's page: the support act, the set times, the
genre. Sisyphos showed the same thing. The club's calendar has the nights, and only sisy.fan has the line-up.

The constraints:

- **Venue facts are checked by hand.** A venue row needs a checked address, a description licence and a photo. A venue that an importer creates by itself
  has none of these.
- **One source per `sourceId` prefix.** Two sources that build the same `sourceId` take each other's rows
  ([#2557](https://github.com/enorm-labs/event-junkie/issues/2557)).
- **Permission and credit.** sisy.fan is read with its developer's permission, and every page that shows its data links to it (ADR-036).
- **No scraping where the terms forbid it.** Resident Advisor, Instagram and Facebook forbid automated access in their terms.

## Candidate options

1. **A venue per event.** A promoter is one source, and each event resolves its venue by name. It needs an alias table, a change to the upsert and a
   de-duplication rule against the venues' own sources. It treats the promoter as a competitor of the venue, not as a helper.
2. **One thin source per venue.** A promoter is read only for venues that have no importer. It adds nothing at the venues we import.
3. **One main source per venue, and enrichment sources.** The venue's own source owns its events. A promoter or a fan site adds what the venue leaves out.

Two other ways to match were considered. Venue and date alone fails at a hall with two shows a day. A title similarity fails because a promoter's
title often differs from the venue's: "Puschen presents: X" against "X".

## Decision

**Option 3.** The venue's page is the authority on what happens there. Another source is most useful where it adds detail to that, not where it competes
with it.

The rules:

1. **One main source per venue.** It is the venue's own site, calendar feed, ticket shop or official account. It creates, updates and removes the venue's
   events, as every importer does today.
2. **An official account is a main source only through a lawful route.** That is entry by hand ([#347](https://github.com/enorm-labs/event-junkie/issues/347))
   or an official API. Scraping a platform whose terms forbid it is not a route.
3. **A venue with no own publication gets a substitute main source.** A promoter or a fan site can be the main source for that venue, with permission. One
   thin source per venue keeps only that venue's shows, and has its own `sourceId` prefix.
4. **An enrichment source only fills empty fields.** It never creates an event and never removes one. It never changes a value that the main source set.
5. **An enrichment event matches on venue, date and start time.** The start times are at most one hour apart. When two main events match, the title decides. When the titles tie too, neither event is filled (decided 2026-10-10).
   An event that matches nothing is dropped and counted in the run's summary.
6. **Each enrichment source has permission and credit.** Its licence is reviewed like a main source's. An event page that shows its data links to it.

**The reason that settled it** is that the promoters' value at the venues we already import is detail, not more events. Rule 4 takes that value and keeps
the venue's page the only authority on which events exist.

## Consequences

- A show that a promoter lists and the venue's page omits is not imported. That gap is deliberate. Count the unmatched events per run before revisiting it.
- A venue with a second show inside the same hour gets no enrichment on either show when the titles also tie. The run counts such an event as ambiguous, in its log line and its metric. A skipped fill only leaves a field empty. A wrong fill would put one show's lineup or ticket link on the other.
- An enrichment source is a new kind of import. It reads stored events instead of writing new ones. The importer needs a role on `event_source` for this,
  and a run summary that counts filled, unmatched and skipped fields.
- A field that enrichment filled can later be set by the main source. The main source's value then wins at its next import.
- The Instagram and Facebook route needs its own work before any account becomes a main source. It needs an official API and a processor entry in
  `docs/LEGAL.md` and the privacy notice. It needs the venue's consent where the API requires it.

## When to revisit

- The unmatched events from enrichment sources show many real shows that venues do not list.
- A main source and an enrichment source disagree often on a field that the main source owns.

## References

- [#324](https://github.com/enorm-labs/event-junkie/issues/324), the decision issue
- [ADR-036](ADR-036_SISYPHOS_WEEKENDS_FROM_SISY_FAN.md) and [ADR-038](ADR-038_SISYPHOS_PROGRAMME_FROM_THE_CLUB_CALENDAR.md), the Sisyphos precedent
- [#2459](https://github.com/enorm-labs/event-junkie/issues/2459), Comedy in English as a substitute main source for bars
- [#2557](https://github.com/enorm-labs/event-junkie/issues/2557), the shared-prefix defect
- [#347](https://github.com/enorm-labs/event-junkie/issues/347), manual entry
- `docs/EVENT_DATA_SOURCES.md` § Promoters
