# ADR-031: Artist identity — MusicBrainz is the hub, and verification comes before enrichment

## Status

**Accepted (2026-09-17) — every artist row is looked up in MusicBrainz after an import commits. The verdict and the
MusicBrainz id are stored. The name is never rewritten from it. Enrichment from the id is a second step, and it runs
only for a row with an `EXACT` verdict.**

**Steps B and C implemented.** Step B is [#1567](https://github.com/enorm-labs/event-junkie/issues/1567). Step C is
[#1568](https://github.com/enorm-labs/event-junkie/issues/1568). Step C+ is decided in
[#1569](https://github.com/enorm-labs/event-junkie/issues/1569). The queue column is a note on #1145. Decided in
[#1549](https://github.com/enorm-labs/event-junkie/issues/1549).

**Does not supersede anything.** [ADR-026](ADR-026_MULTILINGUAL_EVENT_TEXT.md) and
[ADR-027](ADR-027_TRANSLATION_FOLLOWS_THE_DISPLAY_RULE.md) put a third-country service behind the importer and said how
it runs. This decision does the same with a smaller payload. [ADR-019](ADR-019_VENUE_IMAGE_DELIVERY.md) fixed how an
image from an open archive is stored and credited. An artist image reached through Wikidata takes that path unchanged.
[ADR-003](ADR-003_ENTITY_DOMAIN_SEPARATION.md) fixed the boundary where a scraped row becomes an entity. The lookup
runs after that boundary, never inside it.

## Context

Every artist row is a string a scraper cut out of a title or a line-up. The importer has no way to ask whether the
string names an act. Six defects closed in one month (#1110, #1135, #1134, #1132, #302, #1494) stored something
else as an artist. The something was a night, a league, a campaign, a composition, a series and a whole billing. A
person reading a page found each one.
Issue #1145 asks for a count of that shape per source. Issue #322 wants a model to judge the long tail. Neither has a
cheap first answer.

### What the spike measured

`scripts/musicbrainz-match.py` ran over staging's artist rows on 2026-09-17, one search per name, one request a
second. The numbers below are the first 1,322 of 5,730 rows, alphabetically `-` to `Del`. The two halves of that
sample agree within two points. The rest of the run is expected to move a share by a point, not the decision.

| Verdict                                     | Rows | Share |
| ------------------------------------------- | ---- | ----- |
| `exact` — one candidate carries the name    | 694  | 52.5% |
| of which the `country=DE` tie-break decided | 61   |       |
| `ambiguous` — several carry the name        | 172  | 13.0% |
| `none`                                      | 456  | 34.5% |

- **The false-positive control is clean.** All nine names the closed defects settled as not artists come back
  `none`. They are `Kein Bock auf Nazis`, `DLTLLY`, `Vinyl Reduction`, `Sadtember`, `Sonic Morgue`,
  `Drone Art Show: Harry Potter`, `Taschenlampenweihnachtskonzert`, `Corrupted Blood Club Show`, and the glued
  `Current 93 – Sonic Morgue`.
- **A hand read of 25 random `exact` rows found none wrong.** `Accept` resolved to the German band over the
  Japanese one by the tie-break. `Blood & Sun` matched `Blood and Sun`. `André Rieu` matched himself, not his
  orchestra.
- **The head pass recovers the act a series was glued to.** 27 dashed or coloned names matched nothing whole. Nine
  match by their head: `Current 93 – Sonic Morgue` → `Current 93`, `Alister Spence – Within Without` →
  `Alister Spence`, `Andy Strauß - Dosenpfandbetrug` → `Andy Strauß`. `1017 - Colin Stetson` matched a label on its
  head, so a digits-only head is excluded from the rule.
- **Alias equality is not name equality.** `Andy` matched `Horace Andy` through an alias. Three of the 697 raw exact
  rows matched only through an alias or a sort name. The rule below reads them as `ambiguous`.
- **The search score is not confidence.** `Pici` returns `Pici Mazzei` at score 100. The rule never reads the score.
- **A miss proves nothing.** `Avangelic` (#301) is a real DJ and absent. Club line-ups are where coverage is thinnest.
- **MusicBrainz spells better than we do.** 122 of the 694 exact rows differ from ours only in casing or diacritics.
  In every sampled case the MusicBrainz form is the act's own: `A$AP Rocky`, `AK Ausserkontrolle`,
  `Anna von Hausswolff`, `And Also the Trees`. `canonicalArtistName` de-shouts these on import and cannot know.
- **The `none` list is a queue that pays.** Reading its first 380 names filed #1553, #1556, #1560, #1561 and #1564
  in one afternoon. Each was a title or a note stored as an act.

### The service

Free and keyless. Core data is CC0: artists, aliases, relationships and URLs. Tags, genre associations, annotations
and ratings are supplementary data under CC BY-NC-SA 3.0. The limit is one request a second per address, and a
`User-Agent` with a contact address is required. A burst of eight requests earned a 503, and the search path needs
its trailing slash. `PerHostThrottlingFilter` already keeps one host at a polite delay.

### The constraints a candidate had to satisfy

- **Artists are people** (`LEGAL.md` §7.3). §4 of the notice promises no information about anyone's private life.
- **Only CC0 data may be stored.** ShareAlike on our database and NonCommercial on the site are both unacceptable.
- **A miss must never delete or rename a row.** Coverage of Berlin's club line-ups is too thin for absence to mean
  anything.
- **An outage must cost a retry, never a source.** The same rule ADR-026 set for the translation engine.

## Candidate options

- **A. Nothing.** Parser rules, the curated vocabulary (#323) and a model (#322, #473) carry the whole load.
- **B. Verify only.** Store the id and a verdict. Never rewrite a name. The verdict becomes a column in #1145's queue.
- **C. Verify and enrich.** On `EXACT`, read the entity with `inc=url-rels`. First the links. `official homepage`
  fills `websiteUrl`, `social network` by host fills `facebookUrl` and `instagramUrl`, `youtube` fills `youtubeUrl`.
  New columns take `bandcamp`, `soundcloud`, `discogs`, `wikidata`, Resident Advisor and Spotify. Every link fills an
  empty column only, as #1319 ruled for promoters. A `musicbrainzUrl` from the id, shown beside them as the correction path. The Wikidata
  `P18` → Commons image through #1277's pipeline, credit columns and all. The artist `type` for #335.
  `life-span.begin`, `begin-area` and `area` for a `Group` or `Orchestra` only. For a `Person` the same fields are a
  birth date and a birthplace. Skipped: `purchase for download`, `other databases`, `myspace`, `IMDb`.
- **C+. A Wikipedia extract as the description**, through the Wikidata link. Its own decision after C. CC BY-SA 4.0
  needs a text credit line and a `description_attribution` column. A German extract beats a machine translation. A
  person's first paragraph is a birth date and a birthplace, so groups come first.
- **D. Another hub.** Discogs indexes the DJs MusicBrainz lacks, needs a token and allows 60 requests a minute.
  Spotify and Last.fm carry listener counts and forbid reuse of their data outside their own products.

## Comparison

| Question                        | A        | B                | C                    | D                  |
| ------------------------------- | -------- | ---------------- | -------------------- | ------------------ |
| Finds a night stored as an act  | a person | `NONE` + 1 event | same                 | same, fewer misses |
| New external dependency         | no       | one, read-only   | one, read-only       | one, keyed         |
| Personal data leaving the stack | none     | a stage name     | a stage name         | a stage name       |
| Personal data arriving          | none     | id, verdict      | + type, links, image | + listener counts  |
| Licence of what is stored       | —        | CC0              | CC0 + image licence  | terms of service   |
| Cost when the service is down   | —        | a retry          | a retry              | a retry            |
| Gives #482 a popularity number  | no       | no — a key       | no — a key           | Last.fm: yes       |

## Decision

**B now. C for the `EXACT` half, as its own issue. D never as the hub.**

**The reason that settled it: half the rows are confirmed by a lookup that costs nothing and never rewrites
anything.** 52.5% `EXACT` with a clean control and a clean hand read means the verdict is trustworthy where it is
positive. 34.5% `NONE` with `Avangelic` among them means it is a queue where it is negative, never a verdict. That
asymmetry is the whole design: a positive result enriches, a negative result asks a person.

The match rule, which the sweep implements and the spike script documents:

1. A candidate counts only when its folded **name** equals the folded query. Alias or sort-name equality alone is
   `AMBIGUOUS`, never `EXACT`. The search score is never read.
2. One candidate is `EXACT`. Several are narrowed to `country = DE`, and exactly one left is `EXACT`. Anything else
   is `AMBIGUOUS`.
3. A name with a dash or colon that matches nothing whole is queried by its head. A digits-only head is skipped.
   The head verdict is reported beside the whole-name verdict and decides nothing on its own.
4. The stored `name` is never changed by any verdict. A casing or diacritic disagreement is a row in the review queue.

Amended 2026-09-21 ([#302](https://github.com/enorm-labs/event-junkie/issues/302)): rule 3 gains one case where a head
decides. Rule 4 stays whole. At sync, a billed name with a dash or colon is stored as its head. The condition: the
catalogue already holds the head as an `EXACT` row. The glued name must have no `EXACT` row of its own. The evidence is
a verified row, not the head pass's own verdict. No stored name is rewritten: the link goes to the row that exists. A
head whose row is absent, `UNCHECKED`, `AMBIGUOUS` or `NONE` still decides nothing, and the glued name stays for the
queue. No list of series names is kept anywhere. MusicBrainz is the vocabulary.

What is stored per artist in step B: `musicbrainz_id` (nullable), `musicbrainz_match` (`EXACT`, `AMBIGUOUS`, `NONE`,
`UNCHECKED`), `musicbrainz_checked_at`. Nothing else.

What is never stored, in any step: tags and genre associations, ratings and annotations, because of the licence. An
artist's genres are the `genre_tag` rows of its own events, one query and no import. A person's birth date, gender or
birthplace, because of §7.3. A Wikipedia biography is C+ above, not a step of this decision.

Where it runs: its own sweep after an import commits, the shape of `DescriptionTranslationService`. Never inside a
scrape. New and changed names only after the backfill. The backfill is one second per row, so about two hours for
staging. An outage is a counter and a retry, never a `FAILED` source. One host in `PerHostThrottlingFilter` at
1,000 ms or more, and a `User-Agent` naming the repository.

## Consequences

### What this obliges

- **`LEGAL.md` §7.3a, the data-category table.** A row under personal master data for `musicbrainz_id` and the
  verdict. In step C the type and the links, which are already declared. The source line: a public stage name is
  sent as a search term to the MetaBrainz Foundation (California). In step C a Wikidata id goes to the Wikimedia
  Foundation (California). Neither is a processor. A read-only lookup of a public database is not processing on our
  behalf. So the Hetzner AVV is untouched, and §7.3a's warning about a new category does not fire. This sentence is
  where that is written down, so it is not asked again.
- **Both privacy notices, §4, after the "Where this comes from" paragraph.** One sentence each:

    > _en:_ Artist profiles are checked against MusicBrainz, an open music database. Where they match, the official
    > links and the picture come from there and from Wikidata. Only the artist's stage name is sent.
    >
    > _de:_ Künstlerprofile werden mit MusicBrainz abgeglichen, einer offenen Musikdatenbank. Wo sie übereinstimmen,
    > stammen die offiziellen Links und das Bild von dort und aus Wikidata. Übermittelt wird nur der Künstlername.

    The second sentence of each belongs to step C and ships with it, not before.

- **`SCRAPING_POSITION.md`'s politeness position applies to MusicBrainz as to a venue.** The published rate, a
  truthful `User-Agent`, and requests spread through the day rather than a nightly burst.
- **#1145 gains a column**, and its queue becomes `title-derived ∧ one event ∧ NONE`.
- **#322's ADR (#473) assumes this one.** A lookup answers "is this an act" before a model is asked, and the verdict
  is an input to the prompt.
- **A review queue has to exist before C+ does.** 122 spelling disagreements and 172 `AMBIGUOUS` rows are a
  person's work, and #345 is where that surface lives.

### What it does not do

- It does not rename, merge or delete a row. #350's orphans get a verdict, not a deletion.
- It does not give #482 a number. It gives the key Last.fm and Spotify keep one under.
- It does not import a genre. #363's vocabulary stays ours.

## When to revisit

- **If the `EXACT` share on club line-ups stays below a third.** Discogs as a second index for DJs, keyed, with its
  own decision. Never as the hub.
- **If a matched name is wrong in review more than a handful of times a month.** Tighten rule 2 before rule 1.
- **If MusicBrainz changes its licence split or its rate.** The CC0 line above is the reason tags are excluded and
  URLs are not.
- **If #401 needs the id as a join key.** That is the day the column stops being advisory, and this record is
  amended to say so.

## References

- [#1549](https://github.com/enorm-labs/event-junkie/issues/1549) — the decision
- [#1567](https://github.com/enorm-labs/event-junkie/issues/1567) · [#1568](https://github.com/enorm-labs/event-junkie/issues/1568) · [#1569](https://github.com/enorm-labs/event-junkie/issues/1569) — steps B, C and C+
- [#1145](https://github.com/enorm-labs/event-junkie/issues/1145) — title-derived headliners, the queue this feeds
- [#322](https://github.com/enorm-labs/event-junkie/issues/322) · [#473](https://github.com/enorm-labs/event-junkie/issues/473) — AI-assisted data quality
- [#1277](https://github.com/enorm-labs/event-junkie/issues/1277) — Wikidata `P18` → Commons, the image path step C reuses
- [#1233](https://github.com/enorm-labs/event-junkie/issues/1233) — the third-country processor precedent
- [#482](https://github.com/enorm-labs/event-junkie/issues/482) · [#401](https://github.com/enorm-labs/event-junkie/issues/401) — what the id is a key for
- [#1553](https://github.com/enorm-labs/event-junkie/issues/1553) · [#1556](https://github.com/enorm-labs/event-junkie/issues/1556) · [#1560](https://github.com/enorm-labs/event-junkie/issues/1560) · [#1561](https://github.com/enorm-labs/event-junkie/issues/1561) · [#1564](https://github.com/enorm-labs/event-junkie/issues/1564) — what the `none` list found
- `scripts/musicbrainz-match.py` — the spike, and the rule it documents
- [MusicBrainz API](https://musicbrainz.org/doc/MusicBrainz_API) · [data licence](https://musicbrainz.org/doc/About/Data_License) · [rate limiting](https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting)
- [`docs/LEGAL.md`](../LEGAL.md) §7.3, §7.3a
