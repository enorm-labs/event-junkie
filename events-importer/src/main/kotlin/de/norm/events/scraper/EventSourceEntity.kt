package de.norm.events.scraper

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * R2DBC entity for the `event_source` table: which URL to scrape, which [EventImporter] to use,
 * cached conditional-request headers, and the result of the last run.
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
     * When this source's host last had its `robots.txt` read, or `null` while never. The three
     * `robots*` columns are the per-source evidence behind `docs/SCRAPING_POSITION.md` §3.3, and
     * none carries a default: an unchecked source has to be visible as unchecked (#790).
     */
    val robotsCheckedAt: Instant? = null,
    /** Whether [url] itself is permitted by those rules. `null` until the first check. */
    val robotsAllowed: Boolean? = null,
    /** The `robots.txt` that answered, or `null` where the host serves none or could not be reached. */
    val robotsTxtUrl: String? = null,
    /**
     * What is known about republishing this source's descriptions, or `null` while unreviewed. Text
     * rather than the enum so an unrecognised value stays readable; `SourceLicence.parseOrProhibited`
     * reads it, and a CHECK constraint keeps hand-edited rows to the vocabulary (#283).
     */
    val descriptionLicence: String? = null,
    /** The same question for this source's images, answered separately. Agency photographs are common. */
    val imageLicence: String? = null,
    /**
     * Whether this source grants us the right to translate its descriptions, or `null` while
     * unasked. A third answer rather than a reading of [descriptionLicence]: a translation is an
     * adaptation under § 23 UrhG, and only `PERMITTED` allows it (ADR-026, #808).
     */
    val translationLicence: String? = null,
    /**
     * When the two columns above were last reviewed, or `null`. Its own column because null here
     * and null in both status columns stop agreeing once a review is redone, as V005 §robots split.
     */
    val licenceReviewedAt: Instant? = null,
    /** The page the reviewer read. Recorded even for `UNCLEAR`, so nobody repeats the search. */
    val licenceSourceUrl: String? = null,
    /** The sentence that decided it, in the reviewer's words. */
    val licenceNote: String? = null,
    /**
     * Timestamp of the last import that succeeded, which [lastImportAt] is not: that one is written
     * on failure too, so a source broken for a week reports a fresh timestamp there. This is the
     * column `importer.source.last_success` publishes (#415). A 304 counts as a success.
     */
    val lastSuccessAt: Instant? = null,
    /**
     * Number of events the source last published, as counted by the run that last read its listing.
     * A 304 carries this forward rather than resetting it (#659): `loge` read `lastEventCount = 0`
     * on a successful run while the page had six events. The column an operator reaches for first.
     */
    val lastEventCount: Int? = null,
    /** Error message from the last failed import, `null` if the last run succeeded. */
    val lastError: String? = null,
    /** Current import status: IDLE, RUNNING, SUCCESS, FAILED, MISCONFIGURED. */
    val status: String = ImportStatus.IDLE.name,
    /**
     * When a run last found materially less of some field than this source normally publishes
     * (#472), or `null` once a later run looked normal. Independent of [status]: a flagged source is
     * one whose every run succeeds while the data quietly gets worse, and folding it into `FAILED`
     * would make the scheduler back off a venue that is answering perfectly.
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
 * Import source lifecycle status. The [S_IDLE], [S_RUNNING] constants mirror the enum names for
 * `@Query` SQL strings, where `.name` is not a compile-time constant.
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
     * A configuration error that never self-resolves; the scheduler skips these and they consume no
     * retry budget.
     */
    MISCONFIGURED;

    companion object {
        // Compile-time constants for @Query SQL; ImportStatusConstantsTest keeps them in sync.
        const val S_IDLE = "IDLE"
        const val S_RUNNING = "RUNNING"
        const val S_SUCCESS = "SUCCESS"
        const val S_FAILED = "FAILED"
        const val S_MISCONFIGURED = "MISCONFIGURED"
    }
}
