package de.norm.events.account

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOne
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** The ticked boxes of "Report a problem" (#4399). V220's CHECK lists the same names. */
enum class ReportProblem {
    ARTISTS,
    TITLE,
    DATE_TIME,
    STATUS,
    PRICE_TICKETS,
    TYPE_GENRE,
    NOT_AN_EVENT,
    DUPLICATE,

    /** Needs a note: the box alone says nothing. */
    OTHER;

    companion object {
        fun ofOrNull(name: String): ReportProblem? = entries.firstOrNull { it.name == name }
    }
}

/** One open report with the event's slug and title, read at every call. */
data class ReportEntry(
    val slug: String,
    val title: String,
    val problems: List<ReportProblem>,
    val note: String?,
    val updatedAt: Instant
)

/**
 * The rows of `accounts.event_report` (V220). One per account and event. A report holds the event's id, so one whose
 * event is gone drops out of every read, and the daily cleanup deletes it.
 */
@Component
class ReportStore(
    private val db: DatabaseClient
) {
    /** Stores the report, or replaces the account's earlier one on the same event. Answers true when it replaced one. */
    suspend fun save(
        accountId: UUID,
        eventId: Long,
        problems: List<ReportProblem>,
        note: String?,
        now: Instant
    ): Boolean =
        db
            .sql(
                "INSERT INTO accounts.event_report (account_id, event_id, problems, note, created_at, updated_at) " +
                    "VALUES (:account, :event, :problems, :note, :now, :now) " +
                    "ON CONFLICT (account_id, event_id) DO UPDATE SET problems = EXCLUDED.problems, note = EXCLUDED.note, " +
                    "updated_at = EXCLUDED.updated_at RETURNING (xmax <> 0) AS replaced"
            ).bind("account", accountId)
            .bind("event", eventId)
            .bind("problems", problems.map { it.name }.toTypedArray())
            .let { spec -> if (note == null) spec.bindNull("note", String::class.java) else spec.bind("note", note) }
            .bind("now", now)
            .map { row -> row.required("replaced", Boolean::class.javaObjectType) }
            .awaitOne()

    /** Answers whether there was one to withdraw. */
    suspend fun withdraw(
        accountId: UUID,
        eventId: Long
    ): Boolean =
        db
            .sql("DELETE FROM accounts.event_report WHERE account_id = :account AND event_id = :event")
            .bind("account", accountId)
            .bind("event", eventId)
            .fetch()
            .awaitRowsUpdated() > 0

    /** Every open report of the account whose event still exists, newest change first. */
    suspend fun list(accountId: UUID): List<ReportEntry> =
        db
            .sql(
                "SELECT e.slug, e.title, r.problems, r.note, r.updated_at FROM accounts.event_report r " +
                    "JOIN $EVENTS_SCHEMA.event e ON e.id = r.event_id WHERE r.account_id = :account ORDER BY r.updated_at DESC"
            ).bind("account", accountId)
            .map { row ->
                ReportEntry(
                    slug = row.required("slug", String::class.java),
                    title = row.required("title", String::class.java),
                    problems = row.required("problems", Array<String>::class.java).mapNotNull(ReportProblem::ofOrNull),
                    note = row.get("note", String::class.java),
                    updatedAt = row.required("updated_at", Instant::class.java)
                )
            }.flow()
            .toList()

    /** Reports nobody resolved within the retention the privacy notice promises (60 days from the last change). */
    suspend fun deleteOlderThan(cutoff: Instant): Long =
        db
            .sql("DELETE FROM accounts.event_report WHERE updated_at < :cutoff")
            .bind("cutoff", cutoff)
            .fetch()
            .awaitRowsUpdated()

    /** Reports whose event the importer removed or merged away. */
    suspend fun deleteOrphans(): Long =
        db
            .sql("DELETE FROM accounts.event_report r WHERE NOT EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event e WHERE e.id = r.event_id)")
            .fetch()
            .awaitRowsUpdated()
}
