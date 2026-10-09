package de.norm.events.event

import de.norm.events.artist.ArtistSummaryResponse
import de.norm.events.common.AssumedStartTime
import de.norm.events.common.PageResponse
import de.norm.events.image.INTRINSIC_HEIGHT_DESCRIPTION
import de.norm.events.image.INTRINSIC_WIDTH_DESCRIPTION
import de.norm.events.image.ImageSourceResponse
import de.norm.events.image.ServedImage
import de.norm.events.promoter.PromoterSummaryResponse
import de.norm.events.venue.VenueSummaryResponse
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * Compact event representation for list, calendar, and "today" responses.
 *
 * Embeds a venue summary and flat artist-name/genre lists so the frontend can render event
 * cards without follow-up requests.
 */
@Schema(description = "Compact event summary for lists and calendar")
data class EventSummaryResponse(
    @Schema(description = "Database primary key", example = "101")
    val id: Long,
    @Schema(description = "URL-friendly identifier (format: {date}-{venue}-{title})", example = "2026-06-18-lido-sam-prekop-john-mcentire")
    val slug: String,
    @Schema(description = "Main headline or name of the event", example = "THE ADICTS")
    val title: String,
    @Schema(description = "Secondary line, often a tour name or support acts")
    val subtitle: String?,
    @Schema(description = "Kind of event", example = "CONCERT")
    val eventType: EventType,
    @Schema(description = "Scheduling status of the event", example = "SCHEDULED")
    val status: EventStatus,
    @Schema(description = RELOCATED_TO_DESCRIPTION, example = "Hole44")
    val relocatedTo: String? = null,
    @Schema(description = SPOKEN_LANGUAGES_DESCRIPTION, example = "[\"en\"]")
    val spokenLanguages: List<String>? = null,
    @Schema(description = SUBTITLE_LANGUAGE_DESCRIPTION, example = "de")
    val subtitleLanguage: String? = null,
    @Schema(description = "Calendar date of the event", example = "2026-06-12")
    val eventDate: LocalDate,
    @Schema(description = "Time when doors open to the public", example = "19:00")
    val doorsTime: LocalTime?,
    @Schema(description = "Time when the show/performance starts", example = "20:00")
    val startTime: LocalTime?,
    @Schema(description = END_DATE_DESCRIPTION, example = "2026-06-15")
    val endDate: LocalDate?,
    @Schema(description = END_TIME_DESCRIPTION, example = "10:00")
    val endTime: LocalTime?,
    @Schema(description = ASSUMED_START_TIME_DESCRIPTION, example = "23:00")
    val assumedStartTime: LocalTime?,
    @Schema(description = IMAGE_URL_DESCRIPTION)
    val imageUrl: String?,
    @Schema(description = POSTER_SOURCES_DESCRIPTION)
    val imageSources: List<ImageSourceResponse>,
    @Schema(description = INTRINSIC_WIDTH_DESCRIPTION, example = "1200")
    val intrinsicWidth: Int?,
    @Schema(description = INTRINSIC_HEIGHT_DESCRIPTION, example = "630")
    val intrinsicHeight: Int?,
    @Schema(description = IMAGE_WITHHELD_DESCRIPTION, example = "false")
    val imageWithheld: Boolean,
    @Schema(description = "Presale ticket price (Vorverkauf)", example = "38.00")
    val pricePresale: BigDecimal?,
    @Schema(description = "Box office ticket price (Abendkasse)", example = "45.00")
    val priceBoxOffice: BigDecimal?,
    @Schema(description = "ISO 4217 currency code for prices", example = "EUR")
    val priceCurrency: String,
    @Schema(description = "Free-form pricing note for non-standard pricing")
    val priceNote: String?,
    @Schema(description = "Whether all tickets for this event are sold out", example = "false")
    val soldOut: Boolean,
    @Schema(description = "Whether the event is free to attend (free entry)", example = "false")
    val free: Boolean,
    @Schema(description = "The venue where this event takes place")
    val venue: VenueSummaryResponse,
    @Schema(description = "Artist names in billing order (headliner first)", example = "[\"The Adicts\", \"Maid Of Ace\"]")
    val artistNames: List<String>,
    @Schema(description = "Normalized genre tags", example = "[\"Punk\"]")
    val genreTags: List<String>
) {
    companion object {
        @Suppress("LongParameterList") // A row-to-response mapper takes one parameter per column it cannot read off the entity.
        fun fromEntity(
            entity: EventEntity,
            venue: VenueSummaryResponse,
            artistNames: List<String>,
            genreTags: List<String>,
            image: ServedImage,
            imageWithheld: Boolean
        ): EventSummaryResponse =
            EventSummaryResponse(
                id = requireNotNull(entity.id) { "Persisted event must have an ID" },
                slug = entity.slug,
                title = entity.title,
                subtitle = entity.subtitle,
                eventType = EventType.parseOrDefault(entity.eventType),
                status = EventStatus.parseOrDefault(entity.status),
                relocatedTo = entity.relocatedTo,
                spokenLanguages = entity.spokenLanguages,
                subtitleLanguage = entity.subtitleLanguage,
                eventDate = entity.eventDate,
                doorsTime = entity.doorsTime,
                startTime = entity.startTime,
                endDate = entity.endDate,
                endTime = entity.endTime,
                assumedStartTime = AssumedStartTime.guess(entity.startTime, entity.doorsTime, entity.eventType),
                imageUrl = image.url,
                imageSources = image.sources,
                intrinsicWidth = image.intrinsicWidth,
                intrinsicHeight = image.intrinsicHeight,
                imageWithheld = imageWithheld,
                pricePresale = entity.pricePresale,
                priceBoxOffice = entity.priceBoxOffice,
                priceCurrency = entity.priceCurrency,
                priceNote = entity.priceNote,
                soldOut = entity.soldOut,
                free = entity.free,
                venue = venue,
                artistNames = artistNames,
                genreTags = genreTags
            )
    }
}

