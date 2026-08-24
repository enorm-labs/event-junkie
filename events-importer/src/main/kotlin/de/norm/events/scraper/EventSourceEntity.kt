package de.norm.events.scraper

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * R2DBC entity mapped to the `event_source` table.
 *
 * Tracks per-venue import configuration and metadata: which URL to scrape,
 * which [EventImporter] implementation to use, cached conditional-request headers,
 * and the result of the last import run.
 */
@Table("event_source")
data class EventSourceEntity(
    @Id val id: Long? = null,
    /** FK to the venue this source imports events for. */
    val venueId: Long,
    /** Human-readable label (e.g. "Privatclub Website"). */
    val name: String,
    /** URL-friendly unique key for API dispatch (e.g. "privatclub"). */
    val slug: String,
    /** The event listing page URL to scrape. */
    val url: String,
    /** Maps to an [EventSource] enum value identifying which [EventImporter] handles this source. */
    val sourceType: String,
    /** Whether this source is actively scraped. Disabled sources are skipped. */
    val enabled: Boolean = true,
    /** How often to import, in minutes. Defaults to 1440 (daily). */
    val importIntervalMinutes: Int = DEFAULT_IMPORT_INTERVAL_MINUTES,
    /** Number of consecutive failures. Reset to 0 on success. */
    val retryCount: Int = 0,
    /** Maximum retry attempts before giving up. */
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    /** Cached ETag header for conditional requests (If-None-Match). */
    val etag: String? = null,
    /** Cached Last-Modified header for conditional requests (If-Modified-Since). */
    val lastModified: String? = null,
    /** Timestamp of the last completed import (successful or failed). */
    val lastImportAt: Instant? = null,
    /**
     * Timestamp of the last import that **succeeded**, which [lastImportAt] is not.
     *
     * The two differ exactly when they matter most: `lastImportAt` is written on failure as well, so
     * a source that has been broken for a week reports a fresh timestamp there. This column is the
     * one `importer.source.last_success` publishes, and the one an alert on
     * `time() - last_success > 3 * interval` can be written against (#415).
     *
     * A 304 counts as a success — the source was reachable and answered, which is what the metric
     * asserts — and [EventImportService] treats it that way for `status` already.
     */
    val lastSuccessAt: Instant? = null,
    /**
     * Number of events the source last published, as counted by the run that last read its listing.
     *
     * **A 304 carries this forward rather than resetting it (#659).** "Not modified" means the
     * listing still holds what it held, so zeroing the column would report an emptied source on the
     * one answer that proves it is unchanged — `loge` read `lastEventCount = 0` on a successful run
     * while the page had six events on it. This is the column an operator reaches for first, and
     * ADR-015 criterion 1 is the alert written against exactly this class of silence.
     */
    val lastEventCount: Int? = null,
    /** Error message from the last failed import, `null` if the last run succeeded. */
    val lastError: String? = null,
    /** Current import status: IDLE, RUNNING, SUCCESS, FAILED, MISCONFIGURED. */
    val status: String = ImportStatus.IDLE.name,
    /**
     * When a run last found materially less of some field than this source normally publishes
     * (#472), or `null` once a later run looked normal again.
     *
     * **Deliberately independent of [status].** A flagged source is one whose every run *succeeds* —
     * that is the entire failure being caught: the importer keeps working, reports success, writes
     * the usual number of events, and the data quietly gets worse. Folding this into `FAILED` would
     * make the scheduler back it off, which is precisely the wrong response to a venue that is
     * answering perfectly.
     */
    val flaggedAt: Instant? = null,
    /** Which fields dropped and by how much, in the shape a human reads without another query. */
    val flagReason: String? = null,
    /** Optimistic locking version — prevents lost updates from concurrent modifications. */
    @Version val version: Long? = null,
    @CreatedDate val createdAt: Instant? = null,
    @LastModifiedDate val updatedAt: Instant? = null
) {
    companion object {
        /** Default import interval: once per day (24 hours). */
        const val DEFAULT_IMPORT_INTERVAL_MINUTES = 1440

        /** Default maximum number of retry attempts after failure. */
        const val DEFAULT_MAX_RETRIES = 3
    }
}

/**
 * Import source lifecycle status.
 *
 * The [S_IDLE], [S_RUNNING], etc. compile-time constants mirror the enum names
 * for use in `@Query` SQL strings where `ImportStatus.RUNNING.name` cannot be used
 * (annotation values require compile-time constants, and `.name` is a runtime property).
 */
enum class ImportStatus {
    /** No import running, initial state. */
    IDLE,

    /** Import is currently in progress. */
    RUNNING,

    /** Last import completed successfully. */
    SUCCESS,

    /** Last import failed with a transient error (e.g. network timeout, parse failure). Eligible for retry with backoff. */
    FAILED,

    /**
     * Source has a configuration error that will never self-resolve on retry
     * (e.g. unknown source type, no importer registered). Requires manual intervention.
     * The scheduler skips misconfigured sources entirely — they do not consume retry budget.
     */
    MISCONFIGURED;

    companion object {
        // Compile-time constants for use in @Query SQL annotations.
        // Keep in sync with enum values — verified by ImportStatusConstantsTest.
        const val S_IDLE = "IDLE"
        const val S_RUNNING = "RUNNING"
        const val S_SUCCESS = "SUCCESS"
        const val S_FAILED = "FAILED"
        const val S_MISCONFIGURED = "MISCONFIGURED"
    }
}
