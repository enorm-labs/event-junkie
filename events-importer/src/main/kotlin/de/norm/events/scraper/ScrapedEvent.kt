package de.norm.events.scraper

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventArtistEntity
import de.norm.events.event.EventEntity
import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.event.normalizeMoneyScale
import de.norm.events.licence.SourceLicences
import de.norm.events.slug.SlugGenerator
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

/**
 * Intermediate representation of a scraped event before domain mapping.
 *
 * Contains raw data extracted from a venue website, closely matching the
 * fields of [de.norm.events.event.EventEntity] but using simple types.
 * Artist and promoter names are captured as raw strings — the service
 * layer resolves them to database entities (auto-creating if necessary).
 */
data class ScrapedEvent(
    /** Main headline or name of the event. */
    val title: String,
    /** Secondary line, often a tour name or support acts. */
    val subtitle: String? = null,
    /** Longer description or artist biography. */
    val description: String? = null,
    /** Kind of event as categorized by the source (e.g. "CONCERT", "PARTY"). Null means the source provided no category. */
    val eventType: String? = null,
    val eventDate: LocalDate,
    /** Time when doors open to the public. */
    val doorsTime: LocalTime? = null,
    /** Time when the show/performance starts. */
    val startTime: LocalTime? = null,
    /** URL of the event's poster or flyer image. */
    val imageUrl: String? = null,
    val sourceUrl: String,
    /**
     * Unique identifier for this event from the import source.
     * Used for idempotent upserts — format: `"<source-slug>:<event-identifier>"`.
     * Example: `"privatclub:2026-06-12-the-adicts"`.
     */
    val sourceId: String,
    val ticketUrl: String? = null,
    val genre: String? = null,
    /** Presale ticket price (Vorverkauf). */
    val pricePresale: BigDecimal? = null,
    /** Box office ticket price (Abendkasse). */
    val priceBoxOffice: BigDecimal? = null,
    /** Free-form pricing note for non-standard pricing (e.g. "donation 2-5€"). */
    val priceNote: String? = null,
    val soldOut: Boolean = false,
    /**
     * Whether the event is free to attend. Scrapers may set this explicitly; when left false,
     * [toEventEntity] still derives it from the prices and price note via [detectFree].
     */
    val free: Boolean = false,
    /** Scheduling status (e.g. "SCHEDULED", "CANCELLED", "POSTPONED", "RELOCATED"). */
    val status: String = "SCHEDULED",
    /**
     * Raw artist names extracted from the event listing.
     * Each pair contains the artist name and their role (e.g. "HEADLINER", "SUPPORT", "DJ").
     * The service layer resolves these to database artist entities.
     */
    val artists: List<ScrapedArtist> = emptyList(),
    /**
     * Raw promoter names extracted from the event listing.
     * The service layer resolves these to database promoter entities (auto-creating if necessary)
     * and creates event_promoter join table associations.
     */
    val promoters: List<String> = emptyList()
) {
    /**
     * Converts this scraped event into an [EventEntity] for persistence.
     *
     * This is a pure mapping function with no I/O — the caller is responsible for persisting
     * the returned entity. The slug is always regenerated from the event date, venue slug and
     * title — plus [slugDiscriminator] when one is supplied — to ensure uniqueness across venues.
     * On updates, the [existing] entity's `id`, `sourceId`, and `createdAt` are preserved.
     *
     * @param venueId the database ID of the venue this event belongs to.
     * @param venueSlug the URL-friendly slug of the venue, included in the event slug for cross-venue uniqueness.
     * @param eventSourceId the database ID of the event source that imported this event.
     * @param existing the previously persisted entity for updates, or null for new events.
     * @param slugDiscriminator appended to the slug source to separate two sittings of the same
     *   production on the same day (a matinee and an evening show share date, venue and title).
     *   Only the caller can know a collision exists — it takes the whole scrape to see one — so
     *   [EventUpsertService][de.norm.events.scraper.EventUpsertService] computes it and passes it
     *   in. Null for the overwhelming majority of events, which keeps their slug unchanged.
     */
    @Suppress("LongParameterList") // A row-to-response mapper takes one parameter per column it cannot read off the entity.
    fun toEventEntity(
        venueId: Long,
        venueSlug: String,
        eventSourceId: Long,
        existing: EventEntity? = null,
        slugDiscriminator: String? = null,
        licences: SourceLicences = SourceLicences.UNKNOWN_SOURCE
    ): EventEntity {
        // Guard the doors ≤ start invariant: a source that lists them the wrong way round
        // (e.g. SO36's "Einlass: 19:30, Beginn: 19:00") has transposed the labels — swap back.
        val (doors, start) = orderDoorsBeforeStart(doorsTime, startTime)
        // priceCurrency is intentionally omitted — all scraped venues are currently in Berlin
        // (EUR). EventEntity defaults to "EUR". If non-EUR venues are added, introduce a
        // priceCurrency field on ScrapedEvent and pass it through here.
        return EventEntity(
            // Preserve id and sourceId from existing entity on updates; sourceId is the
            // immutable identity key for matching scraped events to persisted rows.
            id = existing?.id,
            sourceId = existing?.sourceId ?: sourceId,
            createdAt = existing?.createdAt,
            venueId = venueId,
            eventSourceId = eventSourceId,
            title = title,
            subtitle = subtitle,
            // A source that forbids its prose gets none of it stored, not merely hidden (#807).
            // Blanking on read would leave the § 16 reproduction in place, and this is the only
            // point every import passes through.
            description = if (licences.withholdsDescription()) null else description,
            // Fall back to OTHER (not CONCERT) when the source provided no category,
            // so unclassifiable events aren't silently labelled as concerts; then
            // promote an under-classified festival title (a "Konzert"-labelled festival
            // day, or a category-less "… Festival") to FESTIVAL, or recover a
            // reading/exhibition/screening a venue filed under the genre field.
            eventType = resolveEventType(eventType, title, genre).name,
            status = EventStatus.parseOrDefault(status).name,
            slug = SlugGenerator.slugify(listOfNotNull(eventDate, venueSlug, title, slugDiscriminator).joinToString("-")),
            eventDate = eventDate,
            doorsTime = doors,
            startTime = start,
            imageUrl = if (licences.withholdsImage()) null else imageUrl,
            sourceUrl = sourceUrl,
            ticketUrl = ticketUrl,
            genre = genre,
            pricePresale = pricePresale?.normalizeMoneyScale(),
            priceBoxOffice = priceBoxOffice?.normalizeMoneyScale(),
            priceNote = priceNote,
            soldOut = soldOut,
            // Honour an explicit scraper flag, otherwise derive from prices/note/title.
            free = free || detectFree(pricePresale, priceBoxOffice, priceNote, title)
        )
    }
}

