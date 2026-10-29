package de.norm.events.account

import de.norm.events.common.QueryParameters
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * The header's bell (#4078) and the notifications page (#4169): what is new from what the account follows, unread until
 * opened. Every route needs a session. The header asks for the count on each page load, so the count is one indexed query.
 */
@RestController
@RequestMapping("/api/me/notifications")
@Tag(name = "Account", description = "The signed-in visitor's account")
class NotificationController(
    private val notifications: NotificationStore,
    private val metrics: NotificationMetrics,
    private val clock: Clock
) {
    @GetMapping
    @Operation(
        summary = "The newest notifications, read or not, 20 unless `limit` says otherwise",
        description =
            "Pages with the `$NEXT_CURSOR` header: send its value as `before` for the entries after the last one listed. " +
                "The header is absent on the last page.",
        responses = [ApiResponse(responseCode = "200", headers = [Header(name = NEXT_CURSOR, description = "The `before` of the next page")])]
    )
    suspend fun list(
        @AuthenticationPrincipal account: Account,
        @Parameter(description = "How many entries, 1 to $MAX_LISTED")
        @RequestParam(defaultValue = "$LISTED")
        limit: Int,
        @Parameter(description = "The `$NEXT_CURSOR` of the previous page")
        @RequestParam(required = false)
        before: String?,
        @Parameter(description = "Only the unread entries")
        @RequestParam(defaultValue = "false")
        unread: Boolean,
        exchange: ServerWebExchange
    ): ResponseEntity<List<NotificationResponse>> {
        PARAMS.rejectUnknownIn(exchange)
        if (limit !in 1..MAX_LISTED) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "'limit' must be between 1 and $MAX_LISTED")
        val after = before?.let { NotificationCursor.parse(it) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "'before' is not a cursor") }
        // One row more than asked says whether a next page exists, without a count.
        val rows = notifications.list(account.id, limit + 1, after, unread)
        val page = rows.take(limit)
        val response = ResponseEntity.ok()
        if (rows.size > limit) response.header(NEXT_CURSOR, NotificationCursor.of(page.last()).token())
        return response.body(page.map(NotificationResponse::of))
    }

    @GetMapping("/unread-count")
    @Operation(summary = "How many notifications are unread, for the badge")
    suspend fun unreadCount(
        @AuthenticationPrincipal account: Account
    ): UnreadCountResponse = UnreadCountResponse(notifications.unreadCount(account.id))

    @PostMapping("/{id}/read")
    @Operation(summary = "Marks one notification read. One that is not the account's answers 404")
    suspend fun read(
        @AuthenticationPrincipal account: Account,
        @PathVariable id: UUID
    ): ResponseEntity<Unit> {
        if (!notifications.markRead(account.id, id, clock.instant())) throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such notification")
        metrics.record(NotificationAction.READ)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/read")
    @Operation(summary = "Marks every notification read")
    suspend fun readAll(
        @AuthenticationPrincipal account: Account
    ): ResponseEntity<Unit> {
        val marked = notifications.markAllRead(account.id, clock.instant())
        metrics.record(NotificationAction.READ_ALL)
        logger.info { "Notifications: all read, $marked marked" }
        return ResponseEntity.noContent().build()
    }

    private companion object {
        const val LISTED = 20
        const val MAX_LISTED = 50
        const val NEXT_CURSOR = "Next-Cursor"
        val PARAMS = QueryParameters.accepting(QueryParameters.named("limit", "before", "unread"))
    }
}

/** One notification, in the bell's menu and in the export. */
data class NotificationResponse(
    val id: UUID,
    /** `new_date`, or `change` for an event the account marked (#4045). */
    val kind: String,
    val event: String,
    val title: String,
    val date: LocalDate,
    /** What made it: the follow's `artist`, `venue` or `promoter`, or `rsvp` for a change. */
    val aboutKind: String,
    val aboutName: String,
    /** For a change, what changed: `cancelled`, `postponed`, `relocated`, `date`, `time` or `venue`. */
    val change: String?,
    val createdAt: Instant,
    val readAt: Instant?
) {
    companion object {
        fun of(notification: Notification) =
            NotificationResponse(
                id = notification.id,
                kind = notification.kind,
                event = notification.slug,
                title = notification.title,
                date = notification.date,
                aboutKind = notification.aboutKind,
                aboutName = notification.aboutName,
                change = notification.change,
                createdAt = notification.createdAt,
                readAt = notification.readAt
            )
    }
}

data class UnreadCountResponse(
    val count: Long
)
