package de.norm.events.scraper

import de.norm.events.event.ArtistRole
import de.norm.events.event.DescriptionLanguage
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
import java.time.LocalDateTime
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
    /**
     * Last day of the event, when the venue states one (ADR-029). Null for the usual single night.
     * A scraper that has an end time but no end date derives this with [endOn].
     */
    val endDate: LocalDate? = null,
    /** Time the event ends on [endDate], when the venue states one. Requires [endDate]. */
    val endTime: LocalTime? = null,
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
     * The venue's own words about the status, where they are neither the title nor the
     * description — a change note, a badge, or the raw title before [cleanEventTitle] strips
     * the "verlegt ins …" tail. Never stored; [toEventEntity] reads the destination of a move
     * out of it (#1551).
     */
    val statusNote: String? = null,
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
    val promoters: List<String> = emptyList(),
    /**
     * The website a venue links each promoter credit to, keyed by the raw name in [promoters].
     * Fills `promoter.website_url` where the row has none; a row that has one keeps it (#1319).
     */
    val promoterWebsites: Map<String, String> = emptyMap()
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
        // A venue with no status badge writes the cancellation into the title (#1493). The title
        // decides only where the scraper found nothing, and a marker glued to a name comes off.
        val badge = EventStatus.parseOrDefault(status)
        val badgeStatus = if (badge == EventStatus.SCHEDULED) parseTitleStatus(title) ?: badge.name else badge.name
        // A "verlegt" badge sits on both ends of a move; which end this row is depends on the
        // venue, which only this boundary knows (#1551).
        val relocation = listOfNotNull(statusNote, title, subtitle, description).firstNotNullOfOrNull(::parseRelocation)
        val (storedStatus, relocatedTo) = resolveRelocation(badgeStatus, relocation, venueSlug)
        val storedTitle = stripTitleStatusMarker(title)
        val storedDescription = if (licences.withholdsDescription()) null else description
        val detected = DescriptionLanguage.detect(storedDescription)
        // The second-language text is not scraped, it is derived from the description after the
        // import commits. Rebuilding it as null here made every translated row "changed", wiped the
        // translation on save, and bought it again the same night — the whole catalogue, daily
        // (#1301). It survives exactly as long as the text it was made from does.
        val alt = existing?.takeIf { storedDescription != null && it.description == storedDescription }
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
            title = storedTitle,
            subtitle = subtitle,
            // A source that forbids its prose gets none of it stored, not merely hidden (#807).
            // Blanking on read would leave the § 16 reproduction in place, and this is the only
            // point every import passes through.
            description = storedDescription,
            // The page tells a reader and a crawler which language the text is in, so a German
            // description under English chrome is marked rather than mislabelled (ADR-026).
            descriptionLanguage = detected?.language?.code,
            descriptionLanguageConfidence = detected?.confidence,
            descriptionAlt = alt?.descriptionAlt,
            descriptionAltLanguage = alt?.descriptionAltLanguage,
            descriptionAltOrigin = alt?.descriptionAltOrigin,
            descriptionAltEngine = alt?.descriptionAltEngine,
            descriptionAltSourceHash = alt?.descriptionAltSourceHash,
            // Fall back to OTHER (not CONCERT) when the source provided no category,
            // so unclassifiable events aren't silently labelled as concerts; then
            // promote an under-classified festival title (a "Konzert"-labelled festival
            // day, or a category-less "… Festival") to FESTIVAL, or recover a
            // reading/exhibition/screening a venue filed under the genre field.
            eventType = resolveEventType(eventType, storedTitle, genre).name,
            status = storedStatus,
            relocatedTo = relocatedTo,
            slug = SlugGenerator.slugify(listOfNotNull(eventDate, venueSlug, storedTitle, slugDiscriminator).joinToString("-")),
            eventDate = eventDate,
            doorsTime = doors,
            startTime = start,
            endDate = endDate,
            // The database refuses a time without a date, so a scraper's slip surfaces here, not as a
            // constraint violation halfway through a bulk save.
            endTime = endTime?.also { requireNotNull(endDate) { "endTime without endDate on $sourceId" } },
            imageUrl = if (licences.withholdsImage()) null else imageUrl,
            sourceUrl = sourceUrl,
            ticketUrl = ticketUrl,
            genre = genre,
            pricePresale = pricePresale?.normalizeMoneyScale(),
            priceBoxOffice = priceBoxOffice?.normalizeMoneyScale(),
            priceNote = priceNote,
            soldOut = soldOut,
            // Honour an explicit scraper flag, otherwise derive from prices/note/title.
            free = free || detectFree(pricePresale, priceBoxOffice, priceNote, storedTitle)
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
 * Returns the events that are not over, passing the number dropped to [onDropped].
 *
 * An event is over after its `endDate`, else after its date (ADR-029), and today counts as not
 * over because the show may still be happening. Before 06:00 on [clock], last night's event with
 * no stated end is not over either when it started at 22:00 or later, or names no start at all
 * (#299) — the BFF applies the same grace with the assumed slot, and keeping one row too many
 * here costs nothing. [EventUpsertService] is the source of truth — a scraper applies the same
 * cutoff earlier only to spare a detail-page fetch. The callback keeps the log statement at the
 * call site, so each caller logs under its own logger and names its own source.
 */
fun List<ScrapedEvent>.dropPastEvents(
    clock: Clock,
    onDropped: (Int) -> Unit
): List<ScrapedEvent> {
    val now = LocalDateTime.now(clock)
    val today = now.toLocalDate()
    val graceNight = today.minusDays(1).takeIf { now.toLocalTime() < GRACE_ENDS }
    val (upcoming, past) =
        partition {
            !(it.endDate ?: it.eventDate).isBefore(today) ||
                (it.endDate == null && it.eventDate == graceNight && (it.startTime == null || it.startTime >= LATE_START))
        }
    if (past.isNotEmpty()) onDropped(past.size)
    return upcoming
}

/**
 * Folds the days of an exhibition into one run (ADR-029, #337).
 *
 * A gallery lists a show once per day it is open, and the page linked from every one of those
 * days is the same. [key] names that page as the run's `sourceId` — or null for a row that is
 * not one day of a run — and every `EXHIBITION` row sharing a key becomes one event: the earliest
 * day's row, dated from the first listed day to the last, identified by the key alone. A row that
 * already carries a span from its page widens the fold to it. A single listed day stays a single
 * day with no end, and every other kind of event passes through untouched, in its place: a
 * festival's days differ in lineup and are not folded.
 */
fun List<ScrapedEvent>.collapseExhibitionRuns(key: (ScrapedEvent) -> String?): List<ScrapedEvent> {
    val runKey = { event: ScrapedEvent -> key(event)?.takeIf { event.eventType == EventType.EXHIBITION.name } }
    val runs = mapNotNull { event -> runKey(event)?.let { it to event } }.groupBy({ it.first }, { it.second })
    val folded = mutableSetOf<String>()
    return mapNotNull { event ->
        val k = runKey(event) ?: return@mapNotNull event
        if (!folded.add(k)) return@mapNotNull null
        val days = runs.getValue(k).sortedBy { it.eventDate }
        val opening = days.first().eventDate
        val closing = days.maxOf { it.endDate ?: it.eventDate }
        days.first().copy(
            sourceId = k,
            eventDate = opening,
            endDate = closing.takeIf { it > opening },
            endTime = null
        )
    }
}

/** A start at or after this is a night, and gets the grace (#299). */
private val LATE_START: LocalTime = LocalTime.of(22, 0)

/** When last night is over for the importer (#299). The BFF and the frontend share the hour. */
private val GRACE_ENDS: LocalTime = LocalTime.of(6, 0)

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
