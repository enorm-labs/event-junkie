# ADR-041: AI-assisted data quality uses the hosted Claude model through Spring AI, suggests only, and has two spending limits

## Status

**Accepted (2026-10-04) — every AI-assisted data-quality step calls Anthropic's hosted API through Spring AI, the integration that translation already uses.
The default model is `claude-haiku-4-5`. One check can use Sonnet when its measured false-positive rate on Haiku is too high. The model only suggests: a
person applies every fix. Spending has two limits: an item cap per run in configuration, and a monthly limit of $20 on the Anthropic workspace.**

**Not implemented yet.** [#474](https://github.com/enorm-labs/event-junkie/issues/474) builds the first checks. The workspace limit is an operator setting
in the Anthropic Console, not code.

**Does not supersede anything.** [ADR-026](ADR-026_MULTILINGUAL_EVENT_TEXT.md) chose Claude for translation. [ADR-027](ADR-027_TRANSLATION_FOLLOWS_THE_DISPLAY_RULE.md)
decides which descriptions are translated. This ADR extends the same integration to data quality and does not change either.

## Context

[#322](https://github.com/enorm-labs/event-junkie/issues/322) is Pillar 4 of `docs/DATA_QUALITY_STRATEGY.md`. It asked for this ADR when it was filed.
Its number was planned twice and lost twice. [#473](https://github.com/enorm-labs/event-junkie/issues/473) held the open questions. They were the model,
the hosting and the cost. [#474](https://github.com/enorm-labs/event-junkie/issues/474), [#346](https://github.com/enorm-labs/event-junkie/issues/346) and
#322 wait on the answer.

Most of the answer exists in production already:

- Since 2026-09-08, `AnthropicTranslationEngine` calls `claude-haiku-4-5` through `spring-ai-anthropic` in production. Its KDoc says that #473 owns the integration
  choice for every AI-assisted step.
- `docs/LEGAL.md` §7.3a names Anthropic as an Art. 28 processor, with a DPA and SCCs ([#1233](https://github.com/enorm-labs/event-junkie/issues/1233)).
- Translation bounds its cost per source and run with `app.translation.max-per-run` (default 50).

The constraints that any option had to satisfy:

- **The platform budget.** [ADR-012](ADR-012_CLOUD_PLATFORM.md) budgets the whole staging environment at about €7 a month.
- **One processor per purpose.** AGENTS.md § Privacy requires every processor to be named, with a DPA. A second model vendor is a second processor.
- **Correctness.** The importer exists to store what the venue published. A model output that writes itself is a source of wrong data that no test catches.

## Candidate options

1. **A hosted API through Spring AI.** No capacity to run. Cost per token. The vendor and the DPA exist already.
2. **A local model on our own hardware, through Ollama.** Hetzner Cloud has no GPU instances. A dedicated GEX44 costs €184 a month plus a €79 setup fee.
   That is more than twenty times the staging budget. The workload runs in short bursts and idles most of the day. CPU inference on the existing node
   would compete with two JVMs and Postgres.
3. **Both.** A small local model for high-volume mechanical checks and a hosted one for judgement. Two integrations, and the volume does not justify it.

## Decision

**Option 1.** The hosted model through Spring AI is already in production and already covered by the privacy notice. A local model costs more than the
platform it would serve.

The rules:

1. **One integration.** Every AI step uses Spring AI with the Anthropic model module, configured inside its own conditional bean, as translation does. No
   step adds a second client or a second vendor.
2. **Haiku by default, Sonnet per check.** Each check has its own model property. A check moves to Sonnet only when its false-positive rate on Haiku is
   too high. The measurement goes into the issue of that check.
3. **Suggest only.** A check produces a finding with its evidence. It never writes to an event, an artist or a vocabulary. A person applies the fix, through
   the admin API or a pull request ([ADR-042](ADR-042_CURATED_VOCABULARIES_STAY_IN_CODE.md)).
4. **Deterministic rules first.** The model sees only what the normalizers and the field-coverage signal could not resolve. That is #322's ordering.
5. **Two spending limits.** Each check has an item cap per run in configuration, like `max-per-run`. The Anthropic workspace has a monthly limit of $20 for
   translation and data quality together. The first limit keeps a run small. The second stops a bug in the first.

**The reason that settled it** is that the integration, the vendor and the legal paperwork exist and work. Each other option adds a cost that buys nothing
for this workload.

## Consequences

- At $20 a month the workspace stops every AI call until the month ends. Translation stops too. An untranslated event stays a candidate, so the next month
  catches up, but a visitor sees fewer German descriptions until then.
- An LLM judge tends to agree with what it is shown. Each check needs a hand-labelled sample and a measured false-positive rate before its findings reach
  anyone. #474 lists this in its done-when items.
- Event titles and descriptions leave the platform for each check. They are public text from public pages, and LEGAL.md §7.3a already covers them. A check
  that sends anything else is a privacy change and needs its own entry.
- Re-fetching a source page for a check is scraping. [ADR-007](ADR-007_WEB_SCRAPING_STRATEGY.md) applies: the existing fetch layer, its rate limits and its
  User-Agent.

## When to revisit

- The monthly bill reaches the limit two months in a row. Then measure which step uses the budget before raising it.
- A self-hosted model becomes cheaper than about $20 a month on hardware we run anyway.

## References

- [#473](https://github.com/enorm-labs/event-junkie/issues/473), the decision issue
- [#322](https://github.com/enorm-labs/event-junkie/issues/322), [#474](https://github.com/enorm-labs/event-junkie/issues/474) and
  [#346](https://github.com/enorm-labs/event-junkie/issues/346), which build on it
- [ADR-026](ADR-026_MULTILINGUAL_EVENT_TEXT.md), the translation engine
- `docs/DATA_QUALITY_STRATEGY.md` §5, Pillar 4
- `docs/LEGAL.md` §7.3a, the processor entry
