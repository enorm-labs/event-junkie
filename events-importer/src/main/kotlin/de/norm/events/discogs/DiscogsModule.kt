package de.norm.events.discogs

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata for the Discogs lookup: the client, the candidates it returns and the rule that
 * turns them into a verdict (#2026, ADR-035).
 *
 * The same boundary as `musicbrainz`: it knows nothing about artist rows, imports or when a lookup
 * runs, which `scraper` owns. It reads `artist` for [de.norm.events.artist.DiscogsMatch], `common`
 * for the `User-Agent`, and `musicbrainz` for the one fold both indexes compare names with.
 */
@ApplicationModule(allowedDependencies = ["artist", "common", "musicbrainz"])
class DiscogsModule
