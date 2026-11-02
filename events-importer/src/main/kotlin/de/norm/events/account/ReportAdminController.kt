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

@Schema(description = "One event with its open problem reports")
data class ReportedEventResponse(
    val eventId: Long,
    val slug: String,
    val title: String,
    val eventDate: LocalDate,
    val venue: String?,
    @Schema(description = "The source that imported the event, or null for a hand-entered one.")
    val sourceSlug: String?,
    val reports: List<ReportEntryResponse>
)

@Schema(description = "One account's report on the event. The reporter is not named.")
data class ReportEntryResponse(
    @Schema(description = "The ticked problems: ARTISTS, TITLE, DATE_TIME, STATUS, PRICE_TICKETS, TYPE_GENRE, NOT_AN_EVENT, DUPLICATE, OTHER.")
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
    @Schema(description = "True shows \"Report a problem\" on the event page; false hides it. Open reports stay.", example = "true")
    val enabled: Boolean
)

/**
 * The problem reports visitors file on event pages (#4399), and the allowlist of accounts that may file them.
 * `/fix-events` and the admin app read the list; resolving an event's reports deletes them (the privacy notice's
 * retention), so a resolve comes after the fix is live or the verdict is final.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin: Reports", description = "Problem reports from event pages, and who may file them")
class ReportAdminController(
    private val store: ReportAdminStore
) {
    @GetMapping("/reports")
    @Operation(summary = "Open problem reports, one entry per event, by event date")
    suspend fun list(): List<ReportedEventResponse> =
        store.list().groupBy { it.eventId }.values.map { rows ->
            val event = rows.first()
            ReportedEventResponse(
                eventId = event.eventId,
                slug = event.slug,
                title = event.title,
                eventDate = event.eventDate,
                venue = event.venue,
                sourceSlug = event.sourceSlug,
                reports = rows.map { ReportEntryResponse(it.problems, it.note, it.updatedAt) }
            )
        }

    @DeleteMapping("/reports")
    @Operation(summary = "Resolves every report on an event: deletes them")
    suspend fun resolve(
        @Parameter(description = "The event whose reports to resolve: `eventId` from the list.", required = true)
        @RequestParam
        eventId: Long
    ): ResolvedResponse {
        val resolved = store.resolve(eventId)
        logger.info { "Resolved $resolved problem reports on event $eventId" }
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
