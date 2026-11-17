package de.norm.events.enrichment

import de.norm.events.account.WatchCounts
import de.norm.events.artist.AmbiguousArtistStore
import de.norm.events.musicbrainz.MusicBrainzMatcher
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The AMBIGUOUS artists visitors wait for (#5214). A watch becomes a follow only once its row has the verdict EXACT, so a
 * person decides these first. A row counts as watched when its name equals a watched act's name, after
 * [MusicBrainzMatcher.fold]: the review's candidates are a live search per row, so a watched MBID among them cannot be
 * found without one search per row. Counts only, never which account watches.
 */
@RestController
@RequestMapping("/api/admin/artists")
@Tag(name = "Admin: Artists", description = "Admin CRUD endpoints for managing artists")
class WatchedArtistController(
    private val ambiguousArtists: AmbiguousArtistStore,
    private val watchCounts: WatchCounts
) {
    private val logger = KotlinLogging.logger {}

    @GetMapping("/watched")
    @Operation(summary = "The AMBIGUOUS artists whose name visitors watch, the most watched first, whatever their dates")
    suspend fun watched(): List<WatchedArtistResponse> {
        val watchers = watchCounts.byName().groupBy({ MusicBrainzMatcher.fold(it.name) }, { it.watchers }).mapValues { it.value.sum() }
        if (watchers.isEmpty()) return emptyList()
        val rows =
            ambiguousArtists.all().mapNotNull { artist ->
                watchers[MusicBrainzMatcher.fold(artist.name)]?.let { WatchedArtistResponse(artist.id, artist.name, artist.slug, it) }
            }
        logger.info { "Watched AMBIGUOUS artists: ${rows.size} of ${watchers.size} watched names" }
        return rows.sortedWith(compareBy({ -it.watchers }, { it.name }))
    }
}

/** An AMBIGUOUS artist visitors watch, in the shape the review's rows read. */
@Schema(description = "An artist with an AMBIGUOUS MusicBrainz verdict whose name visitors watch")
data class WatchedArtistResponse(
    @Schema(description = "Database ID of the artist", example = "42")
    val id: Long,
    @Schema(description = "The artist's name", example = "Moderat")
    val name: String,
    @Schema(description = "The artist's slug", example = "moderat")
    val slug: String,
    @Schema(description = "How many visitors watch an act of this name", example = "2")
    val watchers: Int
) {
    @Schema(description = "Always AMBIGUOUS: the verdict that brings a row here", example = "AMBIGUOUS")
    val musicbrainzMatch: String = "AMBIGUOUS"
}
