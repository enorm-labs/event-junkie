# ADR-026: Multilingual event text — the publisher's own words per language, and machine translation only under a grant

## Status

**Accepted (2026-09-07) — event text is stored per language. The publisher's own text is served in the visitor's locale
wherever the publisher wrote it. Machine translation happens only for a source whose grant names translation, and
the page says so. Everything else is served in the language it was written in, marked as such. Titles are never
translated.**

**Partly implemented.** [#2701](https://github.com/enorm-labs/event-junkie/pull/2701) splits a description that holds
both languages at its marker, for ten sources. This builds the item listed below as deliberately deferred. Five
importers also read the page that a source publishes in its other language, as
[EVENT_DATA_SOURCES.md](../EVENT_DATA_SOURCES.md#-second-language) records. The rest is Phase 2 work. Issue
[#330](https://github.com/enorm-labs/event-junkie/issues/330) holds the sources that remain. Issue [#470](https://github.com/enorm-labs/event-junkie/issues/470) translates under a grant. Issue
[#808](https://github.com/enorm-labs/event-junkie/issues/808) is where a grant comes from. Decided in
[#469](https://github.com/enorm-labs/event-junkie/issues/469).

**Rule 3 is superseded by [ADR-027](ADR-027_TRANSLATION_FOLLOWS_THE_DISPLAY_RULE.md) (2026-09-08), the same day.**
Translation now follows the display rule, so silence permits it and only `PROHIBITED` withholds. **The § 23 UrhG
reasoning below is not withdrawn.** It is the case against that change, and it is kept here in full. That is why
ADR-027 is a separate document rather than an edit to this one. Rules 1, 2 and 4 stand.

**Rule 4 is narrowed by [ADR-040](ADR-040_EVENT_INLANGUAGE_IS_THE_SPOKEN_LANGUAGE.md) (2026-10-03).** An event's
`inLanguage` is the spoken language where the venue states one. The `lang` attribute of a description is unchanged.

**Partially supersedes [ADR-013](ADR-013_LOCALISATION.md).** Its §3 table put event titles and descriptions in one row,
_"third-party content, do not translate"_. This ADR replaces that row for descriptions and keeps it for titles. The
other rows of that table stand. Venue, artist and district names stay as written. Event types are ours and are
translated. Genre tags behave like data.

## Context

The chrome is localised and the data is not. A visitor on `/de/` reads German labels around an English description,
and a visitor on `/en/` reads English labels around a German one. ADR-013 chose that on purpose, for the reason its
§3 row gives: the text belongs to the publisher.

**What forced the decision** is that the epic behind [#468](https://github.com/enorm-labs/event-junkie/issues/468)
cannot start without an answer. One of its four options creates a legal act the project does not perform today.
The milestone comment on #469 moved the decision into `v1.0` for that reason, and left the building in Phase 2.

### What the corpus looks like

Measured on 2026-09-07 against production, through the BFF, every upcoming event and its detail page:

| Measure                              | Value                                                     |
| ------------------------------------ | --------------------------------------------------------- |
| Events                               | 3,246. 2,177 carry a description                          |
| Description corpus                   | 2,719,262 characters. Median 1,027, p90 2,498, max 16,763 |
| Title corpus                         | 66,514 characters                                         |
| German descriptions                  | 1,386 events, 1,849,887 characters                        |
| English descriptions                 | 598 events, 696,803 characters                            |
| Flagged as mixed by a stop-word test | 133 events, 166,768 characters                            |
| Too short to classify                | 46 events                                                 |
| Sources with any description         | 59 of 86                                                  |
| Sources that publish German only     | 7. English only: 10. The other 42 mix per event           |
| New volume, steady state             | About 800 events and 0.6–0.9 million characters a month   |

Three things in that table decide the shape of the work:

1. **Language is a property of the event, not of the source.** 42 of the 59 sources with text publish some events in
   German and some in English. A per-source language flag would be wrong for most of the corpus. Detection must run
   per description.
2. **Both languages in one field is rare, and real.** Klunkerkranich writes German, then a literal `[EN]` marker, then
   English, in one field. SO36 does something similar for about nine events. The mixed count above is a crude
   heuristic, and it overcounts. Matrix's 52 flagged events are German with English fragments. The audit in #330
   replaces the number.
3. **Berghain is not the both-languages case the issue assumed.** It has nine described events in production, all in
   English.

### What translation would cost

The issue asked for the corpus size before treating cost as a factor. A full backfill is about 2.55 million
characters once. Steady state is under one million characters a month.

| Engine                         | Backfill, once                                  | Steady state, per month                                         | Source of the price                                                                                                                                  |
| ------------------------------ | ----------------------------------------------- | --------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| DeepL API                      | Exceeds the Developer plan's one-time 1 million | Growth, $26 a month billed annually, 12 million a year included | Third-party summaries dated 2026-06 and 2026-07. **API Free and API Pro closed to new customers in July 2026.** DeepL's own pages returned no prices |
| Google Cloud Translation, NMT  | About $40 after the free 500,000                | $0–10                                                           | Third-party summaries. $20 a million, 500,000 free a month                                                                                           |
| Claude Haiku 4.5 via Spring AI | About $5                                        | About $1–2                                                      | List price $1 a million input tokens, $5 a million output, at roughly 3.5 characters a token                                                         |
| LibreTranslate, self-hosted    | Node time                                       | Node time                                                       | Not measured. Visibly weaker on German and English                                                                                                   |

**Cost is not the deciding factor.** Every row is inside a rounding error of the hosting bill. The issue's table put
the LLM as the most expensive engine per character. At this volume it is the cheapest, and it is the only engine that
takes an instruction in prose. The DeepL row carries a warning: pricing pages are not the truth, the account's own
form is. Nobody signs up for anything under this ADR.

### The constraints any option had to satisfy

| Constraint                                                                  | Fixed by                                                                                                            |
| --------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| Only `PROHIBITED` withholds a description. `UNCLEAR` and unreviewed display | [SCRAPING_POSITION.md §3.1](../SCRAPING_POSITION.md), decided on #283                                               |
| 83 of 86 sources are `UNCLEAR`, and only #808 moves one to `PERMITTED`      | [licence-review/README.md](../licence-review/README.md)                                                             |
| The site aggregates and links back. It does not republish                   | [ADR-007](ADR-007_WEB_SCRAPING_STRATEGY.md), and SCRAPING_POSITION.md §6 names republishing as a change of position |
| The publisher's text is the record. Anything derived must be regenerable    | [#470](https://github.com/enorm-labs/event-junkie/issues/470), step 3                                               |
| Curated vocabulary has one home, whichever #323 chooses                     | [#323](https://github.com/enorm-labs/event-junkie/issues/323)                                                       |
| Structured data and `hreflang` already exist per locale and must stay valid | [ADR-014](ADR-014_RENDERING_STRATEGY.md), validated on #290                                                         |

### The legal reading

This is our own reasoning, and not a legal opinion, as the title of SCRAPING_POSITION.md says.

§3.1 of that document already names the description as the weak point. We store it under § 16 UrhG and make it
available under § 19a UrhG, and for 83 sources the justification is `UNCLEAR`.

A translation adds a third act. Under § 23 (1) UrhG an adaptation or other transformation of a work may be published
or exploited only with the author's consent. A translation is the textbook example. § 3 UrhG protects the
translation as a work of its own, but expressly "unbeschadet des Urheberrechts am bearbeiteten Werk". No statutory
exception reaches this use. § 51 needs a purpose the quotation serves. § 44a covers only transient copies. § 44b
covers text and data mining for analysis, not the publication of the result. The e-recht24 boilerplate that decides
most of the licence review names "Bearbeitung" explicitly as an act that needs written consent outside the statutory
limits.

So a machine translation of an `UNCLEAR` description is a strictly weaker posture than the display of that
description. The display is already the weakest point of the document. A grant can cover translation, and the
#808 mail is the one place we can ask for it.

Two things create no new act. Detecting the language of a text is analysis. Serving the publisher's own second-language
text is the same act we already perform on the first.

## Candidate options

The four options are the ones #469 listed.

1. **Leave it.** Event text stays in the publisher's language, and the page claims the locale's language for it. Zero
   cost, and the inconsistency the site ships with today.
2. **Import both languages only.** Store per-language text where the source publishes it, and fall back to whatever
   exists. Every string is publisher-written. Coverage is whatever the venues do, which the corpus shows is a small
   minority.
3. **Import both, and machine-translate the rest.** Full coverage. The price is translation cost, a quality floor we do
   not control, and a § 23 act for 83 sources.
4. **Translate in the browser, or leave it to the browser's own feature.** Nothing stored, nothing paid, no derivative
   work made by us. No control over quality, no search value, and no help for the title in a list.

## Comparison

|                                    | 1. Leave it | 2. Both languages          | 3. Translate the rest      | 4. Browser                       |
| ---------------------------------- | ----------- | -------------------------- | -------------------------- | -------------------------------- |
| New copyright act                  | None        | None                       | § 23 UrhG, per description | None                             |
| Coverage of the German locale      | Today's     | Today's plus a few sources | Every description          | Depends on the visitor's browser |
| Quality floor                      | Publisher's | Publisher's                | Engine's                   | Browser's                        |
| Search engines see German text     | No          | Where published            | Yes                        | No                               |
| Running cost                       | None        | None                       | Rounding error             | None                             |
| Needs language detection           | No          | Yes                        | Yes                        | Yes, for a correct `lang`        |
| Needs a per-language storage model | No          | Yes                        | Yes                        | No                               |
| Needs a grant from the source      | No          | No                         | Yes, for 83 of 86          | No                               |

Option 4 has one honest form that costs almost nothing. A description element with the right `lang` attribute lets
the browser's translate feature, a screen reader and a search engine handle it correctly. Today the page
claims the locale's language for every description. That is wrong for the 598 English ones on `/de/` and the 1,386
German ones on `/en/`.

## Decision

**Options 2 and 4 now, and option 3 only where a source grants it.** Four rules replace the ADR-013 §3 row for event
text:

1. **Titles are never translated.** A title is mostly a name or a fact, and translating it changes the search term a
   visitor types. This part of the ADR-013 row stands.
2. **Event text is stored per language. The publisher's own text is served in the visitor's locale wherever the
   publisher wrote it.** Where a source publishes both languages, the importer stores both. A source may publish them
   in two fields or in one field with a marker. Issue #330 builds this.
3. **A description is machine-translated only when its source's `description_licence` is `PERMITTED` and the recorded
   grant names translation.** The #808 mail asks for translation as a question of its own, separate from display. The
   translation is stored beside the original, tagged with the engine and its version. It is regenerable from the
   original at any time. The page labels it in both locales and links to the source. Issue #470 builds this, and
   stays blocked until a grant exists.
4. **Everything else is served in the language it was written in, and the page says which.** The importer detects the
   language of each description and stores it with a confidence. It answers `unknown` for text too short or too mixed
   to call. That value goes into `lang` on the description element and into `inLanguage` in the structured data. An
   `unknown` description gets no `lang` and keeps the page's `inLanguage`.

**The reason that settled it** is the legal reading. Option 3 for `UNCLEAR` sources stacks a § 23 act on a § 16 act
and a § 19a act. The project already cannot justify those two per source. The corpus numbers took cost off the table
and put the per-event nature of the language on it. Neither of those changed the answer. The grant did.

**The engine is constrained here and chosen in #473.** Whatever #470 uses must satisfy five constraints:

1. It runs at import time and never on the request path.
2. It writes a per-language row.
3. It stores the engine and its version with the row.
4. It protects proper nouns.
5. It can be told what not to translate.

The cost table above recommends an LLM through Spring AI. It is the cheapest engine at this volume, and the only one
that takes the proper-noun rule in prose. Issue #473 owns model, hosting and cost for every AI-assisted step, and this
ADR does not pre-empt it.

**The disclosure wording is fixed here**, so that it is not invented at implementation time:

- German: _Maschinell übersetzt. Den Originaltext veröffentlicht der Veranstalter._
- English: _Machine-translated. The venue published the original text._

Both link to the event's source page.

## Consequences

**Accepted costs:**

- **A storage model change.** Event text moves from one column per field to one row per language, or equivalent.
  Every reader of `description` in the BFF, the frontend and the structured data follows. Issue #330 carries the migration.
- **A language-detection dependency in the importer**, run on every description at import time. Lingua is the
  candidate in #470. `unknown` must be a first-class answer, and the 46 short and 14 undecidable events in the
  measurement are what it is for.
- **The `PERMITTED` gate makes translation rare for a long time.** Zero sources hold that verdict today, and #808
  sends its first batch to ten venues. The German locale gains German text at the pace venues answer mail. That is
  the intended pace.
- **A second question in the #808 mail.** Asking for translation separately from display makes the mail longer and
  gives a venue a second thing to refuse. The alternative is a grant whose scope is unclear, which is the state this
  ADR exists to avoid.
- **Rule 4 changes what search engines are told.** `inLanguage` becomes truthful per event, and the Rich Results Test
  on #290 has to be repeated after #330 lands.

**Deliberately deferred:**

- **Venue, artist and promoter descriptions.** Same mechanism, other tables, after event text works.
- **The engine and the model.** #473.
- **Splitting a one-field bilingual description at its marker.** #330's audit records which sources do this and how.
  Klunkerkranich's `[EN]` is the known case.
- **A German locale that hides English text**, or the reverse. A visitor who cannot read the description still wants
  the date, the venue and the link. The text stays, with its `lang`.

## When to revisit

- **A source grants translation.** #470 unblocks for that source, and the engine choice in #473 becomes due.
- **A qualified legal opinion on § 23 arrives**, through #279 or a venue's objection. If it reads the act narrower
  than this ADR does, rule 3 loosens. If it reads the display itself as unjustified, this ADR is the least of the
  problems.
- **The corpus changes shape.** If a majority of sources start publishing both languages, rule 2 alone covers the
  locale and rule 3 stops mattering.

## References

- [#469](https://github.com/enorm-labs/event-junkie/issues/469) — the decision issue, with the options and the
  questions this answers
- [#468](https://github.com/enorm-labs/event-junkie/issues/468) — the epic. [#330](https://github.com/enorm-labs/event-junkie/issues/330)
  imports both languages, [#470](https://github.com/enorm-labs/event-junkie/issues/470) translates under a grant
- [#808](https://github.com/enorm-labs/event-junkie/issues/808) — the venue mail that produces a grant.
  [#283](https://github.com/enorm-labs/event-junkie/issues/283) added the licence columns it writes to
- [#473](https://github.com/enorm-labs/event-junkie/issues/473) — engine, hosting and cost for every AI-assisted step
- [#323](https://github.com/enorm-labs/event-junkie/issues/323) — where the proper-noun glossary lives
- [ADR-013](ADR-013_LOCALISATION.md) §3 — the row this partially supersedes
- [SCRAPING_POSITION.md](../SCRAPING_POSITION.md) §3.1 and §6 — the posture this builds on
- [licence-review/README.md](../licence-review/README.md) — the 83 `UNCLEAR` verdicts and the boilerplate that decides
  them
- [UrhG § 3](https://www.gesetze-im-internet.de/urhg/__3.html) · [§ 23](https://www.gesetze-im-internet.de/urhg/__23.html)
  · [§ 44b](https://www.gesetze-im-internet.de/urhg/__44b.html) · [§ 51](https://www.gesetze-im-internet.de/urhg/__51.html)
