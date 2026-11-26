package de.norm.events.enrichment

import org.springframework.modulith.ApplicationModule

/**
 * The artist lookups against MusicBrainz, Discogs and Wikipedia, and their meters. `importing` schedules them.
 *
 * - `artist` — the rows a lookup reads and fills
 * - `musicbrainz`, `discogs`, `wikimedia` — the clients each lookup calls
 * - `scraper` — `isStatusWord`, so a notice billed as an act is never matched (#5569)
 * - `account` — how many visitors watch an act, counts only, for the AMBIGUOUS review (#5214)
 */
@ApplicationModule(allowedDependencies = ["artist", "musicbrainz", "discogs", "wikimedia", "account", "scraper"])
class EnrichmentModule
