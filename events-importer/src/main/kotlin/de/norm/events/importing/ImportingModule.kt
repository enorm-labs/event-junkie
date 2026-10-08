package de.norm.events.importing

import org.springframework.modulith.ApplicationModule

/**
 * The import pipeline: the source registry and its admin API, the scheduled run, the upsert, the sweeps and the metrics.
 *
 * - `scraper` — the importers it runs and the events they return
 * - `enrichment` — the artist lookups the scheduled tick drives
 * - `event`, `venue`, `artist`, `promoter`, `genretag` — the rows an import writes and resolves
 * - `slug` — the slugs of rows it creates
 * - `licence` — the per-source licence vocabulary the admin API accepts (#283)
 * - `translation` — the description translation pass
 * - `musicbrainz`, `wikimedia` — the name matcher, and the occupations that type a comedy night
 * - `common` — `PageResponse`
 */
@ApplicationModule(
    allowedDependencies = [
        "scraper", "enrichment", "event", "venue", "artist", "promoter", "genretag", "slug", "licence", "translation", "musicbrainz", "wikimedia", "common"
    ]
)
class ImportingModule