/**
 * One page of the events list, and the curated pick that leads it (#1262). The paging fields are
 * [PageResponse]'s, so the page reads like every other list; [lead] is the one addition.
 */
@Schema(description = "A page of the events list, with the curated pick that leads its first page")
data class EventListPage(
    @Schema(description = "The items on this page")
    val content: List<EventSummaryResponse>,
    @Schema(description = "Zero-based index of this page", example = "0")
    val page: Int,
    @Schema(description = "Requested page size", example = "20")
    val size: Int,
    @Schema(description = "Total number of matching items across all pages", example = "137")
    val totalElements: Long,
    @Schema(description = "Total number of pages", example = "7")
    val totalPages: Int,
    @Schema(
        description =
            "On the first page only: of the events matching the filters that an operator features, the earliest-starting. " +
                "It may also be in `content`, or on a later page. Null when no matching event is featured."
    )
    val lead: EventSummaryResponse? = null
) {
    companion object {
        fun of(
            page: PageResponse<EventSummaryResponse>,
            lead: EventSummaryResponse?
        ): EventListPage = EventListPage(page.content, page.page, page.size, page.totalElements, page.totalPages, lead)
    }
}

/**
 * Full event representation for the event detail page, with embedded venue, ordered lineup,
 * promoters, and genre tags.
 */
