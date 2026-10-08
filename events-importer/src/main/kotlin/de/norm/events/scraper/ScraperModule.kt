package de.norm.events.scraper

import org.springframework.modulith.ApplicationModule

/**
 * The framework a venue importer is written against, and every venue package. It never reads the database.
 *
 * - `event` — the enums a scraped event carries, and `normalizeMoneyScale`
 * - `genretag` — `normalizeGenre` and `isGenreLabel`, which turn a venue's style text into genres
 * - `slug` — `SlugGenerator.slugify`, which builds a source id from a title when the venue gives none
 * - `licence` — `SourceLicences`, which `ScrapedEvent` reads to drop a field the source may not give
 * - `common` — the name-casing helpers `deshoutWord`, `isShortInitialism` and `foldTypedApostrophes`
 */
@ApplicationModule(allowedDependencies = ["event", "genretag", "slug", "licence", "common"])
class ScraperModule
