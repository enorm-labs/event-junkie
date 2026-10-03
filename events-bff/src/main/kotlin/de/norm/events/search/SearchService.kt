package de.norm.events.search

import de.norm.events.artist.ArtistService
import de.norm.events.common.PageResponse
import de.norm.events.event.EventFilter
import de.norm.events.event.EventService
import de.norm.events.promoter.PromoterService
import de.norm.events.venue.VenueFilter
import de.norm.events.venue.VenueService
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate

/**
 * Runs each module's own name search for one [term], so the header finds what the list pages find:
 * the same folding, the same typo pass (#2428), the same licence gate on events. Concurrently,
 * because a visitor is waiting on every keystroke; that holds four of the pool's connections at once.
 */
@Service
class SearchService(
    private val eventService: EventService,
    private val venueService: VenueService,
    private val artistService: ArtistService,
    private val promoterService: PromoterService,
    private val clock: Clock
) {
    suspend fun search(
        term: String,
        limit: Int
    ): SearchResponse =
        coroutineScope {
            // `name` puts the closest match first: each repository ranks by similarity under that sort.
            val byName = PageRequest.of(0, limit, Sort.by("name"))
            val events = async { eventService.search(EventFilter(from = LocalDate.now(clock), query = term), PageRequest.of(0, limit, Sort.by("eventDate"))) }
            val venues = async { venueService.list(VenueFilter(query = term), byName) }
            val artists = async { artistService.list(term, byName) }
            val promoters = async { promoterService.list(term, byName) }
            SearchResponse(events.await().group(), venues.await().group(), artists.await().group(), promoters.await().group())
        }

    private fun <T> PageResponse<T>.group() = SearchGroup(content, totalElements)
}
