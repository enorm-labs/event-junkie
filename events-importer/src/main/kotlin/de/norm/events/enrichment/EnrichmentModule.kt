package de.norm.events.enrichment

import org.springframework.modulith.ApplicationModule

/**
 * The artist lookups against MusicBrainz, Discogs and Wikipedia, and their meters. `importing` schedules them.
 *
 * - `artist` — the rows a lookup reads and fills
 * - `musicbrainz`, `discogs`, `wikimedia` — the clients each lookup calls
 */
@ApplicationModule(allowedDependencies = ["artist", "musicbrainz", "discogs", "wikimedia"])
class EnrichmentModule
