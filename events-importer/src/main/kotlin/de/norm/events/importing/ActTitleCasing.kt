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
        val candidates =
            events
                .mapNotNull { event ->
                    val title = event.storedTitle()
                    if (!title.isCapitals()) return@mapNotNull null
                    val slugs = billedNames(event).filter { it.equals(title, ignoreCase = true) }.map { it to artistSlugFor(it, event.sourceId) }
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
     * promoter is left out: the import holds it back unless MusicBrainz vouches for it (#1772).
     */
    private fun billedNames(event: ScrapedEvent): List<String> {
        val promoterSlugs = event.promoters.map(::artistSlugFor).toSet()
        return strippedArtists(event)
            .filterNot { it.titleDerived && artistSlugFor(it.name) in promoterSlugs }
            .map { it.name }
    }
}