/**
 * Resolves the stored [EventType] from a scraped [rawType], event [title], and raw
 * [genre] text.
 *
 * The source's own category wins, defaulting to `OTHER` when it provided none. Two
 * overrides apply, but only to an under-classified `CONCERT`/`OTHER` (an explicit
 * `PARTY`/`QUIZ`/`FESTIVAL`/… from the source is trusted and never overridden):
 *  1. a title that unambiguously names a festival ([isFestivalTitle]) → `FESTIVAL`,
 *     recovering festival days a venue mislabelled "Konzert" (Astra) and
 *     category-less "… Festival" titles (SO36, Privatclub);
 *  2. otherwise, a non-musical format cue in the genre field
 *     ([classifyByGenreKeyword]) → the matching `READING`/`EXHIBITION`/`SCREENING`,
 *     recovering a reading/exhibition/screening a venue filed under `genre` while
 *     leaving the title cue-less (Festsaal `Lesung`, Cassiopeia `Immersive
 *     Ausstellung`).
 *
 * The title-based festival signal takes precedence over the noisier genre field.
 */
private fun resolveEventType(
    rawType: String?,
    title: String,
    genre: String?
): EventType {
    val resolved = EventType.parseOrDefault(rawType ?: EventType.OTHER.name)
    if (resolved != EventType.CONCERT && resolved != EventType.OTHER) return resolved
    // Title-based festival signal first (stronger), then a format cue in the noisier genre field.
    val override =
        when {
            isFestivalTitle(title) -> EventType.FESTIVAL
            else -> genre?.let { classifyByGenreKeyword(it) }?.let { EventType.parseOrDefault(it) }
        }
    return override ?: resolved
}

/**
 * Returns the events dated today or later, passing the number dropped to [onDropped].
 *
 * Same-day events are kept because the show may still be happening, and [EventUpsertService]
 * is the source of truth — a scraper applies the same cutoff earlier only to spare a
 * detail-page fetch. The callback keeps the log statement at the call site, so each caller
 * logs under its own logger and names its own source.
 */
fun List<ScrapedEvent>.dropPastEvents(
    clock: Clock,
    onDropped: (Int) -> Unit
): List<ScrapedEvent> {
    val today = LocalDate.now(clock)
    val (upcoming, past) = partition { !it.eventDate.isBefore(today) }
    if (past.isNotEmpty()) onDropped(past.size)
    return upcoming
}

/**
 * A raw artist reference extracted from a scraped event.
 */
data class ScrapedArtist(
    /** Artist or band name as it appears on the website. */
    val name: String,
    /** Role in the lineup (e.g. "HEADLINER", "SUPPORT", "DJ"). Defaults to headliner. */
    val role: String = "HEADLINER",
    /** Room / stage the artist plays at this event (e.g. "Panorama Bar"). Null for single-room venues. */
    val stage: String? = null
) {
    /**
     * Converts this scraped artist into an [EventArtistEntity] join-table entry.
     *
     * Parses the raw [role] string into a known [ArtistRole], falling back to
     * [ArtistRole.HEADLINER] for unrecognized values.
     *
     * @param eventId the database ID of the event this artist is linked to.
     * @param artistId the resolved database ID of the artist.
     * @param billingOrder the position in the lineup (0-based).
     */
    fun toEventArtistEntity(
        eventId: Long,
        artistId: Long,
        billingOrder: Int
    ): EventArtistEntity =
        EventArtistEntity(
            eventId = eventId,
            artistId = artistId,
            role = ArtistRole.parseOrDefault(role).name,
            billingOrder = billingOrder,
            stage = stage
        )
}
