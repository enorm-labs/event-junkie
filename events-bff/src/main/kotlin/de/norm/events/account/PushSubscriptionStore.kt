package de.norm.events.account

import de.norm.events.EVENTS_SCHEMA
import de.norm.events.push.PushTarget
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitSingle
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A new event for an account that follows its artist, venue or promoter with the bell on. */
data class NewDate(
    val accountId: UUID,
    val locale: String,
    val slug: String,
    val title: String,
    val date: LocalDate
)

/** A subscription as the export shows it: the push service's host, never the endpoint, which identifies the browser. */
data class PushSubscriptionInfo(
    val pushService: String,
    val createdAt: Instant,
    val lastSentAt: Instant?
)

/**
 * The rows of `accounts.push_subscription` and `accounts.push_cursor` (V203, ADR-055): which browsers of an account
 * get a push, and up to when new events have been announced.
 */
@Component
class PushSubscriptionStore(
    private val db: DatabaseClient
) {
    /**
     * Saves the browser's subscription. The same browser signed in to another account moves the row to this one: it sends
     * the same `auth` secret, because the secret belongs to the subscription. An endpoint sent without it changes nothing,
     * so knowing another browser's endpoint is not enough to take its pushes (#4136).
     */
    suspend fun subscribe(
        accountId: UUID,
        target: PushTarget,
        now: Instant
    ) {
        db
            .sql(
                "INSERT INTO accounts.push_subscription (account_id, endpoint, p256dh, auth, created_at) VALUES (:account, :endpoint, :p256dh, :auth, :now) " +
                    "ON CONFLICT (endpoint) DO UPDATE SET account_id = EXCLUDED.account_id, p256dh = EXCLUDED.p256dh " +
                    "WHERE push_subscription.auth = EXCLUDED.auth"
            ).bind("account", accountId)
            .bind("endpoint", target.endpoint)
            .bind("p256dh", target.p256dh)
            .bind("auth", target.auth)
            .bind("now", now)
            .await()
    }

    suspend fun unsubscribe(
        accountId: UUID,
        endpoint: String
    ) {
        db
            .sql("DELETE FROM accounts.push_subscription WHERE account_id = :account AND endpoint = :endpoint")
            .bind("account", accountId)
            .bind("endpoint", endpoint)
            .await()
    }

    /** Whether [endpoint] is already this account's, so subscribing again does not count against the cap. */
    suspend fun owns(
        accountId: UUID,
        endpoint: String
    ): Boolean =
        db
            .sql("SELECT 1 AS hit FROM accounts.push_subscription WHERE account_id = :account AND endpoint = :endpoint")
            .bind("account", accountId)
            .bind("endpoint", endpoint)
            .map { _ -> true }
            .awaitOneOrNull() ?: false

    suspend fun count(accountId: UUID): Long =
        db
            .sql("SELECT count(*) AS n FROM accounts.push_subscription WHERE account_id = :account")
            .bind("account", accountId)
            .map { row -> row.required("n", Long::class.javaObjectType) }
            .awaitSingle()

    suspend fun targets(accountId: UUID): List<PushTarget> =
        db
            .sql("SELECT endpoint, p256dh, auth FROM accounts.push_subscription WHERE account_id = :account")
            .bind("account", accountId)
            .map { row ->
                PushTarget(row.required("endpoint", String::class.java), row.required("p256dh", String::class.java), row.required("auth", String::class.java))
            }.flow()
            .toList()

    suspend fun list(accountId: UUID): List<PushSubscriptionInfo> =
        db
            .sql("SELECT endpoint, created_at, last_sent_at FROM accounts.push_subscription WHERE account_id = :account ORDER BY created_at")
            .bind("account", accountId)
            .map { row ->
                PushSubscriptionInfo(
                    pushService = URI(row.required("endpoint", String::class.java)).host,
                    createdAt = row.required("created_at", Instant::class.java),
                    lastSentAt = row.get("last_sent_at", Instant::class.java)
                )
            }.flow()
            .toList()

    suspend fun sent(
        endpoint: String,
        now: Instant
    ) {
        db
            .sql("UPDATE accounts.push_subscription SET last_sent_at = :now WHERE endpoint = :endpoint")
            .bind("now", now)
            .bind("endpoint", endpoint)
            .await()
    }

    /** The push service said the subscription is gone (404 or 410). */
    suspend fun delete(endpoint: String) {
        db
            .sql("DELETE FROM accounts.push_subscription WHERE endpoint = :endpoint")
            .bind("endpoint", endpoint)
            .await()
    }

    /**
     * Moves the cursor to [upTo] and answers where it was, or null when it is already there. The row lock hands each
     * window to one replica: a second run waits, then sees the moved cursor and claims only what came after it.
     */
    suspend fun claimWindow(upTo: Instant): Instant? =
        db
            .sql("UPDATE accounts.push_cursor SET sent_until = :upTo WHERE sent_until < :upTo RETURNING old.sent_until AS previous")
            .bind("upTo", upTo)
            .map { row -> row.required("previous", Instant::class.java) }
            .awaitOneOrNull()

    /**
     * Events stored in the window `(from, to]` that start [today] or later, for each account that follows their artist,
     * venue or promoter with the bell on and has at least one browser subscribed. Cancelled events are left out.
     */
    suspend fun newDates(
        from: Instant,
        to: Instant,
        today: LocalDate
    ): List<NewDate> =
        db
            .sql(
                "SELECT DISTINCT f.account_id, a.locale, e.slug, e.title, e.event_date, e.id FROM $EVENTS_SCHEMA.event e " +
                    "JOIN accounts.follow f ON f.notify = 'instant' AND (" +
                    "(f.kind = 'venue' AND f.entity_id = e.venue_id) " +
                    "OR (f.kind = 'artist' AND EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event_artist ea " +
                    "WHERE ea.event_id = e.id AND ea.artist_id = f.entity_id)) " +
                    "OR (f.kind = 'promoter' AND EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event_promoter ep " +
                    "WHERE ep.event_id = e.id AND ep.promoter_id = f.entity_id))) " +
                    "JOIN accounts.account a ON a.id = f.account_id " +
                    "WHERE e.created_at > :from AND e.created_at <= :to AND e.event_date >= :today AND e.status <> 'CANCELLED' " +
                    "AND EXISTS (SELECT 1 FROM accounts.push_subscription s WHERE s.account_id = f.account_id) " +
                    "ORDER BY f.account_id, e.event_date, e.id"
            ).bind("from", from)
            .bind("to", to)
            .bind("today", today)
            .map { row ->
                NewDate(
                    accountId = row.required("account_id", UUID::class.java),
                    locale = row.required("locale", String::class.java),
                    slug = row.required("slug", String::class.java),
                    title = row.required("title", String::class.java),
                    date = row.required("event_date", LocalDate::class.java)
                )
            }.flow()
            .toList()
}
