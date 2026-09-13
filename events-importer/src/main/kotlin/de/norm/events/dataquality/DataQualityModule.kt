package de.norm.events.dataquality

import org.springframework.modulith.ApplicationModule

/**
 * Pillar 1 of [docs/DATA_QUALITY_STRATEGY.md] — **Measure**, and nothing else.
 *
 * **This module only observes.** It writes no event, changes no normalizer and touches no scraper.
 * That restraint is the ordering the strategy argues for rather than a scoping convenience: fixing
 * data quality starts from a number instead of an impression, and without a baseline every later
 * pillar is judged on whether it *feels* like it helped.
 *
 * It reads across two other modules because a quality report is inherently cross-cutting — the
 * counts come from `event`, and the source labels and the non-artist-name check from `scraper`.
 * That is exactly why it is its own module rather than a package inside one of them: put it in
 * `event` and it needs `scraper`; put it in `scraper` and it needs `event`.
 */
@ApplicationModule(allowedDependencies = ["event", "scraper"])
class DataQualityModule
