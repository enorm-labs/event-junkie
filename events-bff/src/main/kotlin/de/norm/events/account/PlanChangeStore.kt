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
import java.time.LocalDate
import java.util.UUID

/**
 * What changed on an event an account marked, in the order a push names it: the first is the most severe. The tag is
 * the `accounts.notification.change` value and the API's.
 */
enum class PlanChange(
    val tag: String
) {
    CANCELLED("cancelled"),
    POSTPONED("postponed"),
    RELOCATED("relocated"),
    DATE("date"),
    TIME("time"),
    VENUE("venue");

    companion object {
        fun of(tag: String) = entries.first { it.tag == tag }
    }
}

/** A change notification new today, for an account with the switch on and a browser subscribed. */
data class PlanChangeDue(
    val accountId: UUID,
    val locale: String,
    val slug: String,
    val title: String,
    val date: LocalDate,
    val change: PlanChange
)

/**
 * The changes of events that accounts marked as interested or going (#4045): read from `events.event_change` past
 * `accounts.push_cursor.changes_until` (V206), written as `kind = 'change'` notifications.
 */
@Component
class PlanChangeStore(
    private val db: DatabaseClient
) {
    /** Sets the account's "Changes to my plans" switch. */
    suspend fun setChangePush(
        accountId: UUID,
        enabled: Boolean
    ) {
        db
            .sql("UPDATE accounts.account SET change_push = :enabled WHERE id = :account")
            .bind("enabled", enabled)
            .bind("account", accountId)
            .await()
    }

    /** Moves the cursor to [upTo] and answers where it was, or null when it is already there. As [PushSubscriptionStore.claimWindow]. */
    suspend fun claimWindow(upTo: Instant): Instant? =
        db
            .sql("UPDATE accounts.push_cursor SET changes_until = :upTo WHERE changes_until < :upTo RETURNING old.changes_until AS previous")
            .bind("upTo", upTo)
            .map { row -> row.required("previous", Instant::class.java) }
            .awaitOneOrNull()

    /**
     * Moves the RSVPs and notifications of an event removed by a date move across a re-keyed source id to the event that
     * replaced it: `event_change.previous_event_id` (#4139). Reads every kept change up to [upTo], not only the window, so
     * a run that failed after its claim loses nothing, and a second pass finds nothing left to move. A row the account
     * already has on the new event wins, and the old one is deleted. Answers how many RSVPs and notifications moved.
     */
    suspend fun carryOver(upTo: Instant): Pair<Long, Long> {
        val rsvps = moveThenDrop("accounts.rsvp", "event_id = m.new_id", "k.account_id = t.account_id AND k.event_id = m.new_id", upTo)
        val notifications =
            moveThenDrop(
                "accounts.notification",
                "event_id = m.new_id, about_id = CASE WHEN t.about_kind = 'rsvp' THEN m.new_id ELSE t.about_id END",
                "k.account_id = t.account_id AND k.event_id = m.new_id AND k.kind = t.kind",
                upTo
            )
        return rsvps to notifications
    }

    /** Points [table]'s rows on a removed event at its replacement, unless [taken] finds one there, then drops the rest. */
    private suspend fun moveThenDrop(
        table: String,
        set: String,
        taken: String,
        upTo: Instant
    ): Long {
        val moved =
            db
                .sql(
                    "WITH m AS ($MOVES) UPDATE $table t SET $set FROM m WHERE t.event_id = m.old_id " +
                        "AND NOT EXISTS (SELECT 1 FROM $table k WHERE $taken)"
                ).bind("upTo", upTo)
                .fetch()
                .awaitRowsUpdated()
        db
            .sql("WITH m AS ($MOVES) DELETE FROM $table t USING m WHERE t.event_id = m.old_id")
            .bind("upTo", upTo)
            .await()
        return moved
    }

    /**
     * Writes one notification per account and event for the changes seen in the window `(from, to]` of events starting
     * [today] or later. A changed end and a status back to scheduled are left out. With several changes, the most severe
     * names it.
     *
     * An event has one change row per account. A row from before [dayStart] starts over: unread, dated [now]. A row
     * from today keeps its date and its read state, and takes the new change only when it is more severe. Answers the
     * ids of the rows that started over or are new, which are the ones to push: at most one push per account and event a day.
     */
    suspend fun record(
        from: Instant,
        to: Instant,
        today: LocalDate,
        dayStart: Instant,
        now: Instant
    ): List<UUID> =
        db
            .sql(
                "WITH seen AS (SELECT DISTINCT ON (r.account_id, c.event_id) r.account_id, c.event_id, " +
                    "CASE WHEN c.field = 'STATUS' THEN lower(c.new_value) WHEN c.field = 'EVENT_DATE' THEN 'date' " +
                    "WHEN c.field = 'START_TIME' THEN 'time' ELSE 'venue' END AS change " +
                    "FROM $EVENTS_SCHEMA.event_change c JOIN $EVENTS_SCHEMA.event e ON e.id = c.event_id " +
                    "JOIN accounts.rsvp r ON r.event_id = c.event_id " +
                    "WHERE c.seen_at > :from AND c.seen_at <= :to AND e.event_date >= :today " +
                    "AND (c.field IN ('EVENT_DATE', 'START_TIME', 'VENUE') " +
                    "OR (c.field = 'STATUS' AND c.new_value IN ('CANCELLED', 'POSTPONED', 'RELOCATED'))) " +
                    "ORDER BY r.account_id, c.event_id, $SEVERITY_OF_FIELD) " +
                    "INSERT INTO accounts.notification AS n (account_id, event_id, kind, about_kind, about_id, change, created_at) " +
                    "SELECT account_id, event_id, 'change', 'rsvp', event_id, change, :now FROM seen " +
                    "ON CONFLICT (account_id, event_id, kind) DO UPDATE SET " +
                    "change = CASE WHEN n.created_at < :dayStart OR ${severity("EXCLUDED.change")} < ${severity("n.change")} " +
                    "THEN EXCLUDED.change ELSE n.change END, " +
                    "read_at = CASE WHEN n.created_at < :dayStart THEN NULL ELSE n.read_at END, " +
                    "created_at = CASE WHEN n.created_at < :dayStart THEN EXCLUDED.created_at ELSE n.created_at END " +
                    "RETURNING n.id, old.created_at AS previous"
            ).bind("from", from)
            .bind("to", to)
            .bind("today", today)
            .bind("dayStart", dayStart)
            .bind("now", now)
            .map { row -> row.required("id", UUID::class.java) to row.get("previous", Instant::class.java) }
            .flow()
            .toList()
            .filter { (_, previous) -> previous == null || previous < dayStart }
            .map { (id, _) -> id }

    /** Of the notifications [ids], those whose account has the switch on and at least one browser subscribed. */
    suspend fun due(ids: List<UUID>): List<PlanChangeDue> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            db
                .sql(
                    "SELECT n.account_id, a.locale, e.slug, e.title, e.event_date, n.change FROM accounts.notification n " +
                        "JOIN accounts.account a ON a.id = n.account_id JOIN $EVENTS_SCHEMA.event e ON e.id = n.event_id " +
                        "WHERE n.id = ANY(:ids) AND a.change_push " +
                        "AND EXISTS (SELECT 1 FROM accounts.push_subscription s WHERE s.account_id = n.account_id) " +
                        "ORDER BY n.account_id, e.event_date, e.id"
                ).bind("ids", ids.toTypedArray())
                .map { row ->
                    PlanChangeDue(
                        accountId = row.required("account_id", UUID::class.java),
                        locale = row.required("locale", String::class.java),
                        slug = row.required("slug", String::class.java),
                        title = row.required("title", String::class.java),
                        date = row.required("event_date", LocalDate::class.java),
                        change = PlanChange.of(row.required("change", String::class.java))
                    )
                }.flow()
                .toList()
        }

    private companion object {
        /** Each removed event beside the one that replaced it. */
        const val MOVES =
            "SELECT DISTINCT previous_event_id AS old_id, event_id AS new_id FROM $EVENTS_SCHEMA.event_change " +
                "WHERE previous_event_id IS NOT NULL AND seen_at <= :upTo"

        private val ORDER = PlanChange.entries.joinToString(",") { "'${it.tag}'" }

        fun severity(column: String) = "array_position(ARRAY[$ORDER], $column)"

        /** The same order on the raw `event_change` row, before it is a tag. */
        const val SEVERITY_OF_FIELD =
            "CASE WHEN c.field = 'STATUS' THEN array_position(ARRAY['CANCELLED','POSTPONED','RELOCATED'], c.new_value) " +
                "WHEN c.field = 'EVENT_DATE' THEN 4 WHEN c.field = 'START_TIME' THEN 5 ELSE 6 END"
    }
}
