package de.norm.events.importing

import de.norm.events.artist.artistSlugFor
import de.norm.events.artist.canonicalArtistName
import de.norm.events.common.isCapitals
import de.norm.events.scraper.ScrapedEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Title-cases a title the venue sets wholly in capitals when the whole title is one of the event's billed acts
 * (#4425): `CONWAY THE MACHINE` billing `Conway the Machine` stores `Conway the Machine`. The title takes the
 * act's stored artist name, which the de-shouter has already judged with its exceptions, so the event page shows
 * one spelling. Any other title stays as the venue prints it: one that only contains an act (`SPORTS + Support`),
 * one in mixed case, and one whose act is stored in capitals too.
 *
 * The merge applies it as it builds each row, before a pinned title is kept, so a hand edit wins and a re-import
 * writes nothing. Slugs are case-insensitive, so the slug stays.
 */
@Service
class ActTitleCasing(
    private val artistSync: ArtistAssociationSync
) {
    private val logger = KotlinLogging.logger {}

    /** The title-cased title of each of [events] the rule changes, by `sourceId`. */
    suspend fun titles(events: List<ScrapedEvent>): Map<String, String> {
        val capitals = events.filter { it.storedTitle().isCapitals() }
        val promoterNamed = capitals.flatMap(::promoterNamedTitleSlugs).toSet()
        val verified = if (promoterNamed.isEmpty()) emptySet() else artistSync.exactSlugs(promoterNamed)
        val candidates =
            capitals
                .mapNotNull { event ->
                    val title = event.storedTitle()
                    val slugs =
                        billedNames(event, verified)
                            .filter { it.equals(title, ignoreCase = true) }
                            .map { it to artistSlugFor(it, event.sourceId) }
                    if (slugs.isEmpty()) null else event to slugs
                }
        if (candidates.isEmpty()) return emptyMap()
        val stored = artistSync.storedNames(candidates.flatMap { (_, slugs) -> slugs.map { it.second } }.toSet())
        val titles =
            candidates
                .mapNotNull { (event, slugs) ->
                    val title = event.storedTitle()
                    slugs
                        .map { (name, slug) -> stored[slug] ?: canonicalArtistName(name) }
                        .firstOrNull { it.equals(title, ignoreCase = true) && it != title }
                        ?.let { event.sourceId to it }
                }.toMap()
        if (titles.isNotEmpty()) logger.info { "Title-cased ${titles.size} capitals title(s) that name a billed act" }
        return titles
    }

    /**
     * The names [ArtistAssociationSync.billedArtists] bills. A title-derived name that the event credits as its
     * promoter is left out unless its slug is in [verified], the MusicBrainz `EXACT` rows that keep it billed
     * (#1772, #1841, #4983). This logs nothing: [ArtistAssociationSync.billedArtists] logs each held-back name.
     */
    private fun billedNames(
        event: ScrapedEvent,
        verified: Set<String>
    ): List<String> {
        val promoterSlugs = event.promoters.map(::artistSlugFor).toSet()
        return strippedArtists(event)
            .filterNot { it.titleDerived && artistSlugFor(it.name).let { slug -> slug in promoterSlugs && slug !in verified } }
            .map { it.name }
    }

    /** The slugs of [event]'s title-derived acts that are the whole title and its promoter: the ones only an `EXACT` row bills. */
    private fun promoterNamedTitleSlugs(event: ScrapedEvent): List<String> {
        val title = event.storedTitle()
        val promoterSlugs = event.promoters.map(::artistSlugFor).toSet()
        return strippedArtists(event)
            .filter { it.titleDerived && it.name.equals(title, ignoreCase = true) }
            .map { artistSlugFor(it.name) }
            .filter { it in promoterSlugs }
    }
}
