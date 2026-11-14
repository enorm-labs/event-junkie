package de.norm.events.account

import de.norm.events.EVENTS_SCHEMA
import de.norm.events.event.EventRepository
import de.norm.events.event.EventStatus
import de.norm.events.event.RelocationRepository
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/** An event today that an account marked, for an account with the switch on and a browser subscribed. */
data class ReminderDue(
    val accountId: UUID,
    val locale: String,
    val slug: String,
    val title: String,
    /** The venue, or for a relocated event the place it moved to, named as the event page names it. */
    val place: String
)

/**
 * The day reminders (#4253): the "Reminders on the day" switch, `accounts.account.reminder_push`, and the day claimed
 * last, `accounts.push_cursor.reminders_until` (V211).
 */
@Component
class ReminderStore(
    private val db: DatabaseClient,
    private val events: EventRepository,
    private val relocations: RelocationRepository
) {
    /** Sets the account's "Reminders on the day" switch. */
    suspend fun setReminderPush(
        accountId: UUID,
        enabled: Boolean
    ) {
        db
            .sql("UPDATE accounts.account SET reminder_push = :enabled WHERE id = :account")
            .bind("enabled", enabled)
            .bind("account", accountId)
            .await()
    }

    /** Moves the cursor to [today] and answers true, or false when another run claimed it already. The row lock hands the day to one replica. */
    suspend fun claimDay(today: LocalDate): Boolean =
        db
            .sql("UPDATE accounts.push_cursor SET reminders_until = :today WHERE reminders_until < :today")
            .bind("today", today)
            .fetch()
            .awaitRowsUpdated() > 0

    /**
     * The events on [today] that accounts marked as going or interested, for each account with the switch on and at least
     * one browser subscribed. An event over several days counts on its first day, which is its `event_date`. A cancelled
     * event is left out, and so is a postponed one: its row is the date the show left, and a new date is another row (ADR-030).
     * Per account going comes first, then the earlier start. A relocated event names the place it moved to as the event
     * page's `movedTo` does (#5023): the venue [RelocationRepository] resolves, else the venue's own note, else the venue.
     */
    suspend fun due(today: LocalDate): List<ReminderDue> {
        val rows =
            db
                .sql(
                    "SELECT r.account_id, a.locale, e.id, e.slug, e.title, e.status, " +
                        "COALESCE(CASE WHEN e.status = 'RELOCATED' THEN e.relocated_to END, v.name) AS place " +
                        "FROM accounts.rsvp r JOIN accounts.account a ON a.id = r.account_id " +
                        "JOIN $EVENTS_SCHEMA.event e ON e.id = r.event_id JOIN $EVENTS_SCHEMA.venue v ON v.id = e.venue_id " +
                        "WHERE e.event_date = :today AND e.status NOT IN ('CANCELLED', 'POSTPONED') AND a.reminder_push " +
                        "AND EXISTS (SELECT 1 FROM accounts.push_subscription s WHERE s.account_id = r.account_id) " +
                        "ORDER BY r.account_id, r.status = 'going' DESC, e.start_time NULLS LAST, e.id"
                ).bind("today", today)
                .map { row ->
                    DueRow(
                        ReminderDue(
                            accountId = row.required("account_id", UUID::class.java),
                            locale = row.required("locale", String::class.java),
                            slug = row.required("slug", String::class.java),
                            title = row.required("title", String::class.java),
                            place = row.required("place", String::class.java)
                        ),
                        eventId = row.required("id", Long::class.javaObjectType),
                        relocated = row.required("status", String::class.java) == EventStatus.RELOCATED.name
                    )
                }.flow()
                .toList()
        // One resolution per relocated event, however many accounts marked it.
        val movedTo =
            rows
                .filter { it.relocated }
                .map { it.eventId }
                .distinct()
                .associateWith { id -> events.findById(id)?.let { relocations.newPlace(it) }?.venueName }
        return rows.map { row -> movedTo[row.eventId]?.let { row.due.copy(place = it) } ?: row.due }
    }

    private class DueRow(
        val due: ReminderDue,
        val eventId: Long,
        val relocated: Boolean
    )
}
