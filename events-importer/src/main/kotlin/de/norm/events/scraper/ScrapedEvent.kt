package de.norm.events.scraper

import de.norm.events.event.ArtistRole
import de.norm.events.event.DescriptionLanguage
import de.norm.events.event.EventArtistEntity
import de.norm.events.event.EventEntity
import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.event.SpokenLanguage
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
    /**
     * The venue's own text in the other language, where it wrote both. Stored as the `PUBLISHER`
     * second language when its language differs from [description]'s (ADR-026 rule 2, #330).
     */
    val descriptionAlt: String? = null,
    /** Kind of event as categorized by the source (e.g. "CONCERT", "PARTY"). Null means the source provided no category. */
    val eventType: String? = null,
    /**
     * [eventType] is the scraper's default, because the venue gave no cue for this night. A headliner
     * whose stored occupation is comedy then types it `COMEDY` (`PerformerTyping`, #2314).
     */
    val typeIsFallback: Boolean = false,
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
    val detailUnavailable: Boolean = false,
    /**
     * The fields this importer's detail page supplies over the listing's own value, set on a
     * [detailUnavailable] row. Never stored: the upsert keeps the stored value of each, where it
     * would otherwise take the listing's stand-in, a cut title or a thumbnail (#2465, #2505).
     */
    val detailPageOwns: Set<ScrapedField> = emptySet(),
    /**
     * The `sourceId` of the exhibition run this [detailUnavailable] day belongs to if that run is
     * stored. Only the page says EXHIBITION, so the upsert folds the day into a stored run (#2575).
     */
    val storedRunId: String? = null,
    /**
     * The venue's language for all its shows ([VenueLimitations.houseLanguage]), set by the import,
     * not the scraper. Stored only where the event's own text names no language (#2584).
     */
    val houseLanguage: SpokenLanguage? = null
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
    fun withGapsFrom(fallback: ScrapedEvent): ScrapedEvent =
        withScheduleAndTextGapsFrom(fallback)
            .withTicketAndLineupGapsFrom(fallback)
            .withDescriptionGapFrom(fallback)
            .copy(typeIsFallback = if (eventType == null) fallback.typeIsFallback else typeIsFallback)

    /** [withGapsFrom] for the description, which takes its second language with it. */
    private fun withDescriptionGapFrom(fallback: ScrapedEvent): ScrapedEvent =
        if (description != null) this else copy(description = fallback.description, descriptionAlt = fallback.descriptionAlt)

    /** [withGapsFrom] for the subtitle, the date and times, and the links. */
    private fun withScheduleAndTextGapsFrom(fallback: ScrapedEvent): ScrapedEvent =
        copy(
            subtitle = subtitle ?: fallback.subtitle,
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
     * The title as stored: cleaned by [cleanEventTitle] for every source, then a status note or marker
     * removed once [toEventEntity] has read the status off it (#2008). Dedup and slug collisions key
     * on this too, so they agree with the stored slug. A scraper that builds `sourceId` or artists
     * from the title still cleans it first, so that identity does not depend on this step.
     */
    fun storedTitle(): String = stripTitleStatusMarker(cleanEventTitle(title))

    /**
     * The genre as stored: null when it only repeats the title, which says nothing about the music.
     * `AssociationSyncService` flags such a genre for the data-quality worklist (#320). The type
     * classifier still reads the raw [genre].
     */
    fun storedGenre(): String? = genre?.takeUnless { genreRepeatsTitle() }

    /** Whether [genre] is [storedTitle] again, ignoring case and spacing. */
    fun genreRepeatsTitle(): Boolean {
        val stated = genre?.let(::comparable)
        return !stated.isNullOrEmpty() && stated == comparable(storedTitle())
    }

    /**
     * The type the event is stored with — the source's own, or the festival/format override
     * [resolveEventType] applies at the boundary. Scrapers build their artists before this is
     * known; `AssociationSyncService` reads it to drop a headliner minted from a festival title (#300).
     */
    fun resolvedEventType(): EventType = resolveEventType(eventType, storedTitle(), genre)

    /** [typeIsFallback] unless a festival title or a genre cue changed the type: that is the venue's word. */
    private fun storedTypeIsFallback(): Boolean = typeIsFallback && resolvedEventType().name == eventType

    /**
     * Converts this scraped event into an [EventEntity]; pure, no I/O. The slug is regenerated from
     * the event date, venue slug and title, plus [slugDiscriminator]. On updates [existing]'s `id`,
     * `sourceId` and `createdAt` are preserved.
     *
     * @param venueSlug the venue's slug, in the event slug for cross-venue uniqueness.
     * @param slugDiscriminator appended to separate two sittings of one production on one day. Only
     * the whole scrape can see a collision, so
     * `EventUpsertService`[de.norm.events.scraper.EventUpsertService] computes it. Null for the
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
        val notes = listOfNotNull(statusNote, title, subtitle, description, descriptionAlt)
        val relocation = notes.firstNotNullOfOrNull(::parseRelocation)
        val (relocatedStatus, relocatedTo) = resolveRelocation(badgeStatus, relocation, venueSlug)
        // A "verschoben" note sits on both dates of a move too; the row the show moved to takes place (#2206).
        val postponedStatus = resolvePostponement(relocatedStatus, notes, eventDate)
        // A new date can come with a new house; on the date the show left, that is a move (#2708).
        val (storedStatus, postponedTo) = resolvePostponedMove(postponedStatus, notes, venueSlug)
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
        // priceCurrency omitted: every scraped venue is in Berlin, and EventEntity defaults to "EUR".
        return EventEntity(
            // `sourceId` is the immutable identity key matching scraped events to persisted rows.
            id = existing?.id,
            sourceId = existing?.sourceId ?: sourceId,
            createdAt = existing?.createdAt,
            contentHash = existing?.contentHash,
            contentChangedAt = existing?.contentChangedAt,
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
            // OTHER, not CONCERT, when the source provided no category; then promote an under-classified
            // festival title to FESTIVAL, or recover a reading/exhibition/screening filed under the genre
            // field.
            eventType = resolvedEventType().name,
            typeIsFallback = storedTypeIsFallback(),
            status = storedStatus,
            relocatedTo = relocatedTo ?: postponedTo,
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
            genre = storedGenre(),
            pricePresale = pricePresale?.normalizeMoneyScale(),
            priceBoxOffice = priceBoxOffice?.normalizeMoneyScale(),
            priceNote = priceNote,
            soldOut = storedSoldOut(storedStatus),
            // Honour an explicit scraper flag, otherwise derive from prices/note/title.
            free = free || detectFree(pricePresale, priceBoxOffice, priceNote, storedTitle)
        ).withSpokenLanguages(spokenLanguages(storedTitle))
            .withSecondLanguage(publisherAlt(storedDescription, detected), existing)
    }
}

/**
 * Whether the row is stored sold out. A marker in the title or subtitle is the venue's word, whether or
 * not its scraper reads it (#2103). A cancelled show sells nothing: Wix closes registration on a
 * cancellation and reports it sold out (#2710).
 */
private fun ScrapedEvent.storedSoldOut(storedStatus: String): Boolean =
    storedStatus != EventStatus.CANCELLED.name && (soldOut || hasSoldOutMarker(title) || hasSoldOutMarker(subtitle))

/**
 * The languages the venue states for this event. Read from both halves of the description even when
 * the licence withholds it: the language is a fact, not the prose.
 */
private fun ScrapedEvent.spokenLanguages(storedTitle: String): SpokenLanguages =
    detectSpokenLanguages(
        storedTitle,
        subtitle,
        listOfNotNull(description, descriptionAlt).joinToString("\n").ifEmpty { null },
        resolvedEventType(),
        houseLanguage
    )

/**
 * [ScrapedEvent.descriptionAlt] with its language, when it is stored: the description is, and the two
 * detect as German and English. Otherwise the page would offer one language twice.
 */
private fun ScrapedEvent.publisherAlt(
    storedDescription: String?,
    detected: DescriptionLanguage.Detection?
): Pair<String, DescriptionLanguage>? {
    val text = descriptionAlt?.takeIf { storedDescription != null } ?: return null
    val read = DescriptionLanguage.detect(text)?.language
    val language = read?.takeIf { detected != null && it != detected.language }
    if (language == null) {
        logger.at(Level.DEBUG) {
            message = "Second-language text of '$title' not stored: it reads as ${read?.code}, the description as ${detected?.language?.code}"
            payload = mapOf(LogFields.EVENT_SOURCE_ID to sourceId)
        }
    }
    return language?.let { text to it }
}

/**
 * The second-language columns: the venue's own text where it wrote one, else the stored machine
 * translation while the description it was made from is unchanged. The translation is derived after
 * the import commits; rebuilding it as null wiped it and bought it again every night (#1301). A
 * refusal is kept on the same terms, or the next pass buys it again (#2714).
 */
private fun EventEntity.withSecondLanguage(
    publisherAlt: Pair<String, DescriptionLanguage>?,
    existing: EventEntity?
): EventEntity {
    if (publisherAlt != null) {
        return copy(
            descriptionAlt = publisherAlt.first,
            descriptionAltLanguage = publisherAlt.second.code,
            descriptionAltOrigin = PUBLISHER_ORIGIN
        )
    }
    val translation = existing?.takeIf { description != null && it.description == description && it.descriptionAltOrigin != PUBLISHER_ORIGIN }
    return copy(
        descriptionAlt = translation?.descriptionAlt,
        descriptionAltLanguage = translation?.descriptionAltLanguage,
        descriptionAltOrigin = translation?.descriptionAltOrigin,
        descriptionAltEngine = translation?.descriptionAltEngine,
        descriptionAltSourceHash = translation?.descriptionAltSourceHash,
        descriptionAltRefusedHash = translation?.descriptionAltRefusedHash
    )
}

private fun EventEntity.withSpokenLanguages(languages: SpokenLanguages): EventEntity =
    copy(spokenLanguages = languages.spoken, subtitleLanguage = languages.subtitle)

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
 * slot. `EventUpsertService` is the source of truth; a scraper applies the cutoff earlier only
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
 * from the first listed day to the last, built on the first day whose page answered. A row already
 * carrying a span widens the fold. A single day stays a single day; every other kind of event
 * passes through, since a festival's days differ in lineup.
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
        (days.firstOrNull { !it.detailUnavailable } ?: days.first()).copy(
            sourceId = k,
            eventDate = opening,
            endDate = closing.takeIf { it > opening },
            endTime = null
        )
    }
}

/**
 * Folds each day whose [ScrapedEvent.storedRunId] is in [runIds] into that run, as
 * [collapseExhibitionRuns] does. The day takes EXHIBITION and bills no act, as a day whose page
 * answered does (#2575).
 */
fun List<ScrapedEvent>.foldIntoRuns(runIds: Set<String>): List<ScrapedEvent> =
    if (runIds.isEmpty()) {
        this
    } else {
        map { if (it.storedRunId in runIds) it.copy(eventType = EventType.EXHIBITION.name, artists = emptyList()) else it }
            .collapseExhibitionRuns { it.storedRunId?.takeIf(runIds::contains) }
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

/** [text] lower-cased with its whitespace collapsed, for an equality that ignores case and spacing. */
private fun comparable(text: String): String = text.trim().replace(WHITESPACE, " ").lowercase()
