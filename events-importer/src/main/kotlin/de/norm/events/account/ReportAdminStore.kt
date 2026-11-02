package de.norm.events.account

import de.norm.events.EVENTS_SCHEMA
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** One report as the operator reads it, with the event it is about. Never the reporter's address. */
data class ReportRow(
    val eventId: Long,
    val slug: String,
    val title: String,
    val eventDate: LocalDate,
    val venue: String?,
    val sourceSlug: String?,
    val problems: List<String>,
    val note: String?,
    val updatedAt: Instant
)

/**
 * The open problem reports of `accounts.event_report` (V220) and the `can_report` allowlist (#4399). The BFF writes the
 * reports; resolving one here deletes it. Qualified names, because the connection's `search_path` is `events`.
 */
@Repository
class ReportAdminStore(
    private val databaseClient: DatabaseClient
) {
    /** Every report whose event still exists, by event date, then newest change first. */
    suspend fun list(): List<ReportRow> =
        databaseClient
            .sql(
                """
                SELECT e.id AS event_id, e.slug, e.title, e.event_date, v.name AS venue, s.slug AS source_slug,
                       r.problems, r.note, r.updated_at
                FROM accounts.event_report r
                JOIN $EVENTS_SCHEMA.event e ON e.id = r.event_id
                LEFT JOIN $EVENTS_SCHEMA.venue v ON v.id = e.venue_id
                LEFT JOIN $EVENTS_SCHEMA.event_source s ON s.id = e.event_source_id
                ORDER BY e.event_date, e.id, r.updated_at DESC
                """.trimIndent()
            ).map { row, _ -> toRow(row) }
            .flow()
            .toList()

    /** Resolves every report on the event. Answers how many it deleted. */
    suspend fun resolve(eventId: Long): Long =
        databaseClient
            .sql("DELETE FROM accounts.event_report WHERE event_id = :event")
            .bind("event", eventId)
            .fetch()
            .awaitRowsUpdated()

    /** Sets the allowlist flag on the account with this address. Answers its id, or null when no account has it. */
    suspend fun setCanReport(
        email: String,
        enabled: Boolean
    ): UUID? =
        databaseClient
            .sql("UPDATE accounts.account SET can_report = :enabled WHERE lower(email) = lower(:email) RETURNING id")
            .bind("enabled", enabled)
            .bind("email", email.trim())
            .map { row, _ -> requireNotNull(row.get("id", UUID::class.java)) }
            .awaitOneOrNull()

    private fun toRow(row: Readable) =
        ReportRow(
            eventId = requireNotNull(row.get("event_id", Long::class.javaObjectType)),
            slug = requireNotNull(row.get("slug", String::class.java)),
            title = requireNotNull(row.get("title", String::class.java)),
            eventDate = requireNotNull(row.get("event_date", LocalDate::class.java)),
            venue = row.get("venue", String::class.java),
            sourceSlug = row.get("source_slug", String::class.java),
            problems = requireNotNull(row.get("problems", Array<String>::class.java)).toList(),
            note = row.get("note", String::class.java),
            updatedAt = requireNotNull(row.get("updated_at", Instant::class.java))
        )
}
