package de.norm.events.enrichment

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * The meters of the MusicBrainz, Discogs and Wikipedia lookups, and the only place their names are written down.
 * The names follow `docs/ops/PLATFORM_SETUP.md` §7 like those in `ImporterMetrics`, and an alert reads each one.
 * The backlog gauges read an [AtomicLong] that `MetricsRefreshService` updates on a schedule.
 */
@Component
class EnrichmentMetrics(
    private val registry: MeterRegistry
) {
    /** Backs [MUSICBRAINZ_UNCHECKED]: artist rows the MusicBrainz sweep has not looked at yet (#1567). */
    private val musicBrainzUnchecked = AtomicLong(0)

    /** Backs [MUSICBRAINZ_UNENRICHED]: EXACT rows whose entity step C has not read yet (#1568). */
    private val musicBrainzUnenriched = AtomicLong(0)

    /** Backs [DISCOGS_UNCHECKED]: NONE rows the Discogs sweep has not asked about yet (#2026). */
    private val discogsUnchecked = AtomicLong(0)

    init {
        registry.gauge(MUSICBRAINZ_UNCHECKED, musicBrainzUnchecked) { it.get().toDouble() }
        registry.gauge(MUSICBRAINZ_UNENRICHED, musicBrainzUnenriched) { it.get().toDouble() }
        registry.gauge(DISCOGS_UNCHECKED, discogsUnchecked) { it.get().toDouble() }
    }

    /**
     * Counts one MusicBrainz lookup by verdict, `exact`, `ambiguous`, `none`, or `error`. The shares
     * are the number ADR-031 was decided on.
     */
    fun recordMusicBrainzLookup(state: String) {
        registry.counter(MUSICBRAINZ_LOOKUPS, TAG_STATE, state).increment()
    }

    /**
     * Counts one head pass, the part before ` - ` or `: ` of a name MusicBrainz did not know.
     * Reported, never stored (#1145).
     */
    fun recordMusicBrainzHead(state: String) {
        registry.counter(MUSICBRAINZ_HEADS, TAG_STATE, state).increment()
    }

    /** Publishes how many artist rows are still `UNCHECKED`; refreshed by `MetricsRefreshService`. */
    fun updateMusicBrainzUnchecked(count: Long) = musicBrainzUnchecked.set(count)

    /** Counts one column family the enrichment filled: `website`, `bandcamp`, `type`, `image`, … (ADR-031, step C). */
    fun recordMusicBrainzEnriched(field: String) {
        registry.counter(MUSICBRAINZ_ENRICHED, TAG_FIELD, field).increment()
    }

    /** Counts one Commons picture the enrichment refused, by `licence`, `author`, `source`, `mime` or `size`. */
    fun recordMusicBrainzImageRefused(reason: String) {
        registry.counter(MUSICBRAINZ_IMAGE_REFUSED, TAG_REASON, reason).increment()
    }

    /** Counts one ensemble's Wikipedia lead the enrichment did not store, by `birth-data`, `short` or `no-article`. */
    fun recordWikipediaDescriptionRefused(reason: String) {
        registry.counter(WIKIPEDIA_REFUSED, TAG_REASON, reason).increment()
    }

    /** Counts one entity read that could not complete: MusicBrainz or Wikimedia unavailable. */
    fun recordMusicBrainzEnrichmentError() {
        registry.counter(MUSICBRAINZ_ENRICHED, TAG_FIELD, FIELD_ERROR).increment()
    }

    /** Publishes how many EXACT rows still await their entity read; refreshed by `MetricsRefreshService`. */
    fun updateMusicBrainzUnenriched(count: Long) = musicBrainzUnenriched.set(count)

    /** Counts one Discogs lookup by verdict, `exact`, `ambiguous`, `none`, `inactive` (#2054) or `error` (#2026). */
    fun recordDiscogsLookup(state: String) {
        registry.counter(DISCOGS_LOOKUPS, TAG_STATE, state).increment()
    }

    /** Publishes how many NONE rows Discogs has not been asked about; refreshed by `MetricsRefreshService`. */
    fun updateDiscogsUnchecked(count: Long) = discogsUnchecked.set(count)

    companion object {
        /** `importer.musicbrainz.lookups{state}` — one per name looked up. See [recordMusicBrainzLookup]. */
        const val MUSICBRAINZ_LOOKUPS = "importer.musicbrainz.lookups"

        /** `importer.musicbrainz.heads{state}` — the head pass, reported only. See [recordMusicBrainzHead]. */
        const val MUSICBRAINZ_HEADS = "importer.musicbrainz.heads"

        /** `importer.musicbrainz.unchecked` — rows still awaiting a verdict; the backfill draining. */
        const val MUSICBRAINZ_UNCHECKED = "importer.musicbrainz.unchecked"

        /** `importer.musicbrainz.enriched{field}` — one per column family step C filled, `error` for a read that failed. */
        const val MUSICBRAINZ_ENRICHED = "importer.musicbrainz.enriched"

        /** `importer.musicbrainz.image_refused{reason}` — a Commons picture step C would not store. */
        const val MUSICBRAINZ_IMAGE_REFUSED = "importer.musicbrainz.image_refused"
        const val WIKIPEDIA_REFUSED = "importer.wikipedia.refused"

        /** `importer.musicbrainz.unenriched` — EXACT rows awaiting their entity read; step C's backfill draining. */
        const val MUSICBRAINZ_UNENRICHED = "importer.musicbrainz.unenriched"

        /** `importer.discogs.lookups{state}` — one per name looked up. See [recordDiscogsLookup]. */
        const val DISCOGS_LOOKUPS = "importer.discogs.lookups"

        /** `importer.discogs.unchecked` — NONE rows still awaiting a Discogs verdict; the backfill draining. */
        const val DISCOGS_UNCHECKED = "importer.discogs.unchecked"

        const val FIELD_ERROR = "error"
        const val TAG_STATE = "state"
        private const val TAG_FIELD = "field"
        private const val TAG_REASON = "reason"
    }
}