@Schema(description = "Full event detail with embedded associations")
data class EventDetailResponse(
    @Schema(description = "Database primary key", example = "101")
    val id: Long,
    @Schema(description = "URL-friendly identifier (format: {date}-{venue}-{title})", example = "2026-06-18-lido-sam-prekop-john-mcentire")
    val slug: String,
    @Schema(description = "Main headline or name of the event", example = "THE ADICTS")
    val title: String,
    @Schema(description = "Secondary line, often a tour name or support acts")
    val subtitle: String?,
    @Schema(description = "Longer description or artist biography")
    val description: String?,
    @Schema(
        description =
            "Language of `description` as detected at import: `de` or `en`. Null when the text is too short " +
                "or holds both languages, in which case the page claims no language for it (ADR-026).",
        example = "de"
    )
    val descriptionLanguage: String?,
    @Schema(
        description =
            "The description in the other locale, when there is one. Written by the publisher, or by a machine " +
                "where the source's grant allows it. Null far more often than not."
    )
    val descriptionAlt: String?,
    @Schema(description = "Language of `descriptionAlt`: `de` or `en`. Null exactly when `descriptionAlt` is.", example = "en")
    val descriptionAltLanguage: String?,
    @Schema(
        description =
            "Who wrote `descriptionAlt`: `PUBLISHER` or `MACHINE`. A machine translation is labelled as such " +
                "on the page and links to the source. Null exactly when `descriptionAlt` is.",
        example = "PUBLISHER"
    )
    val descriptionAltOrigin: String?,
    @Schema(description = "Kind of event", example = "CONCERT")
    val eventType: EventType,
    @Schema(description = "Scheduling status of the event", example = "SCHEDULED")
    val status: EventStatus,
    @Schema(description = RELOCATED_TO_DESCRIPTION, example = "Hole44")
    val relocatedTo: String? = null,
    @Schema(description = SPOKEN_LANGUAGES_DESCRIPTION, example = "[\"en\"]")
    val spokenLanguages: List<String>? = null,
    @Schema(description = SUBTITLE_LANGUAGE_DESCRIPTION, example = "de")
    val subtitleLanguage: String? = null,
    @Schema(description = "Calendar date of the event", example = "2026-06-12")
    val eventDate: LocalDate,
    @Schema(description = "Time when doors open to the public", example = "19:00")
    val doorsTime: LocalTime?,
    @Schema(description = "Time when the show/performance starts", example = "20:00")
    val startTime: LocalTime?,
    @Schema(description = END_DATE_DESCRIPTION, example = "2026-06-15")
    val endDate: LocalDate?,
    @Schema(description = END_TIME_DESCRIPTION, example = "10:00")
    val endTime: LocalTime?,
    @Schema(description = ASSUMED_START_TIME_DESCRIPTION, example = "23:00")
    val assumedStartTime: LocalTime?,
    @Schema(description = IMAGE_URL_DESCRIPTION)
    val imageUrl: String?,
    @Schema(description = POSTER_SOURCES_DESCRIPTION)
    val imageSources: List<ImageSourceResponse>,
    @Schema(description = INTRINSIC_WIDTH_DESCRIPTION, example = "1200")
    val intrinsicWidth: Int?,
    @Schema(description = INTRINSIC_HEIGHT_DESCRIPTION, example = "630")
    val intrinsicHeight: Int?,
    @Schema(description = IMAGE_WITHHELD_DESCRIPTION, example = "false")
    val imageWithheld: Boolean,
    @Schema(
        description =
            "True when a licence prohibition removed the description. False when the venue " +
                "simply wrote none, which is far more common and needs no explanation.",
        example = "false"
    )
    val descriptionWithheld: Boolean,
    @Schema(description = "Original URL on the source venue's website")
    val sourceUrl: String?,
    @Schema(
        description =
            "The page the lineup was taken from when it is not `sourceUrl`, such as a fan-run timetable. The page " +
                "must credit it beside the lineup (ADR-036). Null for almost every event.",
        example = "https://sisy.fan/events/from/25.09.2026/to/28.09.2026"
    )
    val lineupSourceUrl: String?,
    @Schema(description = "URL to the external ticket shop")
    val ticketUrl: String?,
    @Schema(description = "Direct link to the Facebook event page")
    val facebookEventUrl: String?,
    @Schema(description = "Music genre or style tag (raw text from source)", example = "Punk")
    val genre: String?,
    @Schema(description = "Presale ticket price (Vorverkauf)", example = "38.00")
    val pricePresale: BigDecimal?,
    @Schema(description = "Box office ticket price (Abendkasse)", example = "45.00")
    val priceBoxOffice: BigDecimal?,
    @Schema(description = "ISO 4217 currency code for prices", example = "EUR")
    val priceCurrency: String,
    @Schema(description = "Free-form pricing note for non-standard pricing")
    val priceNote: String?,
    @Schema(description = "Whether all tickets for this event are sold out", example = "false")
    val soldOut: Boolean,
    @Schema(description = "Whether the event is free to attend (free entry)", example = "false")
    val free: Boolean,
    @Schema(description = "The venue where this event takes place")
    val venue: VenueSummaryResponse,
    @Schema(
        description =
            "The room of the venue the whole event is in, as the venue names it. Null when the venue names none, " +
                "and on a lineup split across rooms, where each entry's `stage` says it instead.",
        example = "Saal"
    )
    val room: String?,
    @Schema(description = "Lineup in billing order (headliner first)")
    val lineup: List<LineupEntryResponse>,
    @Schema(description = "Promoters or presenters responsible for this event")
    val promoters: List<PromoterSummaryResponse>,
    @Schema(description = "Normalized genre tags", example = "[\"Punk\"]")
    val genreTags: List<String>,
    @Schema(
        description =
            "The other sources that filled empty fields of this event, such as a promoter's page (ADR-043). The page " +
                "must link each one (ADR-036). Empty for almost every event."
    )
    val enrichmentSources: List<EnrichmentSourceResponse> = emptyList()
) {
    companion object {
        @Suppress("LongParameterList") // A row-to-response mapper takes one parameter per column it cannot read off the entity.
        fun fromEntity(
            entity: EventEntity,
            venue: VenueSummaryResponse,
            lineup: List<LineupEntryResponse>,
            promoters: List<PromoterSummaryResponse>,
            genreTags: List<String>,
            image: ServedImage,
            imageWithheld: Boolean,
            descriptionWithheld: Boolean,
            enrichmentSources: List<EnrichmentSourceResponse> = emptyList()
        ): EventDetailResponse =
            EventDetailResponse(
                id = requireNotNull(entity.id) { "Persisted event must have an ID" },
                slug = entity.slug,
                title = entity.title,
                subtitle = entity.subtitle,
                description = entity.description,
                descriptionLanguage = entity.descriptionLanguage,
                descriptionAlt = entity.descriptionAlt,
                descriptionAltLanguage = entity.descriptionAltLanguage,
                descriptionAltOrigin = entity.descriptionAltOrigin,
                eventType = EventType.parseOrDefault(entity.eventType),
                status = EventStatus.parseOrDefault(entity.status),
                relocatedTo = entity.relocatedTo,
                spokenLanguages = entity.spokenLanguages,
                subtitleLanguage = entity.subtitleLanguage,
                eventDate = entity.eventDate,
                doorsTime = entity.doorsTime,
                startTime = entity.startTime,
                endDate = entity.endDate,
                endTime = entity.endTime,
                assumedStartTime = AssumedStartTime.guess(entity.startTime, entity.doorsTime, entity.eventType),
                imageUrl = image.url,
                imageSources = image.sources,
                intrinsicWidth = image.intrinsicWidth,
                intrinsicHeight = image.intrinsicHeight,
                imageWithheld = imageWithheld,
                descriptionWithheld = descriptionWithheld,
                sourceUrl = entity.sourceUrl,
                lineupSourceUrl = entity.lineupSourceUrl,
                room = entity.room,
                ticketUrl = entity.ticketUrl,
                facebookEventUrl = entity.facebookEventUrl,
                genre = entity.genre,
                pricePresale = entity.pricePresale,
                priceBoxOffice = entity.priceBoxOffice,
                priceCurrency = entity.priceCurrency,
                priceNote = entity.priceNote,
                soldOut = entity.soldOut,
                free = entity.free,
                venue = venue,
                lineup = lineup,
                promoters = promoters,
                genreTags = genreTags,
                enrichmentSources = enrichmentSources
            )
    }
}

