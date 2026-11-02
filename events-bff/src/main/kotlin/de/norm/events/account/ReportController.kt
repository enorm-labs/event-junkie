package de.norm.events.account

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * "Report a problem" on an event page (#4399), for the accounts the operator allows ([Account.canReport]). A report is
 * stored and nothing more: the operator reads the queue through the importer's admin API and resolves it there.
 */
@RestController
@RequestMapping("/api/me/reports")
@Tag(name = "Account", description = "The signed-in visitor's account")
class ReportController(
    private val reports: ReportStore,
    private val rsvps: RsvpStore,
    private val metrics: AccountMetrics,
    private val clock: Clock
) {
    @GetMapping
    @Operation(summary = "The account's open problem reports, newest first")
    suspend fun list(
        @AuthenticationPrincipal account: Account
    ): List<ReportResponse> = reports.list(account.id).map(ReportResponse::of)

    @PutMapping("/{slug}")
    @Operation(
        summary = "Reports a problem with an event, or replaces the account's earlier report on it",
        description = "Only for accounts the operator allows. At least one problem; a note of at most 500 characters, required with OTHER."
    )
    suspend fun save(
        @AuthenticationPrincipal account: Account,
        @PathVariable slug: String,
        @RequestBody request: ReportRequest
    ): ResponseEntity<Unit> {
        allowed(account)
        val problems = problems(request.problems)
        val note = request.note?.trim()?.takeIf { it.isNotEmpty() }
        if (note != null && note.length > MAX_NOTE) refuse(HttpStatus.BAD_REQUEST, "A note of at most $MAX_NOTE characters")
        if (ReportProblem.OTHER in problems && note == null) refuse(HttpStatus.BAD_REQUEST, "OTHER needs a note")
        val eventId = rsvps.eventId(slug) ?: refuse(HttpStatus.NOT_FOUND, "No such event")
        if (!reports.exists(account.id, eventId) && reports.count(account.id) >= MAX_OPEN) {
            record(ReportAction.REFUSED, problems)
            refuse(HttpStatus.TOO_MANY_REQUESTS, "At most $MAX_OPEN open reports")
        }
        val replaced = reports.save(account.id, eventId, problems, note, clock.instant())
        record(if (replaced) ReportAction.REPLACE else ReportAction.STORE, problems)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{slug}")
    @Operation(summary = "Withdraws the account's report on an event. Withdrawing one that does not exist changes nothing")
    suspend fun withdraw(
        @AuthenticationPrincipal account: Account,
        @PathVariable slug: String
    ): ResponseEntity<Unit> {
        val eventId = rsvps.eventId(slug) ?: refuse(HttpStatus.NOT_FOUND, "No such event")
        if (reports.withdraw(account.id, eventId)) record(ReportAction.WITHDRAW, emptyList())
        return ResponseEntity.noContent().build()
    }

    private fun allowed(account: Account) {
        if (!account.canReport) refuse(HttpStatus.FORBIDDEN, "This account cannot report problems")
    }

    private fun record(
        action: ReportAction,
        problems: List<ReportProblem>
    ) {
        metrics.record(action)
        // The problem names come from a closed list, so they are safe in the line; the note never is.
        logger.info { "Problem report ${action.tag}" + if (problems.isEmpty()) "" else ": ${problems.joinToString()}" }
    }

    private companion object {
        const val MAX_NOTE = 500
        const val MAX_OPEN = 30L

        fun problems(raw: List<String>): List<ReportProblem> {
            val problems = raw.map { ReportProblem.ofOrNull(it) ?: refuse(HttpStatus.BAD_REQUEST, "Unknown problem $it") }.distinct()
            return problems.ifEmpty { refuse(HttpStatus.BAD_REQUEST, "At least one problem") }
        }

        fun refuse(
            status: HttpStatus,
            reason: String
        ): Nothing = throw ResponseStatusException(status, reason)
    }
}

data class ReportRequest(
    /** [ReportProblem] names, at least one. */
    val problems: List<String>,
    /** Free text, at most 500 characters. Required with `OTHER`. */
    val note: String? = null
)

/** One open report, on the account's own list and in the export (#4399). */
data class ReportResponse(
    val event: String,
    val title: String,
    val problems: List<String>,
    val note: String?,
    val updatedAt: Instant
) {
    companion object {
        fun of(entry: ReportEntry) = ReportResponse(entry.slug, entry.title, entry.problems.map { it.name }, entry.note, entry.updatedAt)
    }
}
