package de.norm.events.account

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.awaitSingle
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * One entry under the header's bell: a new date of something the account follows, a change to an event it marked, or
 * the operator's answer to its problem report (#5308). A report note has no event: [slug] and [date] are null, and
 * [subjectKind] and [subjectSlug] name the page. [title] is the page's name at the resolve.
 */
data class Notification(
    val id: UUID,
    val kind: String,
    val slug: String?,
    val title: String,
    val date: LocalDate?,
    val aboutKind: String,
    val aboutName: String,
    val change: String?,
    val createdAt: Instant,
    val readAt: Instant?,
    val subjectKind: String? = null,
    /** Null when the page is gone. */
    val subjectSlug: String? = null,
    val outcome: String? = null,
    val reply: String? = null
)

/** How many rows a window wrote, and for how many accounts. */
data class RecordedNotifications(
    val rows: Int,
    val accounts: Int
)

/** The rows of `accounts.notification` (V205, #4078). An account reads and changes only its own. [PlanChangeStore] writes the changes. */
@Component
class NotificationStore(
    private val db: DatabaseClient
) {
    /**
     * Writes a notification for every account that follows the artist, venue or promoter of an event stored in the window
     * `(from, to]` and starting [today] or later, bell on or off. With two matching follows, the artist names it, then
     * the promoter, then the venue. Cancelled events are left out, and an event the account has already heard of is skipped.
     */
    suspend fun record(
        from: Instant,
        to: Instant,
        today: LocalDate,
        now: Instant
    ): RecordedNotifications {
        val accounts =
            db
                .sql(
                    "INSERT INTO accounts.notification (account_id, event_id, kind, about_kind, about_id, created_at) " +
                        "SELECT DISTINCT ON (f.account_id, e.id) f.account_id, e.id, 'new_date', f.kind, f.entity_id, :now " +
                        "FROM $EVENTS_SCHEMA.event e JOIN accounts.follow f ON " +
                        "(f.kind = 'venue' AND f.entity_id = e.venue_id) " +
                        "OR (f.kind = 'artist' AND EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event_artist ea " +
                        "WHERE ea.event_id = e.id AND ea.artist_id = f.entity_id)) " +
                        "OR (f.kind = 'promoter' AND EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event_promoter ep " +
                        "WHERE ep.event_id = e.id AND ep.promoter_id = f.entity_id)) " +
                        "WHERE e.created_at > :from AND e.created_at <= :to AND e.event_date >= :today AND e.status <> 'CANCELLED' " +
                        "ORDER BY f.account_id, e.id, CASE f.kind WHEN 'artist' THEN 0 WHEN 'promoter' THEN 1 ELSE 2 END " +
                        "ON CONFLICT (account_id, event_id, kind) DO NOTHING RETURNING account_id"
                ).bind("from", from)
                .bind("to", to)
                .bind("today", today)
                .bind("now", now)
                .map { row -> row.required("account_id", UUID::class.java) }
                .flow()
                .toList()
        return RecordedNotifications(accounts.size, accounts.toSet().size)
    }

    /**
     * The newest [limit] notifications whose event still exists, and every report note, after [after] when it is given,
     * and only the unread ones when [unreadOnly]. The order is total, so the cursor of one page's last row starts the next
     * without a gap or a repeat. A report note has no event date, so it sorts as [NotificationCursor.NO_DATE].
     */
    suspend fun list(
        accountId: UUID,
        limit: Int,
        after: NotificationCursor? = null,
        unreadOnly: Boolean = false
    ): List<Notification> {
        val conditions =
            listOfNotNull(
                "n.account_id = :account",
                "(n.event_id IS NULL OR e.id IS NOT NULL)",
                "n.read_at IS NULL".takeIf { unreadOnly },
                (
                    "(n.created_at < :createdAt OR (n.created_at = :createdAt AND " +
                        "($SORT_DATE > :eventDate OR ($SORT_DATE = :eventDate AND n.id > :id))))"
                ).takeIf { after != null }
            )
        var spec =
            db
                .sql(
                    "SELECT n.id, n.kind, e.slug, COALESCE(e.title, n.subject_title) AS title, e.event_date, n.about_kind, " +
                        "n.change, n.created_at, n.read_at, n.subject_kind, n.outcome, n.reply, " +
                        "COALESCE(a.name, p.name, v.name) AS about_name, COALESCE(se.slug, sa.slug, sp.slug, sv.slug) AS subject_slug " +
                        "FROM accounts.notification n LEFT JOIN $EVENTS_SCHEMA.event e ON e.id = n.event_id " +
                        "LEFT JOIN $EVENTS_SCHEMA.artist a ON n.about_kind = 'artist' AND a.id = n.about_id " +
                        "LEFT JOIN $EVENTS_SCHEMA.promoter p ON n.about_kind = 'promoter' AND p.id = n.about_id " +
                        "LEFT JOIN $EVENTS_SCHEMA.venue v ON n.about_kind = 'venue' AND v.id = n.about_id " +
                        "LEFT JOIN $EVENTS_SCHEMA.event se ON n.subject_kind = 'event' AND se.id = n.about_id " +
                        "LEFT JOIN $EVENTS_SCHEMA.artist sa ON n.subject_kind = 'artist' AND sa.id = n.about_id " +
                        "LEFT JOIN $EVENTS_SCHEMA.promoter sp ON n.subject_kind = 'promoter' AND sp.id = n.about_id " +
                        "LEFT JOIN $EVENTS_SCHEMA.venue sv ON n.subject_kind = 'venue' AND sv.id = n.about_id " +
                        "WHERE ${conditions.joinToString(" AND ")} ORDER BY n.created_at DESC, $SORT_DATE, n.id LIMIT :limit"
                ).bind("account", accountId)
                .bind("limit", limit)
        if (after != null) {
            spec = spec.bind("createdAt", after.createdAt).bind("eventDate", after.eventDate).bind("id", after.id)
        }
        return spec
            .map { row ->
                Notification(
                    id = row.required("id", UUID::class.java),
                    kind = row.required("kind", String::class.java),
                    slug = row.get("slug", String::class.java),
                    title = row.required("title", String::class.java),
                    date = row.get("event_date", LocalDate::class.java),
                    aboutKind = row.required("about_kind", String::class.java),
                    // A merged-away artist or venue leaves the event's title to name it, and a report note names its page.
                    aboutName = row.get("about_name", String::class.java) ?: row.required("title", String::class.java),
                    change = row.get("change", String::class.java),
                    createdAt = row.required("created_at", Instant::class.java),
                    readAt = row.get("read_at", Instant::class.java),
                    subjectKind = row.get("subject_kind", String::class.java),
                    subjectSlug = row.get("subject_slug", String::class.java),
                    outcome = row.get("outcome", String::class.java),
                    reply = row.get("reply", String::class.java)
                )
            }.flow()
            .toList()
    }

    suspend fun unreadCount(accountId: UUID): Long =
        db
            .sql(
                "SELECT count(*) AS n FROM accounts.notification n WHERE n.account_id = :account AND n.read_at IS NULL " +
                    "AND (n.event_id IS NULL OR EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event e WHERE e.id = n.event_id))"
            ).bind("account", accountId)
            .map { row -> row.required("n", Long::class.javaObjectType) }
            .awaitSingle()

    /** Marks one read. Answers false when the row is not this account's, or does not exist. */
    suspend fun markRead(
        accountId: UUID,
        id: UUID,
        now: Instant
    ): Boolean =
        db
            .sql("UPDATE accounts.notification SET read_at = COALESCE(read_at, :now) WHERE id = :id AND account_id = :account")
            .bind("now", now)
            .bind("id", id)
            .bind("account", accountId)
            .fetch()
            .awaitRowsUpdated() > 0

    suspend fun markAllRead(
        accountId: UUID,
        now: Instant
    ): Long =
        db
            .sql("UPDATE accounts.notification SET read_at = :now WHERE account_id = :account AND read_at IS NULL")
            .bind("now", now)
            .bind("account", accountId)
            .fetch()
            .awaitRowsUpdated()

    /**
     * Notifications older than [before], read or not, and those whose event the importer removed. One whose event a date
     * move replaced stays for [PlanChangesJob] to carry across (#4139). A report note has no event, so only its age ends it.
     */
    suspend fun deleteExpired(before: Instant): Long =
        db
            .sql(
                "DELETE FROM accounts.notification n WHERE n.created_at < :before " +
                    "OR (n.event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event e WHERE e.id = n.event_id) " +
                    "AND NOT EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event_change c WHERE c.previous_event_id = n.event_id))"
            ).bind("before", before)
            .fetch()
            .awaitRowsUpdated()

    private companion object {
        /** The second sort key: the event's date, or [NotificationCursor.NO_DATE] for a note without an event. */
        const val SORT_DATE = "COALESCE(e.event_date, DATE '${NotificationCursor.NO_DATE}')"
    }
}
