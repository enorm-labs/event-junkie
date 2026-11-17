package de.norm.events.importing

import de.norm.events.artist.ArtistEntity
import de.norm.events.artist.ArtistOccupationRepository
import de.norm.events.enrichment.LookupPass
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
 * Reads the Wikidata occupations of every EXACT artist with a Wikidata link, and stores one fact:
 * whether one of them is comedian, stand-up comedian or cabaret performer (ADR-039).
 *
 * Runs on [ArtistLookupSweep]'s tick after the enrichment, which writes the link. One request per
 * row at the Wikimedia pace, [WikimediaProperties.occupationMaxPerRun] rows a tick. A failure is a
 * retry on the next tick; three in a row, or the tick's deadline, end this tick's pass. A read row is
 * read again after [WikimediaProperties.rereadAfter], so a fix on Wikidata reaches us (#5253).
 */
@Service
class ArtistOccupationService(
    private val occupations: ArtistOccupationRepository,
    private val wikimedia: WikimediaClient,
    private val properties: WikimediaProperties,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = KotlinLogging.logger {}

    /** Reads the unread rows, then the stale ones; [LookupPass.OFF] when the Wikimedia reads are off. */
    suspend fun sweep(deadline: TimeMark = LookupPass.noDeadline()): LookupPass {
        if (!properties.enabled) return LookupPass.OFF
        val candidates = occupations.findOccupationReads(properties.occupationMaxPerRun, staleBefore()).toList()
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
        val id = artist.requiredId()
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

    private fun staleBefore(): Instant = clock.instant().minus(properties.rereadAfter)
}
