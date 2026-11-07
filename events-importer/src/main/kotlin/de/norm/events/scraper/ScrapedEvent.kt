package de.norm.events.scraper

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventArtistEntity
import de.norm.events.event.EventEntity
import de.norm.events.event.EventFeatureMatch
import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.event.SpokenLanguage
import de.norm.events.licence.SourceLicences
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

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
     * Raw artist names with their [ArtistRole], resolved by the service layer.
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
    val houseLanguage: SpokenLanguage? = null,
    /**
     * The features the venue's page states beyond the stored text, such as a children's category or a
     * price per child. `EventFeatureSync` stores them beside what `EventFeatureRules` reads (#3796).
     */
    val features: List<EventFeatureMatch> = emptyList()
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
            promoterWebsites = promoterWebsites.ifEmpty { fallback.promoterWebsites },
            features = features.ifEmpty { fallback.features }
        )

    /**
     * The title as stored: cleaned by [cleanEventTitle] for every source, then the sold-out marker and a
     * status note removed once [toEventEntity] has read the flag and the status off it (#2008, #2971).
     * Dedup and slug collisions key on this too, so they agree with the stored slug. A scraper that
     * builds `sourceId` or artists from the title still cleans it first, so that identity does not
     * depend on this step.
     */
    fun storedTitle(): String = stripTitleStatusMarker(stripSoldOutMarker(cleanEventTitle(title).text))

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
     * known; `ArtistAssociationSync` reads it to drop a headliner minted from a festival title (#300).
     */
    fun resolvedEventType(): EventType = resolveEventType(eventType, storedTitle(), genre)

    /** [typeIsFallback] unless a festival title or a genre cue changed the type: that is the venue's word. */
    internal fun storedTypeIsFallback(): Boolean = typeIsFallback && resolvedEventType().name == eventType

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
    ): EventEntity =
        // Every source's description is cut before its language is detected, so the venue's own second
        // language wins over a machine translation (#2986). A rule left at either edge goes (#3062), and so
        // does an e-mail address (LEGAL.md §7.3a).
        withBilingualDescriptionSplit()
            .let {
                it.copy(
                    description = it.description?.let(::stripContactDetails)?.let(::trimEdgeRules),
                    descriptionAlt = it.descriptionAlt?.let(::stripContactDetails)?.let(::trimEdgeRules)
                )
            }.buildEventEntity(venueId, venueSlug, eventSourceId, existing, slugDiscriminator, licences)
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
 * A raw artist reference extracted from a scraped event.
 */
data class ScrapedArtist(
    /** Artist or band name as it appears on the website. */
    val name: String,
    /** Role in the lineup. Defaults to headliner. */
    val role: ArtistRole = ArtistRole.HEADLINER,
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
     * Converts this scraped artist into an [EventArtistEntity].
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
            role = role.name,
            billingOrder = billingOrder,
            stage = stage,
            titleDerived = titleDerived,
            setStart = setStart,
            setEnd = setEnd
        )
}

/** [text] lower-cased with its whitespace collapsed, for an equality that ignores case and spacing. */
private fun comparable(text: String): String = text.trim().replace(WHITESPACE, " ").lowercase()
