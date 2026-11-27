package de.norm.events.account

import de.norm.events.EVENTS_SCHEMA
import de.norm.events.common.required
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

/**
 * What a report is about (#5603), as `accounts.report.kind` (V288) stores it: the tag, the path segment of its page,
 * the table behind it and the column with its name. The BFF's `ReportKind` carries the same four.
 */
enum class ReportSubject(
    val tag: String,
    val path: String,
    val table: String,
    val nameColumn: String
) {
    EVENT("event", "events", "event", "title"),
    ARTIST("artist", "artists", "artist", "name"),
    PROMOTER("promoter", "promoters", "promoter", "name"),
    VENUE("venue", "venues", "venue", "name");

    companion object {
        fun ofPath(path: String): ReportSubject? = entries.firstOrNull { it.path == path }

        fun ofTag(tag: String): ReportSubject = entries.first { it.tag == tag }
    }
}

/** True when the subject of the report aliased `r` still exists. */
internal val REPORT_SUBJECT_EXISTS =
    ReportSubject.entries.joinToString(" ", "CASE r.kind ", " END") {
        "WHEN '${it.tag}' THEN EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.${it.table} x WHERE x.id = r.subject_id)"
    }

/** One report as the operator reads it, with the subject it is about. Never the reporter's address. */
data class ReportRow(
    val kind: ReportSubject,
    val id: Long,
    val slug: String,
    val name: String,
    /** The event's date, and its venue and source; null on the other kinds. */
    val eventDate: LocalDate?,
    val venue: String?,
    val sourceSlug: String?,
    val problems: List<String>,
    val note: String?,
    val updatedAt: Instant
)

/**
 * The open problem reports of `accounts.report` (V288) and the `can_report` flag (#4399, #5603, #5604). The BFF writes
 * the reports; resolving one here deletes it. Qualified names, because the connection's `search_path` is `events`.
 */
@Repository
class ReportAdminStore(
    private val databaseClient: DatabaseClient
) {
    /** Every report whose subject still exists: events by date, then acts, promoters and venues by name. */
    suspend fun list(): List<ReportRow> =
        databaseClient
            .sql(
                """
                SELECT r.kind, r.subject_id, $SUBJECT_SLUG AS slug, $SUBJECT_NAME AS name, event.event_date,
                       v.name AS venue, s.slug AS source_slug, r.problems, r.note, r.updated_at
                FROM accounts.report r
                $SUBJECT_JOINS
                LEFT JOIN $EVENTS_SCHEMA.venue v ON v.id = event.venue_id
                LEFT JOIN $EVENTS_SCHEMA.event_source s ON s.id = event.event_source_id
                WHERE $SUBJECT_SLUG IS NOT NULL
                ORDER BY $KIND_ORDER, event.event_date, $SUBJECT_NAME, r.subject_id, r.updated_at DESC
                """.trimIndent()
            ).map { row, _ -> toRow(row) }
            .flow()
            .toList()

    /** Resolves every report on the subject. Answers how many it deleted. */
    suspend fun resolve(
        kind: ReportSubject,
        id: Long
    ): Long =
        databaseClient
            .sql("DELETE FROM accounts.report WHERE kind = :kind AND subject_id = :id")
            .bind("kind", kind.tag)
            .bind("id", id)
            .fetch()
            .awaitRowsUpdated()

    /** Lifts or restores the cap on open reports for the account with this address. Answers its id, or null when no account has it. */
    suspend fun setReportCapExempt(
        email: String,
        exempt: Boolean
    ): UUID? =
        databaseClient
            .sql("UPDATE accounts.account SET report_cap_exempt = :exempt WHERE lower(email) = lower(:email) RETURNING id")
            .bind("exempt", exempt)
            .bind("email", email.trim())
            .map { row, _ -> row.required("id", UUID::class.java) }
            .awaitOneOrNull()

    /** Sets the report flag on the account with this address. Answers its id, or null when no account has it. */
    suspend fun setCanReport(
        email: String,
        enabled: Boolean
    ): UUID? =
        databaseClient
            .sql("UPDATE accounts.account SET can_report = :enabled WHERE lower(email) = lower(:email) RETURNING id")
            .bind("enabled", enabled)
            .bind("email", email.trim())
            .map { row, _ -> row.required("id", UUID::class.java) }
            .awaitOneOrNull()

    private fun toRow(row: Readable) =
        ReportRow(
            kind = ReportSubject.ofTag(row.required("kind", String::class.java)),
            id = row.required("subject_id", Long::class.javaObjectType),
            slug = row.required("slug", String::class.java),
            name = row.required("name", String::class.java),
            eventDate = row.get("event_date", LocalDate::class.java),
            venue = row.get("venue", String::class.java),
            sourceSlug = row.get("source_slug", String::class.java),
            problems = row.required("problems", Array<String>::class.java).toList(),
            note = row.get("note", String::class.java),
            updatedAt = row.required("updated_at", Instant::class.java)
        )

    private companion object {
        /** One LEFT JOIN per kind, aliased by its tag, so each probes its own primary key. */
        val SUBJECT_JOINS =
            ReportSubject.entries.joinToString("\n") {
                "LEFT JOIN $EVENTS_SCHEMA.${it.table} ${it.tag} ON r.kind = '${it.tag}' AND ${it.tag}.id = r.subject_id"
            }
        val SUBJECT_SLUG = ReportSubject.entries.joinToString(", ", "COALESCE(", ")") { "${it.tag}.slug" }
        val SUBJECT_NAME = ReportSubject.entries.joinToString(", ", "COALESCE(", ")") { "${it.tag}.${it.nameColumn}" }
        val KIND_ORDER = ReportSubject.entries.withIndex().joinToString(" ", "CASE r.kind ", " END") { "WHEN '${it.value.tag}' THEN ${it.index}" }
    }
}
