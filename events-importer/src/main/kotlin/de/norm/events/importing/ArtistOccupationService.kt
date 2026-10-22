package de.norm.events.importing

import de.norm.events.artist.ArtistEntity
import de.norm.events.artist.ArtistOccupationRepository
import de.norm.events.enrichment.LookupPass
import de.norm.events.wikimedia.WikimediaClient
import de.norm.events.wikimedia.WikimediaProperties
import de.norm.events.wikimedia.WikimediaUnavailableException
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import kotlin.time.TimeMark

/**
 * Reads the Wikidata occupations of every EXACT artist with a Wikidata link, and stores one fact:
 * whether one of them is comedian, stand-up comedian or cabaret performer (ADR-039).
 *
 * Runs on [ArtistLookupSweep]'s tick after the enrichment, which writes the link. One request per
 * row at the Wikimedia pace, [WikimediaProperties.occupationMaxPerRun] rows a tick. A failure is a
 * retry on the next tick; three in a row, or the tick's deadline, end this tick's pass.
 */
@Service
class ArtistOccupationService(
    private val occupations: ArtistOccupationRepository,
    private val wikimedia: WikimediaClient,
    private val properties: WikimediaProperties
) {
    private val logger = KotlinLogging.logger {}

    /** Reads the unread rows; [LookupPass.OFF] when the Wikimedia reads are off. */
    suspend fun sweep(deadline: TimeMark = LookupPass.noDeadline()): LookupPass {
        if (!properties.enabled) return LookupPass.OFF
        val candidates = occupations.findUnreadOccupations(properties.occupationMaxPerRun).toList()
        var comedians = 0
        val pass =
            LookupPass.drain("Occupation read", candidates, deadline) { artist ->
                try {
                    when (read(artist)) {
                        null -> LookupPass.Row.Stop
                        true -> LookupPass.Row.Stored.also { comedians++ }
                        false -> LookupPass.Row.Stored
                    }
                } catch (e: WikimediaUnavailableException) {
                    LookupPass.Row.Failed(e)
                }
            }
        if (candidates.isNotEmpty()) logger.info { "$comedians comedian(s) among ${pass.stored} occupation(s) read" }
        return pass
    }

    /** Whether the row's act is a comedian, now stored; null when the read is off. */
    private suspend fun read(artist: ArtistEntity): Boolean? {
        val id = requireNotNull(artist.id) { "A candidate row is persisted" }
        val wikidataId = wikidataIdOf(requireNotNull(artist.wikidataUrl) { "A candidate row has a Wikidata link" })
        val held =
            if (wikidataId == null) {
                logger.info { "No Wikidata id in '${artist.wikidataUrl}' of '${artist.name}'; stored as no comedian" }
                emptySet()
            } else {
                wikimedia.occupationsOf(wikidataId) ?: return null
            }
        val comedian = held.any { it in PerformerTyping.COMEDY_OCCUPATIONS }
        occupations.storeComedian(id, comedian)
        return comedian
    }

    private companion object {
        /** The item id at the end of a Wikidata link: `https://www.wikidata.org/wiki/Q76152`. */
        val WIKIDATA_ID = Regex("""/(Q\d+)/?$""")

        fun wikidataIdOf(url: String): String? = WIKIDATA_ID.find(url)?.groupValues?.get(1)
    }
}
