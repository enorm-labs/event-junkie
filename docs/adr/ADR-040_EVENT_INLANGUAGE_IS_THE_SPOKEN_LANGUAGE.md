# ADR-040: An event's `inLanguage` is the language spoken on stage, where the venue states it

## Status

**Accepted (2026-10-03) — the structured data of an event declares the spoken language as `inLanguage` when the venue states one. When it states none,
`inLanguage` stays the language of the description, as before. The `lang` attribute on the description element does not change.**

**Implemented in [#2527](https://github.com/enorm-labs/event-junkie/pull/2527).** The operator made the decision in that pull request on 2026-10-03.

**Supersedes rule 4 of [ADR-026](ADR-026_MULTILINGUAL_EVENT_TEXT.md) for one property only:** the `inLanguage` of an event. Rule 4 still decides the `lang`
attribute of every description, and the `inLanguage` of a venue, an artist and a promoter. Rules 1 and 2 of ADR-026 stand. ADR-027 replaced rule 3.

## Context

Issue [#2523](https://github.com/enorm-labs/event-junkie/issues/2523) stores the language of a show where the venue says it: "Stand-Up in English", "Sprache:
Deutsch", "OmU". Over the 1,590 comedy, reading, screening and show rows of a local import, 87 carry one.

ADR-026 rule 4 put the language of the description into `inLanguage`. At that time the description language was the only language we knew about an event.
The two values often disagree. A German description of an English stand-up night is common. Cosmic Comedy and Heimathafen both publish such pages.

schema.org defines `inLanguage` as "the language of the content or performance". For a `CreativeWork` that is the text. For an `Event` it is the performance.
A visitor who searches for English comedy wants the second.

## Decision

The spoken language wins where the venue states it. One language is a string, two or more are an array. Where the venue states none, the description
language stays, so no page loses the value it has today.

**The reason that settled it** is what the property means on an event. A search engine that reads `de` on an English show tells a visitor the wrong thing.
The description language still has a home: the `lang` attribute on the element that holds the text.

## Consequences

- An event page can now declare `en` while its description element says `lang="de"`. That is correct, and a reviewer who sees it should not "fix" it.
- The fallback keeps a weaker claim on most events. Where the venue states no spoken language, `inLanguage` is still the language of the blurb.
- A wrong spoken language is now visible to search engines too. The importer reads explicit phrases only, for that reason (#2523).

## References

- [ADR-026](ADR-026_MULTILINGUAL_EVENT_TEXT.md) rule 4, which this narrows
- [#2523](https://github.com/enorm-labs/event-junkie/issues/2523), the spoken language
- [#2527](https://github.com/enorm-labs/event-junkie/pull/2527), the implementation
- schema.org [`inLanguage`](https://schema.org/inLanguage)
