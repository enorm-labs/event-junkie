package de.norm.events.instagram

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Starts an Instagram read by hand, for a smoke test after a deploy (ADR-048). The routes exist with the
 * drafts switched off and answer 409, so a caller can tell "off" from a typo.
 */
@RestController
@RequestMapping("/api/admin/instagram")
@Tag(name = "Admin: Instagram", description = "Event drafts from the Instagram posts of venues with no website (ADR-048)")
class InstagramController(
    private val runner: ObjectProvider<InstagramDraftRunner>
) {
    @Operation(
        summary = "Start a background read of one venue's Instagram posts",
        description =
            "Reads the posts of the last app.instagram.lookback-days through Business Discovery and drafts the events of each new " +
                "post. Answers 202 at once, and the importer log carries the result. 404 for a venue that is not switched on, 409 " +
                "while another run is going or app.instagram.enabled is false."
    )
    @PostMapping("/venues/{slug}/read")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun readVenue(
        @Parameter(description = "The venue slug", example = "rso-berlin")
        @PathVariable slug: String
    ): InstagramRunStartedResponse {
        enabled().startVenue(slug)
        return InstagramRunStartedResponse("Instagram read started for venue '$slug'")
    }

    @Operation(
        summary = "Start the daily Instagram pass now",
        description = "The same pass the schedule runs, over every venue switched on. Answers 202 at once. 409 as above."
    )
    @PostMapping("/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun runPass(): InstagramRunStartedResponse {
        enabled().startPass()
        return InstagramRunStartedResponse("Instagram pass started")
    }

    private fun enabled(): InstagramDraftRunner = runner.ifAvailable ?: throw InstagramDraftsDisabledException()
}

@Schema(description = "A background Instagram run has started")
data class InstagramRunStartedResponse(
    @Schema(description = "Human-readable acknowledgement", example = "Instagram read started for venue 'rso-berlin'")
    val message: String
)
