package de.norm.events.event

import de.norm.events.artist.ArtistEntity
import de.norm.events.artist.ArtistRepository
import de.norm.events.artist.ArtistSummaryResponse
import de.norm.events.common.PageResponse
import de.norm.events.event.EventService.Companion.MAX_CALENDAR_DAYS
import de.norm.events.genretag.EventGenreTagRepository
import de.norm.events.genretag.GenreTagEntity
import de.norm.events.genretag.GenreTagRepository
import de.norm.events.image.CachedImageGate
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
@Suppress("LongParameterList") // Constructor injection: one parameter per collaborator; splitting the service hides the wiring.
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
    private val sourceLicenceGate: SourceLicenceGate,
    private val cachedImageGate: CachedImageGate
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
        val licensed =
            eventRepository.findBySlug(slug)?.let { withLicenceApplied(listOf(it)).first() }
                ?: throw EventNotFoundException(slug)
        val event = licensed.event
        val eventId = requireNotNull(event.id) { "Persisted event must have an ID" }

        val venue =
            requireNotNull(venueRepository.findById(event.venueId)) {
                "Event $eventId references missing venue ${event.venueId}"
            }

        val artistLinks = eventArtistRepository.findByEventId(eventId).toList().sortedBy { it.billingOrder }
        val artistsById = fetchArtists(artistLinks.map { it.artistId })

        val promoterIds = eventPromoterRepository.findByEventId(eventId).toList().map { it.promoterId }
        val promoterEntities =
            if (promoterIds.isEmpty()) emptyList() else promoterRepository.findByIdIn(promoterIds).toList()

        val genreTagIds = eventGenreTagRepository.findByEventId(eventId).toList().map { it.genreTagId }
        val genreTags =
            if (genreTagIds.isEmpty()) emptyList() else genreTagRepository.findAllById(genreTagIds).toList().map { it.name }

        // One lookup for every image this response carries, not only the event's. An embedded venue
        // or artist summary holds an `imageUrl` too, and leaving those unrewritten would hand out a
        // venue's own URL from an endpoint that had just stopped doing it for the event (#833).
        val images =
            cachedImageGate.forUrls(
                listOf(event.imageUrl, venue.imageUrl) + artistsById.values.map { it.imageUrl } + promoterEntities.map { it.imageUrl }
            )
        val venueSummary = VenueSummaryResponse.fromEntity(venue, images.serve(venue.imageUrl, CARD_WIDTH))
        val lineup =
            artistLinks.mapNotNull { link ->
                artistsById[link.artistId]?.let { artist ->
                    LineupEntryResponse(
                        artist = ArtistSummaryResponse.fromEntity(artist, images.serve(artist.imageUrl, CARD_WIDTH)),
                        role = ArtistRole.parseOrDefault(link.role),
                        billingOrder = link.billingOrder,
                        stage = link.stage
                    )
                }
            }
        val promoters = promoterEntities.map { PromoterSummaryResponse.fromEntity(it, images.serve(it.imageUrl, CARD_WIDTH)) }

        return EventDetailResponse.fromEntity(
            event,
            venueSummary,
            lineup,
            promoters,
            genreTags,
            images.serve(event.imageUrl, DETAIL_WIDTH),
            descriptionWithheld = licensed.descriptionWithheld,
            imageWithheld = licensed.imageWithheld
        )
    }

    /**
     * Re-fetches events by ID via the CRUD repository, preserving the order of [ids].
     *
     * Ordering only. The licence gate and the image rewrite live in [summariesFor], because `today`
     * does not come through here — it reads the repository directly, and while the gate lived here
     * the Home page was the one list endpoint neither was applied to.
     */
    private suspend fun hydrateOrdered(ids: List<Long>): List<EventEntity> {
        if (ids.isEmpty()) return emptyList()
        val byId = eventRepository.findAllById(ids).toList().associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    /**
     * Blanks `description` and `imageUrl` on every event whose source prohibits that field.
     *
     * Done on the entity rather than in [EventResponses], so the summary and detail mappers cannot
     * diverge and neither can forget. One query for the whole page, matching the batch-loading
     * strategy the rest of this service uses.
     */
    private suspend fun withLicenceApplied(events: List<EventEntity>): List<LicensedEvent> {
        if (events.isEmpty()) return emptyList()
        val licences = sourceLicenceGate.forSources(events.mapNotNull { it.eventSourceId })
        return events.map { event ->
            val licence = event.eventSourceId?.let { licences[it] } ?: SourceLicences.UNKNOWN_SOURCE
            // Withheld means something was taken away, not that the source would have withheld it.
            // A prohibited source with no description had nothing removed, and reporting one would
            // point a reader at a venue page that has nothing more to show (#811).
            val descriptionWithheld = licence.withholdsDescription() && event.description != null
            val imageWithheld = licence.withholdsImage() && event.imageUrl != null
            LicensedEvent(
                event =
                    event.copy(
                        description = if (descriptionWithheld) null else event.description,
                        imageUrl = if (imageWithheld) null else event.imageUrl
                    ),
                descriptionWithheld = descriptionWithheld,
                imageWithheld = imageWithheld
            )
        }
    }

    /**
     * An event as it may be shown, and what the licence took out of it.
     *
     * **The response has to say why a field is absent, because `null` cannot.** A description a
     * venue never wrote and one a prohibition removed are the same `null`, and on a seeded database
     * that is 1,072 against 56 — so a note shown for every `null` would be wrong twenty times more
     * often than right (#811).
     */
    private data class LicensedEvent(
        val event: EventEntity,
        val descriptionWithheld: Boolean,
        val imageWithheld: Boolean
    )

    /**
     * Maps a page of events to summaries, batch-loading venue, artist, and genre tag
     * associations in a fixed number of queries regardless of page size.
     *
     * **This is the choke point every list endpoint goes through**, so the licence gate and the image
     * rewrite are applied here. A new list endpoint inherits both by building its summaries the only
     * way there is, rather than by remembering to call something first.
     *
     * The image step runs after the licence gate and never before it: a source that prohibits its
     * images has had the URL blanked already, so there is nothing left to look up.
     */
    private suspend fun summariesFor(unlicensed: List<EventEntity>): List<EventSummaryResponse> {
        if (unlicensed.isEmpty()) return emptyList()
        val licensed = withLicenceApplied(unlicensed)
        val events = licensed.map { it.event }
        val eventIds = events.map { requireNotNull(it.id) { "Persisted event must have an ID" } }

        val venuesById = venueRepository.findByIdIn(events.map { it.venueId }.distinct()).toList().associateBy { it.id }
        // The embedded venue summary carries an `imageUrl` of its own, so it is looked up here
        // rather than left as the venue's URL on an endpoint that rewrote the event's (#833).
        val images = cachedImageGate.forUrls(events.map { it.imageUrl } + venuesById.values.map { it.imageUrl })

        val artistLinks = eventArtistRepository.findByEventIdIn(eventIds).toList()
        val artistsById = fetchArtists(artistLinks.map { it.artistId })
        val artistLinksByEvent = artistLinks.groupBy { it.eventId }

        val genreLinks = eventGenreTagRepository.findByEventIdIn(eventIds).toList()
        val genreNamesById =
            genreLinks.map { it.genreTagId }.distinct().let { tagIds ->
                if (tagIds.isEmpty()) emptyMap() else genreTagRepository.findAllById(tagIds).toList().associate { it.requiredId() to it.name }
            }
        val genreLinksByEvent = genreLinks.groupBy { it.eventId }

        return licensed.map { licensedEvent ->
            val event = licensedEvent.event
            val venue =
                requireNotNull(venuesById[event.venueId]) { "Event ${event.id} references missing venue ${event.venueId}" }
            val artistNames =
                artistLinksByEvent[event.id].orEmpty().sortedBy { it.billingOrder }.mapNotNull { artistsById[it.artistId]?.name }
            val genreTags = genreLinksByEvent[event.id].orEmpty().mapNotNull { genreNamesById[it.genreTagId] }
            EventSummaryResponse.fromEntity(
                event,
                VenueSummaryResponse.fromEntity(venue, images.serve(venue.imageUrl, CARD_WIDTH)),
                artistNames,
                genreTags,
                images.serve(event.imageUrl, CARD_WIDTH),
                imageWithheld = licensedEvent.imageWithheld
            )
        }
    }

    private suspend fun fetchArtists(artistIds: List<Long>): Map<Long?, ArtistEntity> {
        val distinct = artistIds.distinct()
        return if (distinct.isEmpty()) emptyMap() else artistRepository.findByIdIn(distinct).toList().associateBy { it.id }
    }

    companion object {
        /** Maximum span (inclusive) the calendar endpoint will return in a single request. */
        private const val MAX_CALENDAR_DAYS = 92L

        /**
         * How wide the image is drawn, in CSS pixels, which is what decides the derivatives offered.
         *
         * `EventCard` and `VenueCard` draw at 80 px and `BaseDetailView` at 96 px, so 96 covers the
         * cards. `EventDetailView` draws the image at the full width of a `max-w-3xl` column, 704 px
         * after padding ([#804](https://github.com/enorm-labs/event-junkie/issues/804) is why the
         * detail page is on this list at all).
         *
         * **CSS pixels, not file widths.** The device pixel ratio is the browser's to know, and it
         * picks from the `srcset` this produces; a number here that already had a ratio baked in
         * would multiply it twice.
         */
        private const val CARD_WIDTH = 96
        private const val DETAIL_WIDTH = 704
    }
}

/** The id of a genre tag read back from the database, which is never null once it is persisted. */
private fun GenreTagEntity.requiredId(): Long = requireNotNull(id) { "Persisted genre tag must have an ID" }
