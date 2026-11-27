package de.norm.events.account

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
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
import java.time.Clock
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * "Report a problem" on an event, act, promoter or venue page (#4399, #5603), for the accounts the operator allows
 * ([Account.canReport]). A report is stored and nothing more: the operator reads the queue through the importer's
 * admin API and resolves it there. The path names the subject as its page does: `/events/{slug}`, `/venues/{slug}`.
 */
@RestController
@RequestMapping("/api/me/reports")
@Tag(name = "Account", description = "The signed-in visitor's account")
class ReportController(
    private val reports: ReportStore,
    private val metrics: AccountMetrics,
    private val clock: Clock
) {
    @GetMapping
    @Operation(summary = "The account's open problem reports, newest first")
    suspend fun list(
        @AuthenticationPrincipal account: Account
    ): List<ReportResponse> = reports.list(account.id).map(ReportResponse::of)

    @PutMapping("/{kind}/{slug}")
    @Operation(
        summary = "Reports a problem with an event, act, promoter or venue, or replaces the account's earlier report on it",
        description =
            "Only for accounts the operator allows. `kind` is events, artists, promoters or venues. At least one " +
                "problem the kind allows, other than ON_IMAGE, or a note of at most 500 characters. A report with a note " +
                "and no problem is stored as OTHER."
    )
    suspend fun save(
        @AuthenticationPrincipal account: Account,
        @PathVariable kind: String,
        @PathVariable slug: String,
        @RequestBody request: ReportRequest
    ): ResponseEntity<Unit> {
        allowed(account)
        val reportKind = kind(kind)
        val note = request.note?.trim()?.takeIf { it.isNotEmpty() }
        if (note != null && note.length > MAX_NOTE) refuse(HttpStatus.BAD_REQUEST, "A note of at most $MAX_NOTE characters")
        val problems = problems(reportKind, request.problems, note != null)
        val subject = reports.subject(reportKind, slug) ?: refuse(HttpStatus.NOT_FOUND, "No such ${reportKind.tag}")
        val replaced = reports.save(account.id, subject, problems, note, clock.instant())
        record(if (replaced) ReportAction.REPLACE else ReportAction.STORE, reportKind, problems)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{kind}/{slug}")
    @Operation(summary = "Withdraws the account's report on a subject. Withdrawing one that does not exist changes nothing")
    suspend fun withdraw(
        @AuthenticationPrincipal account: Account,
        @PathVariable kind: String,
        @PathVariable slug: String
    ): ResponseEntity<Unit> {
        val reportKind = kind(kind)
        val subject = reports.subject(reportKind, slug) ?: refuse(HttpStatus.NOT_FOUND, "No such ${reportKind.tag}")
        if (reports.withdraw(account.id, subject)) record(ReportAction.WITHDRAW, reportKind, emptyList())
        return ResponseEntity.noContent().build()
    }

    private fun allowed(account: Account) {
        if (!account.canReport) refuse(HttpStatus.FORBIDDEN, "This account cannot report problems")
    }

    private fun record(
        action: ReportAction,
        kind: ReportKind,
        problems: List<ReportProblem>
    ) {
        metrics.record(action, kind)
        if (action != ReportAction.WITHDRAW) metrics.record(problems)
        // The problem names come from a closed list, so they are safe in the line; the note never is.
        logger.info { "Problem report (${kind.tag}) ${action.tag}" + if (problems.isEmpty()) "" else ": ${problems.joinToString()}" }
    }

    private companion object {
        const val MAX_NOTE = 500

        fun kind(path: String): ReportKind = ReportKind.ofPath(path) ?: refuse(HttpStatus.NOT_FOUND, "Nothing to report at $path")

        /**
         * Any name the kind allows passes, an event's old ones too, so an edited report keeps what it held. The stored
         * row needs a problem, so a note with no box is stored as [ReportProblem.OTHER].
         */
        fun problems(
            kind: ReportKind,
            raw: List<String>,
            hasNote: Boolean
        ): List<ReportProblem> {
            val problems =
                raw
                    .map { name ->
                        ReportProblem.ofOrNull(name)?.takeIf { it in kind.allowed }
                            ?: refuse(HttpStatus.BAD_REQUEST, "No problem $name on a ${kind.tag}")
                    }.distinct()
            if (!hasNote && problems.all { it == ReportProblem.ON_IMAGE }) refuse(HttpStatus.BAD_REQUEST, "At least one problem or a note")
            return problems.ifEmpty { listOf(ReportProblem.OTHER) }
        }
    }
}

data class ReportRequest(
    /** [ReportProblem] names the subject's [ReportKind] allows. At least one other than `ON_IMAGE`, unless there is a note. */
    val problems: List<String>,
    /** Free text, at most 500 characters. Never required, but a report with no problem needs one. */
    val note: String? = null
)

/** One open report, on the account's own list and in the export (#4399, #5603). */
data class ReportResponse(
    @Schema(description = "The subject's kind, as its page's path names it", allowableValues = ["events", "artists", "promoters", "venues"])
    val kind: String,
    val slug: String,
    @Schema(description = "The event's title, or the act's, promoter's or venue's name")
    val title: String,
    val problems: List<String>,
    val note: String?,
    val updatedAt: Instant
) {
    companion object {
        fun of(entry: ReportEntry) = ReportResponse(entry.kind.path, entry.slug, entry.title, entry.problems.map { it.name }, entry.note, entry.updatedAt)
    }
}
