package de.norm.events.enrichment

import de.norm.events.artist.ArtistService
import de.norm.events.musicbrainz.CandidateNameMatch
import de.norm.events.musicbrainz.MusicBrainzCandidate
import de.norm.events.musicbrainz.MusicBrainzClient
import de.norm.events.musicbrainz.MusicBrainzMatcher
import de.norm.events.musicbrainz.MusicBrainzUnavailableException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The MusicBrainz candidates for one artist's name, for a person who decides an AMBIGUOUS verdict (#2946).
 *
 * The importer asks MusicBrainz through [MusicBrainzClient], so the request keeps the client's pace,
 * retries and `User-Agent`, and the browser never calls MusicBrainz. Nothing is stored: the choice
 * goes back through `PUT /api/admin/artists/{id}/musicbrainz-id`. Every request is one search on a
 * queue the sweep shares, so a reply can wait behind a running sweep.
 */
@RestController
@RequestMapping("/api/admin/artists")
@Tag(name = "Admin: Artists", description = "Admin CRUD endpoints for managing artists")
class MusicBrainzCandidateController(
    private val artistService: ArtistService,
    private val client: MusicBrainzClient
) {
    private val logger = KotlinLogging.logger {}

    @GetMapping("/{id}/musicbrainz-candidates")
    @Operation(
        summary = "The MusicBrainz artists the search returns for the artist's name, those whose name is equal first",
        description = "Calls MusicBrainz at its rate limit; 503 when MusicBrainz does not answer."
    )
    suspend fun candidates(
        @Parameter(description = "Database ID of the artist.", example = "1")
        @PathVariable id: Long
    ): List<MusicBrainzCandidateResponse> {
        val name = artistService.findById(id).name
        return client
            .search(name)
            .map { MusicBrainzCandidateResponse.of(it, MusicBrainzMatcher.nameMatch(name, it)) }
            .sortedBy { it.nameMatch.ordinal }
    }

    /** MusicBrainz did not answer, after the client's retries: its outage, not the request's fault. */
    @ExceptionHandler(MusicBrainzUnavailableException::class)
    fun handleUnavailable(ex: MusicBrainzUnavailableException): ProblemDetail {
        logger.warn { "MusicBrainz candidates unavailable: ${ex.message}" }
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "MusicBrainz did not answer. Try again in a minute.")
    }
}

/**
 * One MusicBrainz candidate as the review shows it.
 *
 * A person's `life-span` is a birth date, which the privacy notice promises not to hold, so the
 * dates are passed on for an ensemble only, as [ArtistEnrichment] reads them. Tags are the
 * CC BY-NC-SA half and are never requested (ADR-031).
 */
@Schema(description = "A MusicBrainz artist the search returned for an artist's name")
data class MusicBrainzCandidateResponse(
    @Schema(description = "MusicBrainz artist id (MBID)", example = "06e3bce0-c612-4a5f-b095-9ffed1e4a656")
    val musicbrainzId: String,
    @Schema(description = "The primary name on MusicBrainz", example = "Accept")
    val name: String,
    @Schema(description = "Which of the candidate's names equals the artist's after folding case and accents", example = "NAME")
    val nameMatch: CandidateNameMatch,
    @Schema(description = "`Person`, `Group`, `Orchestra`, `Choir`, `Character` or `Other`; null when MusicBrainz has not typed it", example = "Group")
    val type: String?,
    @Schema(description = "MusicBrainz's note that tells namesakes apart", example = "German heavy metal band")
    val disambiguation: String?,
    @Schema(description = "ISO 3166-1 code of the country the artist is from", example = "DE")
    val country: String?,
    @Schema(description = "The area the artist is most associated with", example = "Solingen")
    val area: String?,
    @Schema(description = "When the ensemble formed, MusicBrainz's partial date; always null for a person", example = "1976")
    val founded: String?,
    @Schema(description = "When the ensemble dissolved; always null for a person", example = "1989")
    val dissolved: String?,
    @Schema(description = "The candidate's page on musicbrainz.org", example = "https://musicbrainz.org/artist/06e3bce0-c612-4a5f-b095-9ffed1e4a656")
    val url: String
) {
    companion object {
        private const val PERSON = "Person"

        fun of(
            candidate: MusicBrainzCandidate,
            nameMatch: CandidateNameMatch
        ): MusicBrainzCandidateResponse {
            val lifeSpan = candidate.lifeSpan.takeIf { candidate.type != null && candidate.type != PERSON }
            return MusicBrainzCandidateResponse(
                musicbrainzId = candidate.id,
                name = candidate.name,
                nameMatch = nameMatch,
                type = candidate.type,
                disambiguation = candidate.disambiguation?.takeIf { it.isNotBlank() },
                country = candidate.country,
                area = candidate.area?.name,
                founded = lifeSpan?.begin?.takeIf { it.isNotBlank() },
                dissolved = lifeSpan?.end?.takeIf { it.isNotBlank() },
                url = "https://musicbrainz.org/artist/${candidate.id}"
            )
        }
    }
}
