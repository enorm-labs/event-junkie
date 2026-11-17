package de.norm.events.artist

import io.swagger.v3.oas.annotations.media.Schema

/** An `EXACT` artist and the Wikidata genre items stored for it (ADR-060): a row of [ArtistGenreRepository.findWithGenres]. */
data class ArtistWikidataGenresResponse(
    @Schema(description = "Database ID of the artist", example = "7")
    val id: Long,
    @Schema(description = "The artist's slug", example = "roland-kaiser")
    val slug: String,
    @Schema(description = "The artist's name", example = "Roland Kaiser")
    val name: String,
    @Schema(description = "Wikidata P136 item ids, in statement order", example = "[\"Q959583\", \"Q9759\"]")
    val wikidataGenres: List<String>
)
