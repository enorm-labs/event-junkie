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

/** One entry under the header's bell: a new date of something the account follows, or a change to an event it marked. */
data class Notification(
    val id: UUID,
    val kind: String,
    val slug: String,
    val title: String,
    val date: LocalDate,
    val aboutKind: String,
    val aboutName: String,
    val change: String?,
    val createdAt: Instant,
    val readAt: Instant?
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

    /** The newest [limit] notifications whose event still exists. */
    suspend fun list(
        accountId: UUID,
        limit: Int
    ): List<Notification> =
        db
            .sql(
                "SELECT n.id, n.kind, e.slug, e.title, e.event_date, n.about_kind, n.change, n.created_at, n.read_at, " +
                    "COALESCE(a.name, p.name, v.name) AS about_name " +
                    "FROM accounts.notification n JOIN $EVENTS_SCHEMA.event e ON e.id = n.event_id " +
                    "LEFT JOIN $EVENTS_SCHEMA.artist a ON n.about_kind = 'artist' AND a.id = n.about_id " +
                    "LEFT JOIN $EVENTS_SCHEMA.promoter p ON n.about_kind = 'promoter' AND p.id = n.about_id " +
                    "LEFT JOIN $EVENTS_SCHEMA.venue v ON n.about_kind = 'venue' AND v.id = n.about_id " +
                    "WHERE n.account_id = :account ORDER BY n.created_at DESC, e.event_date, n.id LIMIT :limit"
            ).bind("account", accountId)
            .bind("limit", limit)
            .map { row ->
                Notification(
                    id = row.required("id", UUID::class.java),
                    kind = row.required("kind", String::class.java),
                    slug = row.required("slug", String::class.java),
                    title = row.required("title", String::class.java),
                    date = row.required("event_date", LocalDate::class.java),
                    aboutKind = row.required("about_kind", String::class.java),
                    // A merged-away artist or venue leaves the event's title to name it.
                    aboutName = row.get("about_name", String::class.java) ?: row.required("title", String::class.java),
                    change = row.get("change", String::class.java),
                    createdAt = row.required("created_at", Instant::class.java),
                    readAt = row.get("read_at", Instant::class.java)
                )
            }.flow()
            .toList()

    suspend fun unreadCount(accountId: UUID): Long =
        db
            .sql(
                "SELECT count(*) AS n FROM accounts.notification n WHERE n.account_id = :account AND n.read_at IS NULL " +
                    "AND EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event e WHERE e.id = n.event_id)"
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

    /** Notifications older than [before], read or not, and those whose event the importer removed. */
    suspend fun deleteExpired(before: Instant): Long =
        db
            .sql(
                "DELETE FROM accounts.notification n WHERE n.created_at < :before " +
                    "OR NOT EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event e WHERE e.id = n.event_id)"
            ).bind("before", before)
            .fetch()
            .awaitRowsUpdated()
}
