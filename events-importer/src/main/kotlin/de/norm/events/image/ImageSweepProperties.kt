package de.norm.events.image

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * How the orphan sweep decides what to delete, and whether it deletes anything at all.
 *
 * A bucket has no referential integrity, so nothing removes an object when the row that named it
 * goes (ADR-019). This is the mechanism that replaces the lifecycle rule the other two buckets
 * carry, and without it storage grows forever.
 */
@ConfigurationProperties(prefix = "app.images.sweep")
data class ImageSweepProperties(
    /**
     * Whether the sweep deletes, or only reports what it would delete.
     *
     * Off by default, and a disabled pass still runs every query and logs every count. Robots.txt
     * enforcement was rolled out the same way: a deletion rule is worth watching before it is
     * allowed to act, because the cost of a wrong join is the whole cache.
     */
    val enabled: Boolean = false,
    /**
     * How long an object is left alone before it can be called an orphan.
     *
     * [ImageCacheService] stores the bytes and then writes the row, so an object is briefly
     * unreferenced by design. Without this window a sweep landing between the two deletes an image
     * the importer has just fetched.
     */
    val gracePeriod: Duration = Duration.ofDays(1),
    /** How many objects one pass may delete, so a mistake is bounded by a tick rather than a bucket. */
    val maxDeletes: Int = DEFAULT_MAX_DELETES
) {
    companion object {
        private const val DEFAULT_MAX_DELETES = 500
    }
}
