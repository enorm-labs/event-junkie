package de.norm.events.account

import de.norm.events.common.PageResponse
import de.norm.events.event.EventFilter
import de.norm.events.event.EventService
import de.norm.events.event.EventSummaryResponse
import de.norm.events.event.FollowedIds
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

/**
 * "Interested" and "going" (#3892), private to the account, and "My calendar" built from them. Every route needs a session.
 * The events also join Following's feed, through [FollowedIds.eventIds].
 */
@RestController
@RequestMapping("/api/me/rsvps")
@Tag(name = "Account", description = "The signed-in visitor's account")
class RsvpController(
    private val rsvps: RsvpStore,
    private val events: EventService,
    private val metrics: AccountMetrics,
    private val clock: Clock
) {
    @GetMapping
    @Operation(summary = "The account's RSVPs as event slug to status, for the cards and the event page")
    suspend fun statuses(
        @AuthenticationPrincipal account: Account
    ): Map<String, String> = rsvps.list(account.id).associate { it.slug to it.status.tag }

    @PutMapping("/{slug}")
    @Operation(summary = "Sets or changes the RSVP for an event: interested or going")
    suspend fun set(
        @AuthenticationPrincipal account: Account,
        @PathVariable slug: String,
        @RequestBody request: RsvpRequest
    ): ResponseEntity<Unit> {
        val status = RsvpStatus.ofOrNull(request.status) ?: refuse(HttpStatus.BAD_REQUEST, "Status is interested or going")
        rsvps.set(account.id, eventId(slug), status, clock.instant())
        record(RsvpAction.SET)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{slug}")
    @Operation(summary = "Withdraws the RSVP. Withdrawing one that does not exist changes nothing")
    suspend fun withdraw(
        @AuthenticationPrincipal account: Account,
        @PathVariable slug: String
    ): ResponseEntity<Unit> {
        rsvps.withdraw(account.id, eventId(slug))
        record(RsvpAction.WITHDRAW)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/upcoming")
    @Operation(summary = "RSVPed events that are not over, by date")
    suspend fun upcoming(
        @AuthenticationPrincipal account: Account,
        @ParameterObject
        @PageableDefault(size = 20, sort = ["eventDate"])
        pageable: Pageable
    ): PageResponse<EventSummaryResponse> = events.search(filterFor(account), pageable)

    @GetMapping("/past")
    @Operation(summary = "RSVPed events that are over, latest first: the history")
    suspend fun past(
        @AuthenticationPrincipal account: Account,
        @RequestParam(defaultValue = "0") page: Int
    ): PageResponse<EventSummaryResponse> =
        events.search(filterFor(account).copy(over = true), PageRequest.of(page.coerceAtLeast(0), PAGE_SIZE, Sort.by(Sort.Direction.DESC, "eventDate")))

    @GetMapping("/calendar")
    @Operation(summary = "RSVPed events within an inclusive date range, for the calendar view. At most 92 days")
    suspend fun calendar(
        @AuthenticationPrincipal account: Account,
        @Parameter(description = "Range start date (inclusive), ISO-8601.", required = true)
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        from: LocalDate,
        @Parameter(description = "Range end date (inclusive), ISO-8601.", required = true)
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        to: LocalDate
    ): List<EventSummaryResponse> = events.calendar(from, to, filterFor(account))

    private suspend fun filterFor(account: Account) = EventFilter(followed = FollowedIds(emptyList(), emptyList(), emptyList(), rsvps.eventIds(account.id)))

    private suspend fun eventId(slug: String): Long = rsvps.eventId(slug) ?: refuse(HttpStatus.NOT_FOUND, "No such event")

    private fun record(action: RsvpAction) {
        metrics.record(action)
        logger.info { "RSVP ${action.tag}" }
    }

    private companion object {
        const val PAGE_SIZE = 20

        fun refuse(
            status: HttpStatus,
            reason: String
        ): Nothing = throw ResponseStatusException(status, reason)
    }
}

data class RsvpRequest(
    /** `interested` or `going`. */
    val status: String
)

/** One RSVP in the export (#3892). */
data class RsvpResponse(
    val event: String,
    val title: String,
    val status: String,
    val updatedAt: Instant
) {
    companion object {
        fun of(entry: RsvpEntry) = RsvpResponse(entry.slug, entry.title, entry.status.tag, entry.updatedAt)
    }
}
