package de.norm.events.account

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

@Schema(description = "One event, act, promoter or venue with its open problem reports")
data class ReportedSubjectResponse(
    @Schema(description = "The subject's kind, as its page's path names it", allowableValues = ["events", "artists", "promoters", "venues"])
    val kind: String,
    @Schema(description = "The subject's id: `id` for a resolve")
    val id: Long,
    val slug: String,
    @Schema(description = "The event's title, or the act's, promoter's or venue's name")
    val name: String,
    @Schema(description = "The event's date; null on the other kinds.")
    val eventDate: LocalDate?,
    @Schema(description = "The event's venue; null on the other kinds.")
    val venue: String?,
    @Schema(description = "The source that imported the event, or null for a hand-entered one and on the other kinds.")
    val sourceSlug: String?,
    val reports: List<ReportEntryResponse>
)

@Schema(description = "One account's report on the subject. The reporter is not named.")
data class ReportEntryResponse(
    @Schema(
        description =
            "The ticked problems. An event: ARTISTS, PROMOTER, GENRE, EVENT_TYPE, FEATURES, DATE_TIME, TEXT, STATUS, " +
                "PRICE_TICKETS, NOT_AN_EVENT and the hint ON_IMAGE; older reports can hold TITLE, DESCRIPTION, " +
                "TYPE_GENRE, DUPLICATE and QUESTION. An act: NAME, WRONG_ACT, GENRE, LINKS, TEXT, IMAGE. A promoter: " +
                "NAME, LINKS, TEXT, NOT_A_PROMOTER. A venue: NAME, ADDRESS, LINKS, TEXT, CLOSED. OTHER is " +
                "a report with a note and no box."
    )
    val problems: List<String>,
    val note: String?,
    val updatedAt: Instant
)

@Schema(description = "How many reports a resolve deleted")
data class ResolvedResponse(
    val resolved: Long
)

data class CanReportRequest(
    @Schema(description = "The account's address, as it signs in.", example = "operator@example.de")
    val email: String,
    @Schema(description = "True shows \"Report a problem\" on the detail pages; false hides it. Open reports stay.", example = "true")
    val enabled: Boolean
)

/**
 * The problem reports visitors file on event, act, promoter and venue pages (#4399, #5603), and the allowlist of
 * accounts that may file them.
 * `/fix-events` and the admin app read the list; resolving an event's reports deletes them (the privacy notice's
 * retention), so a resolve comes after the fix is live or the verdict is final.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin: Reports", description = "Problem reports from the detail pages, and who may file them")
class ReportAdminController(
    private val store: ReportAdminStore
) {
    @GetMapping("/reports")
    @Operation(summary = "Open problem reports, one entry per subject: events by date, then acts, promoters and venues by name")
    suspend fun list(): List<ReportedSubjectResponse> =
        store.list().groupBy { it.kind to it.id }.values.map { rows ->
            val subject = rows.first()
            ReportedSubjectResponse(
                kind = subject.kind.path,
                id = subject.id,
                slug = subject.slug,
                name = subject.name,
                eventDate = subject.eventDate,
                venue = subject.venue,
                sourceSlug = subject.sourceSlug,
                reports = rows.map { ReportEntryResponse(it.problems, it.note, it.updatedAt) }
            )
        }

    @DeleteMapping("/reports")
    @Operation(
        summary = "Resolves every report on a subject: deletes them",
        description = "`kind` and `id` from the list. `eventId` alone is the event case, for steps written before #5603."
    )
    suspend fun resolve(
        @Parameter(description = "events, artists, promoters or venues", example = "venues")
        @RequestParam(required = false)
        kind: String?,
        @Parameter(description = "The subject's `id` from the list")
        @RequestParam(required = false)
        id: Long?,
        @Parameter(description = "An event's id; the same as `kind=events&id=`")
        @RequestParam(required = false)
        eventId: Long?
    ): ResolvedResponse {
        val (subject, subjectId) =
            when {
                eventId != null && kind == null && id == null -> {
                    ReportSubject.EVENT to eventId
                }

                kind != null && id != null && eventId == null -> {
                    (ReportSubject.ofPath(kind) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown kind $kind")) to id
                }

                else -> {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Either kind and id, or eventId")
                }
            }
        val resolved = store.resolve(subject, subjectId)
        logger.info { "Resolved $resolved problem reports on ${subject.tag} $subjectId" }
        return ResolvedResponse(resolved)
    }

    @PutMapping("/accounts/can-report")
    @Operation(summary = "Allows or stops an account filing problem reports")
    suspend fun canReport(
        @RequestBody request: CanReportRequest
    ): ResponseEntity<Unit> {
        val id = store.setCanReport(request.email, request.enabled) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No account with that address")
        // The id, never the address: the log leaves the cluster.
        logger.info { "Account $id ${if (request.enabled) "may" else "may no longer"} report problems" }
        return ResponseEntity.noContent().build()
    }
}
