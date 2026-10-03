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
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

private val logger = KotlinLogging.logger {}

/**
 * Intermediate representation of a scraped event: the fields of
 * [de.norm.events.event.EventEntity] in simple types, with artist and promoter names as raw
 * strings the service layer resolves.
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
    val doorsTime: LocalTime? = null,
    val startTime: LocalTime? = null,
    /**
     * Last day of the event, when the venue states one (ADR-029). A scraper with an end time but
     * no end date derives this with [endOn].
     */
    val endDate: LocalDate? = null,
    /** Time the event ends on [endDate], when the venue states one. Requires [endDate]. */
    val endTime: LocalTime? = null,
    /**
     * The room the whole event is in, as the venue names it ("Saal", "Kuppelhalle"). A lineup
     * split across rooms leaves this null and sets [ScrapedArtist.stage] per act instead; one
     * event never uses both (#316).
     */
    val room: String? = null,
    /** URL of the event's poster or flyer image. */
    val imageUrl: String? = null,
    val sourceUrl: String,
    /**
     * The page the [artists] were read from when that is not [sourceUrl]. The event page credits it
     * (ADR-036).
     */
    val lineupSourceUrl: String? = null,
    /**
     * Unique identifier from the import source, for idempotent upserts:
     * `"<source-slug>:<event-identifier>"`, e.g. `"privatclub:2026-06-12-the-adicts"`.
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
     * Whether the event is free. When left false, [toEventEntity] still derives it via [detectFree].
     */
    val free: Boolean = false,
    /** Scheduling status (e.g. "SCHEDULED", "CANCELLED", "POSTPONED", "RELOCATED"). */
    val status: String = "SCHEDULED",
    /**
     * The venue's own words about the status, where they are neither the title nor the description:
     * a change note, a badge, or the raw title before [cleanEventTitle] strips the "verlegt ins …"
     * tail. Never stored; [toEventEntity] reads the destination of a move out of it (#1551).
     */
    val statusNote: String? = null,
    /**
     * Raw artist names with their role ("HEADLINER", "SUPPORT", "DJ"), resolved by the service layer.
     */
    val artists: List<ScrapedArtist> = emptyList(),
    /**
     * Raw promoter names, resolved and auto-created by the service layer.
     */
    val promoters: List<String> = emptyList(),
    /**
     * The website a venue links each promoter credit to, keyed by the raw name in [promoters]. Fills
     * `promoter.website_url` where the row has none (#1319).
     */
    val promoterWebsites: Map<String, String> = emptyMap(),
    /**
     * The event's detail page yielded nothing this run, so this row holds the listing's fields alone.
     * Never stored: the upsert keeps what the stored row holds where this row has nothing (#2421).
     */
    val detailUnavailable: Boolean = false
) {
    /**
     * This event with every gap filled from [fallback]: a null field takes the fallback's value, the
     * [UNRESOLVED_EVENT_DATE] sentinel takes its date, an empty list or map takes its entries, a
     * `SCHEDULED` status takes its status, and sold-out or free holds when either says so. A field
     * set here is never replaced. The default merge of a detail page (this) with its listing row
     * ([AbstractTwoPageWebsiteImporter.fillGapsFromOverview]), so a field a scraper adds later is
     * kept without naming it in every merge (#1408). A venue whose listing is authoritative for a
     * field overrides it with `copy` after the merge.
     */
    fun withGapsFrom(fallback: ScrapedEvent): ScrapedEvent = withScheduleAndTextGapsFrom(fallback).withTicketAndLineupGapsFrom(fallback)

    /** [withGapsFrom] for the text, the date and times, and the links. */
    private fun withScheduleAndTextGapsFrom(fallback: ScrapedEvent): ScrapedEvent =
        copy(
            subtitle = subtitle ?: fallback.subtitle,
            description = description ?: fallback.description,
            eventType = eventType ?: fallback.eventType,
            eventDate = eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            doorsTime = doorsTime ?: fallback.doorsTime,
            startTime = startTime ?: fallback.startTime,
            endDate = endDate ?: fallback.endDate,
            endTime = endTime ?: fallback.endTime,
            room = room ?: fallback.room,
            imageUrl = imageUrl ?: fallback.imageUrl,
            lineupSourceUrl = lineupSourceUrl ?: fallback.lineupSourceUrl,
            ticketUrl = ticketUrl ?: fallback.ticketUrl,
            genre = genre ?: fallback.genre
        )

    /** [withGapsFrom] for the prices, the flags, the status, the lineup and the promoters. */
    private fun withTicketAndLineupGapsFrom(fallback: ScrapedEvent): ScrapedEvent =
        copy(
            pricePresale = pricePresale ?: fallback.pricePresale,
            priceBoxOffice = priceBoxOffice ?: fallback.priceBoxOffice,
            priceNote = priceNote ?: fallback.priceNote,
            soldOut = soldOut || fallback.soldOut,
            free = free || fallback.free,
            status = status.takeUnless { it == EventStatus.SCHEDULED.name } ?: fallback.status,
            statusNote = statusNote ?: fallback.statusNote,
            artists = artists.ifEmpty { fallback.artists },
            promoters = promoters.ifEmpty { fallback.promoters },
            promoterWebsites = promoterWebsites.ifEmpty { fallback.promoterWebsites }
        )

    /**
     * This event with every empty field the detail page fills taken from [stored], for a row whose
     * detail page yielded nothing ([detailUnavailable]). A transient redirect would otherwise blank
     * the start time and image the last run stored (#2421). The end date and time move as a pair,
     * because the database refuses a time without a date.
     */
    fun withGapsFromStored(stored: EventEntity): ScrapedEvent = withScheduleGapsFromStored(stored).withLinkAndPriceGapsFromStored(stored)

    /** [withGapsFromStored] for the text, the times and the room. */
    private fun withScheduleGapsFromStored(stored: EventEntity): ScrapedEvent {
        val (end, endAt) = if (endDate == null) stored.endDate to stored.endTime else endDate to endTime
        return copy(
            subtitle = subtitle ?: stored.subtitle,
            description = description ?: stored.description,
            doorsTime = doorsTime ?: stored.doorsTime,
            startTime = startTime ?: stored.startTime,
            endDate = end,
            endTime = endAt,
            room = room ?: stored.room
        )
    }

    /** [withGapsFromStored] for the image, the links, the genre and the prices. */
    private fun withLinkAndPriceGapsFromStored(stored: EventEntity): ScrapedEvent =
        copy(
            imageUrl = imageUrl ?: stored.imageUrl,
            lineupSourceUrl = lineupSourceUrl ?: stored.lineupSourceUrl,
            ticketUrl = ticketUrl ?: stored.ticketUrl,
            genre = genre ?: stored.genre,
            pricePresale = pricePresale ?: stored.pricePresale,
            priceBoxOffice = priceBoxOffice ?: stored.priceBoxOffice,
            priceNote = priceNote ?: stored.priceNote
        )

    /**
     * The title as stored: cleaned by [cleanEventTitle] for every source, then a status note or marker
     * removed once [toEventEntity] has read the status off it (#2008). Dedup and slug collisions key
     * on this too, so they agree with the stored slug. A scraper that builds `sourceId` or artists
     * from the title still cleans it first, so that identity does not depend on this step.
     */
    fun storedTitle(): String = stripTitleStatusMarker(cleanEventTitle(title))

    /**
     * The type the event is stored with — the source's own, or the festival/format override
     * [resolveEventType] applies at the boundary. Scrapers build their artists before this is
     * known; [AssociationSyncService] reads it to drop a headliner minted from a festival title (#300).
     */
    fun resolvedEventType(): EventType = resolveEventType(eventType, storedTitle(), genre)

    /**
     * Converts this scraped event into an [EventEntity]; pure, no I/O. The slug is regenerated from
     * the event date, venue slug and title, plus [slugDiscriminator]. On updates [existing]'s `id`,
     * `sourceId` and `createdAt` are preserved.
     *
     * @param venueSlug the venue's slug, in the event slug for cross-venue uniqueness.
     * @param slugDiscriminator appended to separate two sittings of one production on one day. Only
     * the whole scrape can see a collision, so
     * [EventUpsertService][de.norm.events.scraper.EventUpsertService] computes it. Null for the
     * overwhelming majority.
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
        // Doors ≤ start: a source listing them the wrong way round (SO36's "Einlass: 19:30, Beginn:
        // 19:00") has transposed the labels.
        val (doors, start) = orderDoorsBeforeStart(doorsTime, startTime)
        // A venue with no status badge writes the cancellation into the title (#1493); the title decides
        // only where the scraper found nothing.
        val badge = EventStatus.parseOrDefault(status)
        val badgeStatus = if (badge == EventStatus.SCHEDULED) parseTitleStatus(title) ?: badge.name else badge.name
        // A "verlegt" badge sits on both ends of a move; which end this row is, only this boundary knows
        // (#1551).
        val notes = listOfNotNull(statusNote, title, subtitle, description)
        val relocation = notes.firstNotNullOfOrNull(::parseRelocation)
        val (relocatedStatus, relocatedTo) = resolveRelocation(badgeStatus, relocation, venueSlug)
        // A "verschoben" note sits on both dates of a move too; the row the show moved to takes place (#2206).
        val storedStatus = resolvePostponement(relocatedStatus, notes, eventDate)
        val storedTitle = storedTitle()
        // Presale dearer than the door is a misread price (#1583); named so the nightly log says which
        // source, stored as read because the fix is per parser.
        if (presaleAboveDoor(pricePresale, priceBoxOffice)) {
            logger.at(Level.WARN) {
                message = "Presale $pricePresale above box office $priceBoxOffice on '$title'"
                payload = mapOf(LogFields.EVENT_SOURCE_ID to sourceId)
            }
        }
        val storedDescription = if (licences.withholdsDescription()) null else description
        val detected = DescriptionLanguage.detect(storedDescription)
        // The second-language text is derived after the import commits, not scraped. Rebuilding it as
        // null made every translated row "changed", wiped the translation and bought it again the same
        // night, the whole catalogue daily (#1301).
        val alt = existing?.takeIf { storedDescription != null && it.description == storedDescription }
        // priceCurrency omitted: every scraped venue is in Berlin, and EventEntity defaults to "EUR".
        return EventEntity(
            // `sourceId` is the immutable identity key matching scraped events to persisted rows.
            id = existing?.id,
            sourceId = existing?.sourceId ?: sourceId,
            createdAt = existing?.createdAt,
            venueId = venueId,
            room = room,
            eventSourceId = eventSourceId,
            title = storedTitle,
            subtitle = subtitle,
            // A source that forbids its prose gets none of it stored, not merely hidden (#807): blanking on
            // read would leave the § 16 reproduction in place.
            description = storedDescription,
            descriptionWithheld = licences.withholdsDescription() && !description.isNullOrBlank(),
            // The page tells a reader and a crawler which language the text is in (ADR-026).
            descriptionLanguage = detected?.language?.code,
            descriptionLanguageConfidence = detected?.confidence,
            descriptionAlt = alt?.descriptionAlt,
            descriptionAltLanguage = alt?.descriptionAltLanguage,
            descriptionAltOrigin = alt?.descriptionAltOrigin,
            descriptionAltEngine = alt?.descriptionAltEngine,
            descriptionAltSourceHash = alt?.descriptionAltSourceHash,
            // OTHER, not CONCERT, when the source provided no category; then promote an under-classified
            // festival title to FESTIVAL, or recover a reading/exhibition/screening filed under the genre
            // field.
            eventType = resolvedEventType().name,
            status = storedStatus,
            relocatedTo = relocatedTo,
            slug = SlugGenerator.slugify(listOfNotNull(eventDate, venueSlug, storedTitle, slugDiscriminator).joinToString("-")),
            eventDate = eventDate,
            doorsTime = doors,
            startTime = start,
            endDate = endDate,
            // The database refuses a time without a date, so a scraper's slip surfaces here, not halfway
            // through a bulk save.
            endTime = endTime?.also { requireNotNull(endDate) { "endTime without endDate on $sourceId" } },
            imageUrl = if (licences.withholdsImage()) null else imageUrl,
            // What the licence took out is recorded as a fact, so the page can say so (#2130).
            imageWithheld = licences.withholdsImage() && !imageUrl.isNullOrBlank(),
            sourceUrl = sourceUrl,
            lineupSourceUrl = lineupSourceUrl,
            ticketUrl = ticketUrl,
            genre = genre,
            pricePresale = pricePresale?.normalizeMoneyScale(),
            priceBoxOffice = priceBoxOffice?.normalizeMoneyScale(),
            priceNote = priceNote,
            // A marker in the title or subtitle is the venue's word, whether or not its scraper reads it (#2103).
            soldOut = soldOut || hasSoldOutMarker(title) || hasSoldOutMarker(subtitle),
            // Honour an explicit scraper flag, otherwise derive from prices/note/title.
            free = free || detectFree(pricePresale, priceBoxOffice, priceNote, storedTitle)
        )
    }
}

/**
 * Resolves the stored [EventType] from [rawType], [title] and raw [genre]. The source's own
 * category wins, `OTHER` when none. Two overrides apply only to an under-classified
 * `CONCERT`/`OTHER`: a title that unambiguously names a festival ([isFestivalTitle]), recovering
 * festival days mislabelled "Konzert" (Astra) and category-less "… Festival" titles (SO36,
 * Privatclub); else a non-musical format cue in the genre field ([classifyByGenreKeyword]),
 * recovering Festsaal's `Lesung` and Cassiopeia's `Immersive Ausstellung`. The title signal
 * takes precedence over the noisier genre field.
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
 * Returns the events that are not over, passing the number dropped to [onDropped]. An event is
 * over after its `endDate`, else after its date (ADR-029), and today counts as not over. Before
 * 06:00 on [clock], last night's event with no stated end is not over either when it started at
 * 22:00 or later, or names no start (#299); the BFF applies the same grace with the assumed
 * slot. [EventUpsertService] is the source of truth; a scraper applies the cutoff earlier only
 * to spare a detail-page fetch. The callback keeps the log at the call site.
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
 * Folds the days of an exhibition into one run (ADR-029, #337): a gallery lists a show once per
 * day, linking the same page. [key] names that page as the run's `sourceId`, or null for a row
 * that is not one day of a run, and every `EXHIBITION` row sharing a key becomes one event dated
 * from the first listed day to the last. A row already carrying a span widens the fold. A single
 * day stays a single day; every other kind of event passes through, since a festival's days
 * differ in lineup.
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
    /**
     * The room or floor the act plays (e.g. "Panorama Bar") when the lineup is split across rooms.
     * An event in one room puts it on [ScrapedEvent.room] instead (#316).
     */
    val stage: String? = null,
    /**
     * The name was read off the event title by [headlinersFromTitle], not a line-up element.
     * Provenance, not a verdict: a touring act on its first Berlin date is title-derived and real
     * (#1145).
     */
    val titleDerived: Boolean = false,
    /** When the set starts, from the venue's running order (#2002). Null where none is published. */
    val setStart: Instant? = null,
    /** When the set ends. Null where the running order gives only starts, or none at all. */
    val setEnd: Instant? = null
) {
    /**
     * Converts this scraped artist into an [EventArtistEntity], parsing [role] with
     * [ArtistRole.HEADLINER] as the fallback.
     *
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
            stage = stage,
            titleDerived = titleDerived,
            setStart = setStart,
            setEnd = setEnd
        )
}
