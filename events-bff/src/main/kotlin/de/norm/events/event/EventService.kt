package de.norm.events.event

import de.norm.events.artist.ArtistEntity
import de.norm.events.artist.ArtistRepository
import de.norm.events.artist.ArtistSummaryResponse
import de.norm.events.common.PageResponse
import de.norm.events.event.EventService.Companion.MAX_CALENDAR_DAYS
import de.norm.events.genretag.EventGenreTagRepository
import de.norm.events.genretag.GenreTagRepository
import de.norm.events.licence.SourceLicences
import de.norm.events.promoter.PromoterRepository
import de.norm.events.promoter.PromoterSummaryResponse
import de.norm.events.sourcelicence.SourceLicenceGate
import de.norm.events.venue.VenueRepository
import de.norm.events.venue.VenueSummaryResponse
import kotlinx.coroutines.flow.toList
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate

/**
 * Read service assembling the public event responses for the frontend.
 *
 * List/calendar/today responses use batch loading to avoid N+1 queries: a page of events is
 * resolved first, then venue, artist, promoter, and genre tag associations are bulk-fetched
 * for the whole page (mirroring the importer's [de.norm.events.event] strategy).
 */
@Service
class EventService(
    private val eventRepository: EventRepository,
    private val eventSearchRepository: EventSearchRepository,
    private val eventArtistRepository: EventArtistRepository,
    private val eventPromoterRepository: EventPromoterRepository,
    private val eventGenreTagRepository: EventGenreTagRepository,
    private val venueRepository: VenueRepository,
    private val artistRepository: ArtistRepository,
    private val promoterRepository: PromoterRepository,
    private val genreTagRepository: GenreTagRepository,
    private val sourceLicenceGate: SourceLicenceGate
) {
    /**
     * Searches events with optional filters and pagination, returning summaries with
     * pagination metadata.
     */
    @Transactional(readOnly = true)
    suspend fun search(
        filter: EventFilter,
        pageable: Pageable
    ): PageResponse<EventSummaryResponse> {
        val page = eventSearchRepository.search(filter, pageable)
        val events = hydrateOrdered(page.ids)
        return PageResponse.of(summariesFor(events), pageable, page.total)
    }

    /** Today's events, ordered by start time — backs the Home page. */
    @Transactional(readOnly = true)
    suspend fun today(): List<EventSummaryResponse> = summariesFor(eventRepository.findByEventDateOrderByStartTime(LocalDate.now()).toList())

    /**
     * Events within an inclusive date range, for the calendar view. [filter] carries the same
     * optional criteria as [search] — the calendar is the search endpoint's other rendering —
     * and its own date range is overridden by [from]/[to], which the view derives from the
     * visible window.
     *
     * @throws ResponseStatusException 400 if the range is inverted or exceeds [MAX_CALENDAR_DAYS].
     */
    @Transactional(readOnly = true)
    suspend fun calendar(
        from: LocalDate,
        to: LocalDate,
        filter: EventFilter = EventFilter()
    ): List<EventSummaryResponse> {
        if (to.isBefore(from)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "'to' must not be before 'from'")
        }
        if (from.plusDays(MAX_CALENDAR_DAYS) <= to) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Date range must not exceed $MAX_CALENDAR_DAYS days")
        }
        val ids = eventSearchRepository.searchAll(filter.copy(from = from, to = to))
        return summariesFor(hydrateOrdered(ids))
    }

    /**
     * Finds a single event by [slug], fully assembled with venue, lineup, promoters, and genre tags.
     *
     * @throws EventNotFoundException if no event with the given slug exists.
     */
    @Transactional(readOnly = true)
    suspend fun findBySlug(slug: String): EventDetailResponse {
        // The detail path does not go through hydrateOrdered, so it applies the gate itself. This is
        // the endpoint that renders the description in full (EventDetailView.vue), which makes it
        // the one that matters most.
        val event =
            eventRepository.findBySlug(slug)?.let { withLicenceApplied(listOf(it)).first() }
                ?: throw EventNotFoundException(slug)
        val eventId = requireNotNull(event.id) { "Persisted event must have an ID" }

        val venue = venueRepository.findById(event.venueId)
        val venueSummary =
            requireNotNull(venue) { "Event $eventId references missing venue ${event.venueId}" }
                .let { VenueSummaryResponse.fromEntity(it) }

        val artistLinks = eventArtistRepository.findByEventId(eventId).toList().sortedBy { it.billingOrder }
        val artistsById = fetchArtists(artistLinks.map { it.artistId })
        val lineup =
            artistLinks.mapNotNull { link ->
                artistsById[link.artistId]?.let { artist ->
                    LineupEntryResponse(
                        artist = ArtistSummaryResponse.fromEntity(artist),
                        role = ArtistRole.parseOrDefault(link.role),
                        billingOrder = link.billingOrder,
                        stage = link.stage
                    )
                }
            }

        val promoterIds = eventPromoterRepository.findByEventId(eventId).toList().map { it.promoterId }
        val promoters =
            if (promoterIds.isEmpty()) {
                emptyList()
            } else {
                promoterRepository.findByIdIn(promoterIds).toList().map { PromoterSummaryResponse.fromEntity(it) }
            }

        val genreTagIds = eventGenreTagRepository.findByEventId(eventId).toList().map { it.genreTagId }
        val genreTags =
            if (genreTagIds.isEmpty()) emptyList() else genreTagRepository.findAllById(genreTagIds).toList().map { it.name }

        return EventDetailResponse.fromEntity(event, venueSummary, lineup, promoters, genreTags)
    }

    /**
     * Re-fetches events by ID via the CRUD repository, preserving the order of [ids].
     *
     * **Also applies the licence gate**, which is why every list path goes through here rather than
     * mapping repository output directly. One choke point for `search`, `today` and `calendar`
     * means a new list endpoint inherits the gate instead of having to remember it.
     */
    private suspend fun hydrateOrdered(ids: List<Long>): List<EventEntity> {
        if (ids.isEmpty()) return emptyList()
        val byId = eventRepository.findAllById(ids).toList().associateBy { it.id }
        return withLicenceApplied(ids.mapNotNull { byId[it] })
    }

    /**
     * Blanks `description` and `imageUrl` on every event whose source prohibits that field.
     *
     * Done on the entity rather than in [EventResponses], so the summary and detail mappers cannot
     * diverge and neither can forget. One query for the whole page, matching the batch-loading
     * strategy the rest of this service uses.
     */
    private suspend fun withLicenceApplied(events: List<EventEntity>): List<EventEntity> {
        if (events.isEmpty()) return events
        val licences = sourceLicenceGate.forSources(events.mapNotNull { it.eventSourceId })
        return events.map { event ->
            val licence = event.eventSourceId?.let { licences[it] } ?: SourceLicences.UNKNOWN_SOURCE
            event.copy(
                description = if (licence.withholdsDescription()) null else event.description,
                imageUrl = if (licence.withholdsImage()) null else event.imageUrl
            )
        }
    }

    /**
     * Maps a page of events to summaries, batch-loading venue, artist, and genre tag
     * associations in a fixed number of queries regardless of page size.
     */
    private suspend fun summariesFor(events: List<EventEntity>): List<EventSummaryResponse> {
        if (events.isEmpty()) return emptyList()
        val eventIds = events.map { requireNotNull(it.id) { "Persisted event must have an ID" } }

        val venuesById = venueRepository.findByIdIn(events.map { it.venueId }.distinct()).toList().associateBy { it.id }

        val artistLinks = eventArtistRepository.findByEventIdIn(eventIds).toList()
        val artistsById = fetchArtists(artistLinks.map { it.artistId })
        val artistLinksByEvent = artistLinks.groupBy { it.eventId }

        val genreLinks = eventGenreTagRepository.findByEventIdIn(eventIds).toList()
        val genreNamesById =
            genreLinks.map { it.genreTagId }.distinct().let { tagIds ->
                if (tagIds.isEmpty()) emptyMap() else genreTagRepository.findAllById(tagIds).toList().associate { it.id!! to it.name }
            }
        val genreLinksByEvent = genreLinks.groupBy { it.eventId }

        return events.map { event ->
            val venue =
                requireNotNull(venuesById[event.venueId]) { "Event ${event.id} references missing venue ${event.venueId}" }
            val artistNames =
                artistLinksByEvent[event.id].orEmpty().sortedBy { it.billingOrder }.mapNotNull { artistsById[it.artistId]?.name }
            val genreTags = genreLinksByEvent[event.id].orEmpty().mapNotNull { genreNamesById[it.genreTagId] }
            EventSummaryResponse.fromEntity(event, VenueSummaryResponse.fromEntity(venue), artistNames, genreTags)
        }
    }

    private suspend fun fetchArtists(artistIds: List<Long>): Map<Long?, ArtistEntity> {
        val distinct = artistIds.distinct()
        return if (distinct.isEmpty()) emptyMap() else artistRepository.findByIdIn(distinct).toList().associateBy { it.id }
    }

    companion object {
        /** Maximum span (inclusive) the calendar endpoint will return in a single request. */
        private const val MAX_CALENDAR_DAYS = 92L
    }
}
