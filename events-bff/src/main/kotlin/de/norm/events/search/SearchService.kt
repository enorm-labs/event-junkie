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
            // The closest match first: artists rank by similarity under `name`, venues and promoters without a sort (#2694).
            val byName = PageRequest.of(0, limit, Sort.by("name"))
            val byRelevance = PageRequest.of(0, limit)
            val byDate = PageRequest.of(0, limit, Sort.by("eventDate"))
            val events = async { eventService.search(EventFilter(from = LocalDate.now(clock), query = term), byDate, COUNT_CAP) }
            val venues = async { venueService.list(VenueFilter(query = term), byRelevance, COUNT_CAP) }
            val artists = async { artistService.list(term, byName, COUNT_CAP) }
            val promoters = async { promoterService.list(term, byRelevance, COUNT_CAP) }
            SearchResponse(events.await().group(), venues.await().group(), artists.await().group(), promoters.await().group())
        }

    private fun <T> PageResponse<T>.group() = SearchGroup(content, minOf(totalElements, COUNT_CAP.toLong()), totalCapped = totalElements > COUNT_CAP)

    companion object {
        /** Past this many matches of one kind the header says "100+"; counting further cost the most (#2533). */
        const val COUNT_CAP = 100
    }
}
