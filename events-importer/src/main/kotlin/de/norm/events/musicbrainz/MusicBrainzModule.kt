package de.norm.events.musicbrainz

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata for the MusicBrainz lookup: the client, the candidates it returns and the rule
 * that turns them into a verdict (ADR-031).
 *
 * It knows nothing about artist rows, imports or when a lookup runs. `scraper` owns that. It reads
 * `artist` only for [de.norm.events.artist.MusicBrainzMatch], the vocabulary of the verdict, and
 * paces its own requests rather than through [de.norm.events.scraper.PerHostThrottlingFilter],
 * because depending on `scraper` from here while `scraper` calls the client would be a cycle.
 * `common` lends the `User-Agent` it shares with `wikimedia`.
 */
@ApplicationModule(allowedDependencies = ["artist", "common"])
class MusicBrainzModule