/** A source that filled empty fields of an event, and which fields, so the page can credit it (ADR-043). */
@Schema(description = "A source that filled empty fields of an event, credited on its page")
data class EnrichmentSourceResponse(
    @Schema(description = "The page the fields were read from", example = "https://puschen.net/events/alpha-band")
    val sourceUrl: String,
    @Schema(
        description =
            "The fields it filled, by their admin API names: `lineup`, `promoters`, `genres` or a column such as " +
                "`genre` or `doorsTime`",
        example = "[\"genre\", \"lineup\"]"
    )
    val fields: List<String>
)

/**
 * A single entry in an event's lineup: the artist plus their role and billing position, and the
 * set's times where the venue publishes a running order. The times carry Berlin's offset, so the
 * clock time a visitor reads is the one the venue printed.
 */
@Schema(description = "An artist's participation in an event lineup")
data class LineupEntryResponse(
    @Schema(description = "The performing artist")
    val artist: ArtistSummaryResponse,
    @Schema(description = "The artist's role in the lineup", example = "HEADLINER")
    val role: ArtistRole,
    @Schema(description = "Position in the lineup — lower numbers appear first", example = "0")
    val billingOrder: Int,
    @Schema(description = "The room or floor the act plays when the lineup is split across rooms, or null", example = "Panorama Bar")
    val stage: String? = null,
    @Schema(description = "Start of the set in Berlin time, from the venue's running order, or null", example = "2026-09-26T23:59:00+02:00")
    val setStart: OffsetDateTime? = null,
    @Schema(description = "End of the set in Berlin time, or null", example = "2026-09-27T04:30:00+02:00")
    val setEnd: OffsetDateTime? = null
)

