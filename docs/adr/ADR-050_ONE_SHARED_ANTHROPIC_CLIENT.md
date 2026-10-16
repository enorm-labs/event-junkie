# ADR-050: Every AI step calls Anthropic through one shared client in the `ai` module

## Status

**Accepted (2026-10-16). A new Spring Modulith module `ai` (package `de.norm.events.ai`) holds the Anthropic client of the importer. It builds the Spring
AI options and the model, holds the base URL and the refusal reason, and runs the one call body. Each caller keeps its own properties, prompt, validator and
result type.**

Decided in [#3396](https://github.com/enorm-labs/event-junkie/issues/3396), option 1.

**Amends rule 1 of [ADR-041](ADR-041_AI_ASSISTED_DATA_QUALITY.md).** That rule said that each AI step configures Spring AI "inside its own conditional
bean". Now each step has its own bean over the shared `ai` client. All other rules of ADR-041 stand. [ADR-026](ADR-026_MULTILINGUAL_EVENT_TEXT.md),
[ADR-048](ADR-048_INSTAGRAM_POSTS_THROUGH_BUSINESS_DISCOVERY.md) and [ADR-049](ADR-049_INSTAGRAM_DRAFTS_READ_THE_POST_IMAGES.md) decide what each caller
sends. This ADR does not change that.

## Context

Three classes in `events-importer` called Anthropic, and each one built its own client:

| Class                        | Module        | Decided by       |
| ---------------------------- | ------------- | ---------------- |
| `AnthropicTranslationEngine` | `translation` | ADR-026          |
| `AnthropicEventClassifier`   | `dataquality` | ADR-041          |
| `AnthropicPostExtractor`     | `instagram`   | ADR-048, ADR-049 |

Each class built the same `AnthropicChatOptions` and `AnthropicChatModel` pair. Each declared `DEFAULT_BASE_URL` and `REFUSAL`. Each repeated the same call
body: run on the IO dispatcher, check the finish reason, take the trimmed text. A change to the call shape, such as a model parameter or a retry, was made
three times. A fourth caller would copy the pattern a fourth time.

The constraints:

- **`common` is not a place for it.** `common` is an open module, and its KDoc limits it to shared API plumbing without dependencies. Spring AI is a
  dependency.
- **Each feature stays off by default.** Each caller is a conditional bean with its own switch and its own key. A shared client must not start a model when
  every feature is off.
- **Each caller keeps its own settings.** ADR-041 rule 2 gives each check its own model property. Timeouts and the token limit also differ per caller.

## Candidate options

1. **A new `ai` module** that holds the client, the constants and the call body. The three modules add `ai` to their `allowedDependencies`.
2. **The client in `common`.** It breaks the limit in the KDoc of `common`.
3. **No change.** Three copies of about 40 lines stay.

## Decision

**Option 1.** The `ai` module has one public class, `AnthropicClient`, and two value types, `AnthropicSettings` and `AnthropicReply`.

- `AnthropicClient` is not a Spring bean. Each caller builds one inside its own conditional bean, from its own properties. A disabled feature builds no
  client.
- `call` runs on the IO dispatcher, because Spring AI blocks (ADR-001). It takes the system prompt, the user text and optional media. It returns the trimmed
  text, or null for a refusal or an empty answer. It also returns the prompt and completion token counts, and a flag for a refusal.
- The temperature is 0 for every caller.
- The caller logs a refusal in its own words, and it decides what a failed call means.

**The reason that settled it** is that the call shape now changes in one place, and a fourth caller gets it without a copy.

## Consequences

- `dataquality`, `translation` and `instagram` depend on `ai`. `ai` depends on no other module.
- A change to the client changes all three callers. Their tests run the real Spring AI pipeline against a local server, so a change that breaks one
  caller fails its test.
- Each caller still builds its own `AnthropicChatModel`. Each model registers its own HTTP client meters. [#3316](https://github.com/enorm-labs/event-junkie/issues/3316)
  tracks the duplicate meter, and this ADR does not fix it.
- A caller that needs a different temperature needs a new field in `AnthropicSettings`.

## References

- [#3396](https://github.com/enorm-labs/event-junkie/issues/3396), the decision issue
- [#3316](https://github.com/enorm-labs/event-junkie/issues/3316), the duplicate meter
- [ADR-041](ADR-041_AI_ASSISTED_DATA_QUALITY.md) rule 1, which this ADR amends
- [ADR-006](ADR-006_SPRING_MODULITH.md), the module structure
- `docs/architecture/modules-events-importer.md`, the module diagram
