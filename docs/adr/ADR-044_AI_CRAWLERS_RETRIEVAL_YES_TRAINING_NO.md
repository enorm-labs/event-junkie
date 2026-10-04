# ADR-044: AI crawlers — retrieval yes, training no, and JSON-LD in the server HTML

## Status

**Accepted (2026-10-04) — `robots.txt` allows the AI crawlers that fetch pages for answers and disallows the crawlers that collect text for model
training. The injector writes the JSON-LD of event and venue pages into the served HTML.**

**Implemented in the pull request that closes [#2596](https://github.com/enorm-labs/event-junkie/issues/2596).**

**Supersedes one row of [ADR-014](ADR-014_RENDERING_STRATEGY.md) only**: the `schema.org` JSON-LD row of its table "What is already mitigated". That
row said that no consumer of structured data runs without JavaScript. The rest of ADR-014 stays in force. This ADR does not change what the site displays
or which licences permit the display. [ADR-027](ADR-027_TRANSLATION_FOLLOWS_THE_DISPLAY_RULE.md) and
[`docs/SCRAPING_POSITION.md`](../SCRAPING_POSITION.md) keep those rules.

## Context

AI answer engines (ChatGPT, Claude, Perplexity) cite web pages in their answers. Their crawlers read the HTML that the server sends.

**On 2026-10-04 an event page had no data for these crawlers.** A request with a `GPTBot` user agent got 6.8 KB. The response held the title and the Open
Graph tags from the injector, and an empty `<div id="app">`. It held no JSON-LD and no body text.

ADR-014 accepted this. Its table said that Googlebot runs JavaScript and that no other consumer of structured data exists. AI crawlers are such a consumer.
Reports say that most of them do not run JavaScript. We did not verify this. Google's AI answers use Googlebot, so Google is not affected.

**The vendors split their crawlers by purpose.** Each vendor documents one token for retrieval and a different token for training:

| Vendor       | Retrieval for answers (allowed)           | Training (disallowed) |
| ------------ | ----------------------------------------- | --------------------- |
| OpenAI       | `OAI-SearchBot`, `ChatGPT-User`           | `GPTBot`              |
| Anthropic    | `Claude-SearchBot`, `Claude-User`         | `ClaudeBot`           |
| Perplexity   | `PerplexityBot`, `Perplexity-User`        | none documented       |
| Google       | Googlebot                                 | `Google-Extended`     |
| Apple        | Applebot                                  | `Applebot-Extended`   |
| Meta         | `Meta-WebIndexer`, `Meta-ExternalFetcher` | `Meta-ExternalAgent`  |
| Common Crawl | none                                      | `CCBot`               |

`events-frontend/src/lib/seo.ts` links the documentation of each vendor next to the list of training tokens.

**The site displays text and images that it does not own.** Each source has a licence review (`docs/licence-review/`). The display rule is in
`docs/SCRAPING_POSITION.md` §3.1 and §3.6. No review and no grant covers the training of a model. This is our reading of the licences. It is not legal
advice.

## Candidate options

1. **Allow every crawler.** This is the state before this ADR. Answers can cite us. Training crawlers also copy the venues' texts, which the licences may
   not permit.
2. **Allow retrieval, disallow training.** Answers can cite us and link back. The training crawlers that obey `robots.txt` do not copy the texts.
3. **Disallow every AI crawler.** No licence risk from AI crawlers. Answer engines cannot cite our events, and visitors from those answers do not arrive.

## Decision

**Option 2.** The reason that settled it is the licence scope. A venue that permits the display of its text on Event Junkie did not permit the training
of a model on that text. A retrieval crawler fetches a page to answer one question and links to it. That is closer to the display that the licences
permit.

The rules:

1. **`User-agent: *` stays allowed.** Search engines and retrieval crawlers read the whole site.
2. **Each training token gets its own `Disallow: /` group.** The list is `AI_TRAINING_CRAWLERS` in `lib/seo.ts`. A new token goes in with a link to the
   vendor's documentation.
3. **The injector writes the JSON-LD of event and venue pages into the served HTML.** The client and the injector use the same builders,
   `eventPageJsonLd` and `venuePageJsonLd`. `injector/__tests__/parity.spec.ts` makes sure that both write the same data.
4. **A booted page has exactly one JSON-LD block.** The client takes over the served block. It does not add a second one.
5. **`/llms.txt` describes the site and links its lists, sitemaps and feeds.** It holds no event data.

## Consequences

- A crawler that runs no JavaScript reads the date, venue, address, price and line-up of an event. This applies to all crawlers, not only AI crawlers.
- `robots.txt` is a request, not a control. A training crawler that ignores it still copies the texts. This ADR does not block crawlers at the ingress.
- The list of training tokens becomes stale when a vendor adds a crawler. Someone must check the vendors' documentation from time to time.
- A future change that allows a training token must first get a licence decision. This ADR records why the tokens are disallowed.
- The evidence that crawlers read `/llms.txt` is weak. The file costs little, and we keep it while it costs little.
- Staging serves `/llms.txt` with production links. Its `robots.txt` disallows every crawler, so this has no effect.

## When to revisit

- A source grants permission for training, or a licence review finds a prohibition of retrieval.
- A vendor combines retrieval and training in one token.
- The ingress logs show that AI crawlers ignore `robots.txt`.

## References

- [#2596](https://github.com/enorm-labs/event-junkie/issues/2596): the decision, and the measurement on 2026-10-04
- [ADR-014](ADR-014_RENDERING_STRATEGY.md): rendering strategy and the injector
- [ADR-027](ADR-027_TRANSLATION_FOLLOWS_THE_DISPLAY_RULE.md): the display rule for venue texts
- [`docs/SCRAPING_POSITION.md`](../SCRAPING_POSITION.md) §3.1 and §3.6, and [`docs/licence-review/`](../licence-review/README.md)
- [llmstxt.org](https://llmstxt.org/)
