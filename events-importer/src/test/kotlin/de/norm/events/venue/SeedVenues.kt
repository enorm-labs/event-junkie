package de.norm.events.venue

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File

/** One file per venue, `<slug>.json`: the venue's request body and its event sources' (#2824). */
internal val SEED_VENUE_DIR = File("../http/importer/seed/venues")

/** The generated "Run all" file; `scripts/dev-seed-parity.sh check` holds it to [SEED_VENUE_DIR]. */
internal val RUN_ALL_FILE = File("../http/importer/dev-seed.http")

internal data class SeedVenue(
    val file: File,
    val venue: JsonNode,
    val sources: List<JsonNode>
) {
    val name: String get() = venue.get("name").asString()
}

/** Every venue file, sorted by name. The venue files are where a venue exists before it exists anywhere else (#876). */
internal fun seedVenues(): List<SeedVenue> {
    val mapper = JsonMapper.builder().build()
    return SEED_VENUE_DIR
        .listFiles { file -> file.extension == "json" }
        .orEmpty()
        .sortedBy { it.name }
        .map { file ->
            val root = mapper.readTree(file)
            SeedVenue(file, root.get("venue"), root.get("sources").toList())
        }
}
