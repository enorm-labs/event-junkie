package de.norm.events.importing

import de.norm.events.artist.ArtistEntity
import de.norm.events.artist.ArtistGenreRepository
import de.norm.events.enrichment.LookupPass
import de.norm.events.genretag.WIKIDATA_GENRES
import de.norm.events.genretag.WIKIDATA_NO_TAG
import de.norm.events.wikimedia.WikimediaClient
import de.norm.events.wikimedia.WikimediaProperties
import de.norm.events.wikimedia.WikimediaUnavailableException
import de.norm.events.wikimedia.wikidataIdOf
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import kotlin.time.TimeMark

/**
 * Reads the Wikidata genres (P136) of every EXACT artist with a Wikidata link, and stores their item ids (ADR-060).
 * The genre derivation maps them onto tags at the next import of an event the act plays.
 *
 * Runs on [ArtistLookupSweep]'s tick after the occupation read, within the same budget. One request per row at the
 * Wikimedia pace, [WikimediaProperties.genreMaxPerRun] rows a tick. A failure is a retry on the next tick. Three in a
 * row, or the deadline, end this tick's pass. A read row is read again after [WikimediaProperties.rereadAfter], so a
 * fix on Wikidata reaches us (#5253).
 */
@Service
class ArtistGenreService(
    private val genres: ArtistGenreRepository,
    private val wikimedia: WikimediaClient,
    private val properties: WikimediaProperties,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = KotlinLogging.logger {}

    /** Reads the unread rows, then the stale ones; [LookupPass.OFF] when the Wikimedia reads are off. */
    suspend fun sweep(deadline: TimeMark = LookupPass.noDeadline()): LookupPass {
        if (!properties.enabled) return LookupPass.OFF
        val candidates = genres.findGenreReads(properties.genreMaxPerRun, staleBefore()).toList()
        var mapped = 0
        val pass =
            LookupPass.drain("Genre read", candidates, deadline) { artist ->
                try {
                    when (read(artist)) {
                        null -> LookupPass.Row.Stop
                        true -> LookupPass.Row.Stored.also { mapped++ }
                        false -> LookupPass.Row.Stored
                    }
                } catch (e: WikimediaUnavailableException) {
                    LookupPass.Row.Failed(e)
                }
            }
        if (candidates.isNotEmpty()) logger.info { "$mapped of ${pass.stored} genre read(s) give a tag" }
        return pass
    }

    /** Whether the row's genres give a tag, now stored; null when the read is off. */
    private suspend fun read(artist: ArtistEntity): Boolean? {
        val id = artist.requiredId()
        val wikidataId = wikidataIdOf(requireNotNull(artist.wikidataUrl) { "A candidate row has a Wikidata link" })
        val held =
            if (wikidataId == null) {
                logger.info { "No Wikidata id in '${artist.wikidataUrl}' of '${artist.name}'; stored with no genre" }
                emptyList()
            } else {
                wikimedia.genresOf(wikidataId) ?: return null
            }
        genres.storeGenres(id, held.toTypedArray())
        val unmapped = held.filterNot { it in WIKIDATA_GENRES || it in WIKIDATA_NO_TAG }
        // `/genre-cleanup` grows the map from these items: an unlisted one gives no tag (ADR-060, #5252).
        if (unmapped.isNotEmpty()) logger.info { "Unmapped Wikidata genre(s) $unmapped of '${artist.name}' ($wikidataId)" }
        return held.any { it in WIKIDATA_GENRES }
    }

    private fun staleBefore(): Instant = clock.instant().minus(properties.rereadAfter)
}
