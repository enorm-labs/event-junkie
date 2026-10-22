package de.norm.events.enrichment

import de.norm.events.artist.ArtistDiscogsRepository
import de.norm.events.artist.ArtistEntity
import de.norm.events.discogs.DiscogsClient
import de.norm.events.discogs.DiscogsMatcher
import de.norm.events.discogs.DiscogsProperties
import de.norm.events.discogs.DiscogsUnavailableException
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service
import java.time.Year
import java.time.ZoneOffset
import kotlin.time.TimeMark

/**
 * Asks Discogs about every artist MusicBrainz does not know, and stores the verdict (#2026, ADR-035).
 *
 * **Only a row whose MusicBrainz verdict is `NONE`.** MusicBrainz stays the hub (ADR-031). An EXACT
 * row gets its Discogs link from MusicBrainz's own relationships, and Discogs cannot settle an
 * AMBIGUOUS one, because its search returns no country to break the tie. So this runs last on
 * [ArtistLookupSweep]'s tick, after the MusicBrainz lookup, and for the same reasons: a verdict is
 * derived, and Discogs being slow or down must not fail a scrape.
 *
 * **The same queue as MusicBrainz's.** The touched rows first, then the oldest unasked `NONE` rows
 * up to [DiscogsProperties.maxPerRun]. `importer.discogs.unchecked` shows it draining.
 *
 * **An EXACT match needs a recent release** (#2054). Its newest release year is read, compared
 * with [DiscogsProperties.recentReleaseYears] and dropped. `state="inactive"` counts the rows it
 * turns to AMBIGUOUS.
 *
 * **An id, a verdict and an empty link filled — nothing else.** Discogs' API terms forbid storing
 * its content longer than a service needs it and showing it more than six hours stale. The name and
 * the MusicBrainz verdict are never rewritten from a Discogs verdict.
 */
@Service
class DiscogsLookupService(
    private val artistRepository: ArtistDiscogsRepository,
    private val client: DiscogsClient,
    private val properties: DiscogsProperties,
    private val metrics: EnrichmentMetrics
) {
    private val logger = KotlinLogging.logger {}

    /** Says once, at start-up, why the sweep will send nothing. A switched-off lookup is a decision, not a fault. */
    @PostConstruct
    fun reportInactive() {
        if (properties.enabled && !properties.active) {
            logger.info { "Discogs lookup is off: app.discogs.consumer-key or consumer-secret is not set" }
        }
    }

    /**
     * Looks up what this tick owes: the touched `NONE` rows without a current Discogs verdict, then
     * the backfill.
     *
     * @return what the tick owed and stored, or [LookupPass.OFF] without both consumer credentials.
     */
    suspend fun sweep(
        touchedArtistIds: Set<Long>,
        deadline: TimeMark = LookupPass.noDeadline()
    ): LookupPass {
        if (!properties.active) return LookupPass.OFF
        val candidates =
            artistCandidateSlice(
                touchedArtistIds,
                limit = properties.maxPerRun,
                touchedRows = artistRepository::findNeedingDiscogsLookup,
                backfillRows = artistRepository::findUncheckedByDiscogs
            )
        return LookupPass.drain("Discogs lookup", candidates, deadline) { artist ->
            try {
                lookup(artist)
                LookupPass.Row.Stored
            } catch (e: DiscogsUnavailableException) {
                metrics.recordDiscogsLookup(STATE_ERROR)
                LookupPass.Row.Failed(e)
            }
        }
    }

    /**
     * The `NONE` rows Discogs has not been asked about, for `importer.discogs.unchecked`. Zero while the
     * lookup is off: a switched-off lookup owes nothing, and every `NONE` row would read as a backlog
     * that `ej-discogs-backlog-stuck` fires on (#2043).
     */
    suspend fun backlog(): Long = if (properties.active) artistRepository.countUncheckedByDiscogs() else 0

    /** The search, then for an EXACT match its newest release (rule 5, #2054): one more request for about one row in six. */
    private suspend fun lookup(artist: ArtistEntity) {
        val id = requireNotNull(artist.id) { "A candidate row is persisted" }
        val found = DiscogsMatcher.decide(artist.name, client.search(artist.name))
        val newestRelease = found.discogsId?.let { client.newestReleaseYear(it) }
        val earliestYear = Year.now(ZoneOffset.UTC).value - properties.recentReleaseYears
        val verdict = DiscogsMatcher.confirmRecent(found, newestRelease, earliestYear)
        if (verdict != found) {
            logger.info {
                "Discogs artist ${found.discogsId} for '${artist.name}' last released in ${newestRelease ?: "no dated year"}, " +
                    "before $earliestYear; stored AMBIGUOUS"
            }
            metrics.recordDiscogsLookup(STATE_INACTIVE)
        } else {
            metrics.recordDiscogsLookup(verdict.match.name.lowercase())
        }
        artistRepository.storeDiscogsVerdict(id, verdict.match.name, verdict.discogsId, verdict.discogsUrl)
    }

    private companion object {
        const val STATE_ERROR = "error"
        const val STATE_INACTIVE = "inactive"
    }
}