// The descriptions the summary and the detail response both carry, each stating a rule a client
// acts on. Written once, so a correction cannot reach one response and miss the other.
private const val RELOCATED_TO_DESCRIPTION =
    "Where a RELOCATED event moved to, as the venue's own note names the house; absent on every other status"
private const val SPOKEN_LANGUAGES_DESCRIPTION =
    "What is said on stage or on screen, as ISO 639-1 codes, where the venue states it. Absent means unknown, " +
        "never German by default. Not the language of the description."
private const val SUBTITLE_LANGUAGE_DESCRIPTION = "The subtitles of a screening shown in the original, as an ISO 639-1 code: `de` for OmU"
private const val END_DATE_DESCRIPTION =
    "Last day of the event, only when the venue stated one; null means it ends on `eventDate`. A weekender " +
        "carries the Monday here, a run of weeks its closing day."
private const val END_TIME_DESCRIPTION = "Time the event ends on `endDate`, only when the venue stated one. Never set without `endDate`."
private const val ASSUMED_START_TIME_DESCRIPTION =
    "Our guess at the start, from the kind of event, when the venue published neither `startTime` nor " +
        "`doorsTime`; null whenever either is set. The list sorts by it, and a page must show it as a guess."
private const val IMAGE_URL_DESCRIPTION =
    "Where the poster is fetched from. A path on this origin once the environment serves cached images, " +
        "and the venue's own URL until then (ADR-019)."

// Not IMAGE_SOURCES_DESCRIPTION: that name holds the generic image wording in `image/`, and an
// event's poster is described in its own words.
private const val POSTER_SOURCES_DESCRIPTION =
    "Better formats of the same poster, best first, for a <picture> element. Empty when the image is not " +
        "cached, in which case `imageUrl` is all there is."
private const val IMAGE_WITHHELD_DESCRIPTION =
    "True when a licence prohibition removed the image. False both when the venue " +
        "published none and when one is shown — a placeholder renders either way."
