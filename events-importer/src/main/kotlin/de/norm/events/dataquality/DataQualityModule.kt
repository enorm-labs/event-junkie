package de.norm.events.dataquality

import org.springframework.modulith.ApplicationModule

/**
 * Pillar 1 of [docs/DATA_QUALITY_STRATEGY.md] — **Measure** — and the store for Pillar 4's proposals.
 *
 * **This module only observes.** It writes no event, changes no normalizer and touches no scraper.
 * That restraint is the ordering the strategy argues for rather than a scoping convenience: fixing
 * data quality starts from a number instead of an impression, and without a baseline every later
 * pillar is judged on whether it *feels* like it helped.
 *
 * It reads across three other modules because a quality report is inherently cross-cutting — the
 * counts come from `event`, the source labels and quality flags from `importing`, and the
 * non-artist-name check from `scraper`. That is exactly why it is its own module rather than a
 * package inside one of them.
 *
 * It also holds Pillar 4's suggestion store (`event_suggestion`, #474). A suggestion is a proposal
 * with its evidence, and writing one still writes no event: a person applies it through
 * `PUT /api/admin/events/{id}` (ADR-041 rule 3). `common` supplies the paged envelope.
 */
@ApplicationModule(allowedDependencies = ["event", "importing", "scraper", "common"])
class DataQualityModule
