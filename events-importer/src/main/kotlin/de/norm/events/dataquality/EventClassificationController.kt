package de.norm.events.dataquality

import de.norm.events.dataquality.classification.ClassificationRunResult
import de.norm.events.dataquality.classification.ClassificationRunner
import de.norm.events.dataquality.classification.EventClassificationCheck
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Runs the event-classification check by hand (#3241). It writes suggestions and never an event.
 *
 * The routes exist with the check switched off, and answer 409, so a caller can tell "off" from a typo.
 * A source run goes to [ClassificationRunner], the runner of the nightly pass, and answers at once (#2996).
 */
@RestController
@RequestMapping("/api/admin/data-quality/check")
@Tag(name = "Admin: Data Quality", description = "Per-source data-quality measurement (Pillar 1 — Measure)")
class EventClassificationController(
    private val check: ObjectProvider<EventClassificationCheck>,
    private val runner: ObjectProvider<ClassificationRunner>
) {
    @Operation(
        summary = "Classify one event and propose fixes",
        description =
            "MusicBrainz first, then one model call. Writes an OPEN suggestion per field that differs from the stored value. " +
                "An event whose title, subtitle and description did not change since its last check is skipped unless force=true. " +
                "No call once the month's tokens reach app.data-quality.classification.monthly-token-budget. " +
                "409 while app.data-quality.classification.enabled is false."
    )
    @PostMapping("/event/{id}")
    suspend fun checkEvent(
        @Parameter(description = "Database ID of the event", example = "4711")
        @PathVariable id: Long,
        @Parameter(description = "Send the text again although it did not change")
        @RequestParam(defaultValue = "false") force: Boolean
    ): ClassificationRunResponse = ClassificationRunResponse.from(enabled(check).checkEvent(id, force))

    @Operation(
        summary = "Start a background classification of the upcoming candidates of one source",
        description =
            "Candidates are events typed OTHER, with an unmatched or singleton title-derived artist, missing a start time or a " +
                "price beside a description, or with an uncertain party-feature cue. Soonest first, capped at " +
                "app.data-quality.classification.max-per-run model calls and by the monthly token budget. Answers 202 at once, " +
                "and the importer log carries the result. 409 while another run, the nightly pass included, is going."
    )
    @PostMapping("/source/{slug}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun checkSource(
        @Parameter(description = "The event source slug", example = "badehaus")
        @PathVariable slug: String,
        @Parameter(description = "Send unchanged texts again")
        @RequestParam(defaultValue = "false") force: Boolean
    ): ClassificationStartedResponse {
        enabled(runner).startSource(slug, force)
        return ClassificationStartedResponse("Event classification started for source '$slug'", slug)
    }

    private fun <T : Any> enabled(provider: ObjectProvider<T>): T = provider.ifAvailable ?: throw EventClassificationDisabledException()
}

/** Thrown when a check route is called while the check is switched off. Rendered as a 409. */
class EventClassificationDisabledException :
    RuntimeException("The event-classification check is off: set app.data-quality.classification.enabled and a key")

/** Thrown when a classification run is asked for while one is going. Rendered as a 409. */
class ClassificationRunInProgressException : RuntimeException("An event-classification run is going. Start another one when it has finished")

@Schema(description = "A background run of the event-classification check has started")
data class ClassificationStartedResponse(
    @Schema(description = "Human-readable acknowledgement", example = "Event classification started for source 'badehaus'")
    val message: String,
    @Schema(description = "The source the run reads", example = "badehaus")
    val sourceSlug: String
)

@Schema(description = "What one run of the event-classification check did")
data class ClassificationRunResponse(
    @Schema(description = "Events the run considered", example = "12")
    val candidates: Int,
    @Schema(description = "Events sent to the model", example = "10")
    val checked: Int,
    @Schema(description = "Events skipped because their text did not change since the last check", example = "1")
    val unchanged: Int,
    @Schema(description = "Model calls that returned no answer. Those events stay candidates", example = "0")
    val failed: Int,
    @Schema(description = "Candidates left for the next run by max-per-run", example = "1")
    val overCap: Int,
    @Schema(description = "Candidates left unsent because the month's tokens reached the budget", example = "0")
    val overBudget: Int,
    @Schema(description = "Suggestion rows written, per field", example = """{"startTime": 2, "eventType": 1}""")
    val suggested: Map<String, Int>
) {
    companion object {
        fun from(result: ClassificationRunResult) =
            ClassificationRunResponse(
                result.candidates,
                result.checked,
                result.unchanged,
                result.failed,
                result.overCap,
                result.overBudget,
                result.suggested
            )
    }
}
