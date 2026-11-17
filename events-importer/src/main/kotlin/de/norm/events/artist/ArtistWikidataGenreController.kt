package de.norm.events.artist

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.flow.toList
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The stored Wikidata genres of every `EXACT` artist, read-only, for `scripts/genre-cleanup.py wikidata` to list the items
 * `WIKIDATA_GENRES` lacks (#5252). The column is not on [ArtistEntity], so the artist routes do not show it.
 */
@RestController
@RequestMapping("/api/admin/artists")
@Tag(name = "Admin: Artists", description = "Admin CRUD endpoints for managing artists")
class ArtistWikidataGenreController(
    private val genres: ArtistGenreRepository
) {
    @GetMapping("/wikidata-genres")
    @Operation(
        summary = "Every EXACT artist that has Wikidata genres, with the P136 item ids, by id",
        description = "Not paged: one row per artist that holds a genre, a few thousand at most."
    )
    suspend fun wikidataGenres(): List<ArtistWikidataGenresResponse> = genres.findWithGenres().toList()
}
