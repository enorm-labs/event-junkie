package de.norm.events.account

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** One RSVP with the event's slug and title, read at every call, so a renamed event shows its new name. */
data class RsvpEntry(
    val slug: String,
    val title: String,
    val status: RsvpStatus,
    val updatedAt: Instant
)

/**
 * The rows of `accounts.rsvp` (V202). An RSVP holds the event's id, so a slug is read from `events` at every read, and an
 * RSVP whose event is gone drops out.
 */
@Component
class RsvpStore(
    private val db: DatabaseClient
) {
    /** The event behind [slug], or null when no such event exists. */
    suspend fun eventId(slug: String): Long? =
        db
            .sql("SELECT id FROM $EVENTS_SCHEMA.event WHERE slug = :slug")
            .bind("slug", slug)
            .map { row -> row.required("id", Long::class.javaObjectType) }
            .awaitOneOrNull()

    /** Sets or changes the RSVP. */
    suspend fun set(
        accountId: UUID,
        eventId: Long,
        status: RsvpStatus,
        now: Instant
    ) {
        db
            .sql(
                "INSERT INTO accounts.rsvp (account_id, event_id, status, created_at, updated_at) VALUES (:account, :event, :status, :now, :now) " +
                    "ON CONFLICT (account_id, event_id) DO UPDATE SET status = EXCLUDED.status, updated_at = EXCLUDED.updated_at"
            ).bind("account", accountId)
            .bind("event", eventId)
            .bind("status", status.tag)
            .bind("now", now)
            .await()
    }

    suspend fun withdraw(
        accountId: UUID,
        eventId: Long
    ) {
        db
            .sql("DELETE FROM accounts.rsvp WHERE account_id = :account AND event_id = :event")
            .bind("account", accountId)
            .bind("event", eventId)
            .await()
    }

    /** Every RSVP of the account whose event still exists, newest change first. */
    suspend fun list(accountId: UUID): List<RsvpEntry> =
        db
            .sql(
                "SELECT e.slug, e.title, r.status, r.updated_at FROM accounts.rsvp r JOIN $EVENTS_SCHEMA.event e ON e.id = r.event_id " +
                    "WHERE r.account_id = :account ORDER BY r.updated_at DESC"
            ).bind("account", accountId)
            .map { row ->
                RsvpEntry(
                    slug = row.required("slug", String::class.java),
                    title = row.required("title", String::class.java),
                    status = RsvpStatus.of(row.required("status", String::class.java)),
                    updatedAt = row.required("updated_at", Instant::class.java)
                )
            }.flow()
            .toList()

    /** The account's RSVPed event ids, optionally of one [status], for the event search. */
    suspend fun eventIds(
        accountId: UUID,
        status: RsvpStatus? = null
    ): List<Long> =
        db
            .sql("SELECT event_id FROM accounts.rsvp WHERE account_id = :account" + if (status == null) "" else " AND status = :status")
            .bind("account", accountId)
            .let { spec -> if (status == null) spec else spec.bind("status", status.tag) }
            .map { row -> row.required("event_id", Long::class.javaObjectType) }
            .flow()
            .toList()

    /**
     * RSVPs whose event no longer exists: removed by the importer, or merged away. One whose event a date move replaced
     * stays for [PlanChangesJob] to carry across (#4139).
     */
    suspend fun deleteOrphans(): Long =
        db
            .sql(
                "DELETE FROM accounts.rsvp r WHERE NOT EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event e WHERE e.id = r.event_id) " +
                    "AND NOT EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event_change c WHERE c.previous_event_id = r.event_id)"
            ).fetch()
            .awaitRowsUpdated()
}

enum class RsvpStatus(
    val tag: String
) {
    INTERESTED("interested"),
    GOING("going");

    companion object {
        fun ofOrNull(tag: String): RsvpStatus? = entries.firstOrNull { it.tag == tag }

        fun of(tag: String): RsvpStatus = requireNotNull(ofOrNull(tag)) { "Unknown RSVP status $tag" }
    }
}
