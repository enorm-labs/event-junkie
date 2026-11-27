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
import org.springframework.web.bind.annotation.PostMapping
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

@Schema(description = "How many reports a resolve deleted, and how many notes it left under the reporters' bells")
data class ResolvedResponse(
    val resolved: Long,
    val notified: Long
)

data class ResolveRequest(
    @Schema(description = "The subject's kind from the list", allowableValues = ["events", "artists", "promoters", "venues"], example = "venues")
    val kind: String,
    @Schema(description = "The subject's `id` from the list")
    val id: Long,
    @Schema(description = "What the operator did: `fixed`, or `unchanged` when the page stays as it is", allowableValues = ["fixed", "unchanged"])
    val outcome: String = ReportOutcome.FIXED.tag,
    @Schema(description = "An optional answer to the reporters, at most $MAX_REPLY characters. Every reporter on the subject reads it.")
    val reply: String? = null
)

/** The longest reply, as V290's CHECK holds it. */
const val MAX_REPLY = 500

data class CanReportRequest(
    @Schema(description = "The account's address, as it signs in.", example = "operator@example.de")
    val email: String,
    @Schema(description = "False withdraws \"Report a problem\" from the account; true gives it back, the default. Open reports stay.", example = "true")
    val enabled: Boolean
)

data class ReportCapRequest(
    @Schema(description = "The account's address, as it signs in.", example = "operator@example.de")
    val email: String,
    @Schema(description = "True lifts the cap of 200 open reports for the account; false restores it.", example = "true")
    val exempt: Boolean
)

/**
 * The problem reports visitors file on event, act, promoter and venue pages (#4399, #5603), and the flag that
 * withdraws reporting from an account that abuses the form (#5604).
 * `/fix-events` and the admin app read the list. Resolving a subject's reports deletes them (the privacy notice's
 * retention) and leaves each reporter a note under the bell (#5308), so a resolve comes after the fix is live or the
 * verdict is final.
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

    @PostMapping("/reports/resolve")
    @Operation(
        summary = "Resolves every report on a subject: deletes them, and leaves each reporter a note under the bell",
        description = "The note names the page and the outcome, and carries the reply. The reply goes in the body, never the URL."
    )
    suspend fun resolve(
        @RequestBody request: ResolveRequest
    ): ResolvedResponse {
        val subject = ReportSubject.ofPath(request.kind) ?: badRequest("Unknown kind ${request.kind}")
        val outcome = ReportOutcome.ofTag(request.outcome) ?: badRequest("Unknown outcome ${request.outcome}")
        val reply = request.reply?.trim()?.ifEmpty { null }
        if (reply != null && reply.length > MAX_REPLY) badRequest("The reply is longer than $MAX_REPLY characters")
        return resolve(subject, request.id, outcome, reply)
    }

    @DeleteMapping("/reports")
    @Operation(
        summary = "Resolves every report on a subject as fixed, with no reply",
        description =
            "For steps written before #5308; `POST /reports/resolve` takes an outcome and a reply. `kind` and `id` from the list. " +
                "`eventId` alone is the event case, for steps written before #5603."
    )
    suspend fun resolveFixed(
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
        return resolve(subject, subjectId, ReportOutcome.FIXED, null)
    }

    private fun badRequest(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)

    private suspend fun resolve(
        subject: ReportSubject,
        id: Long,
        outcome: ReportOutcome,
        reply: String?
    ): ResolvedResponse {
        val result = store.resolve(subject, id, outcome, reply)
        // Whether there is a reply, never its text: the log leaves the cluster.
        logger.info {
            "Resolved ${result.resolved} problem reports on ${subject.tag} $id as ${outcome.tag}, " +
                "${result.notified} notified, ${if (reply == null) "no reply" else "with a reply"}"
        }
        return ResolvedResponse(result.resolved, result.notified)
    }

    @PutMapping("/accounts/can-report")
    @Operation(summary = "Withdraws reporting from an account, or gives it back")
    suspend fun canReport(
        @RequestBody request: CanReportRequest
    ): ResponseEntity<Unit> {
        val id = store.setCanReport(request.email, request.enabled) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No account with that address")
        // The id, never the address: the log leaves the cluster.
        logger.info { "Account $id ${if (request.enabled) "may" else "may no longer"} report problems" }
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/accounts/report-cap-exempt")
    @Operation(summary = "Lifts the cap on open problem reports for an account, or restores it")
    suspend fun reportCapExempt(
        @RequestBody request: ReportCapRequest
    ): ResponseEntity<Unit> {
        val id =
            store.setReportCapExempt(request.email, request.exempt)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No account with that address")
        // The id, never the address: the log leaves the cluster.
        logger.info { "Account $id ${if (request.exempt) "is exempt from" else "is held to"} the cap on open reports" }
        return ResponseEntity.noContent().build()
    }
}
