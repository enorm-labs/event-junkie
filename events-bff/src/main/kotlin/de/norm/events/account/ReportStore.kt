package de.norm.events.account

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOne
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * The ticked boxes of "Report a problem" (#4399, #5603). Each [ReportKind] allows its own subset. TITLE, DESCRIPTION,
 * TYPE_GENRE, DUPLICATE and QUESTION are the names of earlier event forms, kept so stored reports still read. V288's
 * CHECK lists the same names.
 */
enum class ReportProblem {
    ARTISTS,
    PROMOTER,
    GENRE,
    EVENT_TYPE,
    FEATURES,
    DATE_TIME,
    TEXT,
    STATUS,
    PRICE_TICKETS,

    /** Not a real event, or listed twice. */
    NOT_AN_EVENT,

    /** A hint, not a problem: the details are on the event's image. Never alone without a note. */
    ON_IMAGE,

    TITLE,
    DESCRIPTION,
    TYPE_GENRE,
    DUPLICATE,

    /** A report with a note and no box. No form offers it, so the BFF stores it for a note-only report. */
    OTHER,
    QUESTION,

    NAME,

    /** The venue's address or its pin on the map. */
    ADDRESS,

    /** The website, Instagram or other links. */
    LINKS,

    /** The venue has closed. */
    CLOSED,

    /** Events of another act with the same name, or a wrong MusicBrainz match. */
    WRONG_ACT,

    /** The act's picture. */
    IMAGE,
    NOT_A_PROMOTER;

    companion object {
        fun ofOrNull(name: String): ReportProblem? = entries.firstOrNull { it.name == name }
    }
}

/**
 * What a report is about: the path segment of its page, the table behind it, the column with its name, and the boxes
 * it allows. [OTHER][ReportProblem.OTHER] is allowed everywhere, for a note-only report.
 */
enum class ReportKind(
    val tag: String,
    val path: String,
    val table: String,
    val nameColumn: String,
    val allowed: Set<ReportProblem>
) {
    EVENT(
        "event",
        "events",
        "event",
        "title",
        setOf(
            ReportProblem.ARTISTS,
            ReportProblem.PROMOTER,
            ReportProblem.GENRE,
            ReportProblem.EVENT_TYPE,
            ReportProblem.FEATURES,
            ReportProblem.DATE_TIME,
            ReportProblem.TEXT,
            ReportProblem.STATUS,
            ReportProblem.PRICE_TICKETS,
            ReportProblem.NOT_AN_EVENT,
            ReportProblem.ON_IMAGE,
            ReportProblem.TITLE,
            ReportProblem.DESCRIPTION,
            ReportProblem.TYPE_GENRE,
            ReportProblem.DUPLICATE,
            ReportProblem.OTHER,
            ReportProblem.QUESTION
        )
    ),
    ARTIST(
        "artist",
        "artists",
        "artist",
        "name",
        setOf(
            ReportProblem.NAME,
            ReportProblem.WRONG_ACT,
            ReportProblem.GENRE,
            ReportProblem.LINKS,
            ReportProblem.TEXT,
            ReportProblem.IMAGE,
            ReportProblem.OTHER
        )
    ),
    PROMOTER(
        "promoter",
        "promoters",
        "promoter",
        "name",
        setOf(ReportProblem.NAME, ReportProblem.LINKS, ReportProblem.TEXT, ReportProblem.NOT_A_PROMOTER, ReportProblem.OTHER)
    ),
    VENUE(
        "venue",
        "venues",
        "venue",
        "name",
        setOf(
            ReportProblem.NAME,
            ReportProblem.ADDRESS,
            ReportProblem.LINKS,
            ReportProblem.TEXT,
            ReportProblem.CLOSED,
            ReportProblem.OTHER
        )
    );

    companion object {
        fun ofPath(path: String): ReportKind? = entries.firstOrNull { it.path == path }

        /** The kind of a stored row. V288's CHECK admits no other tag. */
        fun ofTag(tag: String): ReportKind = entries.first { it.tag == tag }
    }
}

/** One event, act, promoter or venue a report is about: its kind and its id. */
data class ReportSubject(
    val kind: ReportKind,
    val id: Long
)

/** One open report with its subject's slug and name, read at every call. */
data class ReportEntry(
    val kind: ReportKind,
    val slug: String,
    val title: String,
    val problems: List<ReportProblem>,
    val note: String?,
    val updatedAt: Instant
)

/**
 * The rows of `accounts.report` (V288). One per account and subject. A report holds the subject's id, so one whose
 * subject is gone drops out of every read, and the daily cleanup deletes it.
 */
@Component
class ReportStore(
    private val db: DatabaseClient
) {
    /** The subject behind [slug], or null when no such subject exists. */
    suspend fun subject(
        kind: ReportKind,
        slug: String
    ): ReportSubject? =
        db
            .sql("SELECT id FROM $EVENTS_SCHEMA.${kind.table} WHERE slug = :slug")
            .bind("slug", slug)
            .map { row -> ReportSubject(kind, row.required("id", Long::class.javaObjectType)) }
            .awaitOneOrNull()

    /** Stores the report, or replaces the account's earlier one on the same subject. Answers true when it replaced one. */
    suspend fun save(
        accountId: UUID,
        subject: ReportSubject,
        problems: List<ReportProblem>,
        note: String?,
        now: Instant
    ): Boolean =
        db
            .sql(
                "INSERT INTO accounts.report (account_id, kind, subject_id, problems, note, created_at, updated_at) " +
                    "VALUES (:account, :kind, :subject, :problems, :note, :now, :now) " +
                    "ON CONFLICT (account_id, kind, subject_id) DO UPDATE SET problems = EXCLUDED.problems, note = EXCLUDED.note, " +
                    "updated_at = EXCLUDED.updated_at RETURNING (xmax <> 0) AS replaced"
            ).bind("account", accountId)
            .bind("kind", subject.kind.tag)
            .bind("subject", subject.id)
            .bind("problems", problems.map { it.name }.toTypedArray())
            .let { spec -> if (note == null) spec.bindNull("note", String::class.java) else spec.bind("note", note) }
            .bind("now", now)
            .map { row -> row.required("replaced", Boolean::class.javaObjectType) }
            .awaitOne()

    /** Answers whether there was one to withdraw. */
    suspend fun withdraw(
        accountId: UUID,
        subject: ReportSubject
    ): Boolean =
        db
            .sql("DELETE FROM accounts.report WHERE account_id = :account AND kind = :kind AND subject_id = :subject")
            .bind("account", accountId)
            .bind("kind", subject.kind.tag)
            .bind("subject", subject.id)
            .fetch()
            .awaitRowsUpdated() > 0

    /** Every open report of the account whose subject still exists, newest change first. */
    suspend fun list(accountId: UUID): List<ReportEntry> =
        db
            .sql(
                "SELECT r.kind, $SUBJECT_SLUG AS slug, $SUBJECT_NAME AS name, r.problems, r.note, r.updated_at " +
                    "FROM accounts.report r $SUBJECT_JOINS " +
                    "WHERE r.account_id = :account AND $SUBJECT_SLUG IS NOT NULL ORDER BY r.updated_at DESC"
            ).bind("account", accountId)
            .map { row ->
                ReportEntry(
                    kind = ReportKind.ofTag(row.required("kind", String::class.java)),
                    slug = row.required("slug", String::class.java),
                    title = row.required("name", String::class.java),
                    problems = row.required("problems", Array<String>::class.java).mapNotNull(ReportProblem::ofOrNull),
                    note = row.get("note", String::class.java),
                    updatedAt = row.required("updated_at", Instant::class.java)
                )
            }.flow()
            .toList()

    /** Reports nobody resolved within the retention the privacy notice promises (60 days from the last change). */
    suspend fun deleteOlderThan(cutoff: Instant): Long =
        db
            .sql("DELETE FROM accounts.report WHERE updated_at < :cutoff")
            .bind("cutoff", cutoff)
            .fetch()
            .awaitRowsUpdated()

    /** Reports whose subject the importer removed or merged away. */
    suspend fun deleteOrphans(): Long =
        db
            .sql("DELETE FROM accounts.report r WHERE NOT $SUBJECT_EXISTS")
            .fetch()
            .awaitRowsUpdated()

    private companion object {
        /** One LEFT JOIN per kind, aliased by its tag, so each probes its own primary key. */
        val SUBJECT_JOINS =
            ReportKind.entries.joinToString(" ") {
                "LEFT JOIN $EVENTS_SCHEMA.${it.table} ${it.tag} ON r.kind = '${it.tag}' AND ${it.tag}.id = r.subject_id"
            }
        val SUBJECT_SLUG = ReportKind.entries.joinToString(", ", "COALESCE(", ")") { "${it.tag}.slug" }
        val SUBJECT_NAME = ReportKind.entries.joinToString(", ", "COALESCE(", ")") { "${it.tag}.${it.nameColumn}" }

        /** True when the report's subject still exists. */
        val SUBJECT_EXISTS =
            ReportKind.entries.joinToString(" ", "CASE r.kind ", " END") {
                "WHEN '${it.tag}' THEN EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.${it.table} s WHERE s.id = r.subject_id)"
            }
    }
}
