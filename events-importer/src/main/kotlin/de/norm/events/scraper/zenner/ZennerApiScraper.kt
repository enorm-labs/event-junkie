package de.norm.events.scraper.zenner

import com.fasterxml.jackson.annotation.JsonProperty
import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.dropPastEvents
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.isFestivalTitle
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.splitHeadlinerTitle
import de.norm.events.scraper.stripArtistSuffix
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/**
 * Pure parser for Zenner's programme from the Gatsby page-data artefact
 * (`/page-data/programm/page-data.json`) behind `/programm`: a Gatsby front end over a Sanity
 * CMS publishes the GraphQL result as static JSON, so no CSS selector is involved (ADR-007
 * §"Selector Strategy" priority 1). Events nest under `result.data.queryKultur.nodes`, each
 * with a Gatsby node `id` derived from the Sanity document id (the [ScrapedEvent.sourceId]
 * key), `title`, `typeOfEvent`, `place`, an ISO `eventDate`, a ticket-shop `linkEvent`, an
 * `image` and a Portable Text `_rawText` blurb. Three properties shape the parsing:
 * 1. `eventDate` is a true UTC instant, so a `21:45Z` party is a `23:45` Berlin door time;
 * read as a wall clock every event shifts one to two hours early and a late night rolls onto
 * the previous day.
 * 2. The artefact holds the venue's whole archive, so past dates are dropped here
 * ([dropPastEvents]) rather than minting a hundred throwaway events per run.
 * 3. A sibling `queryShowHidePlaces` block carries per-room publish flags
 * ([ZennerPlaceVisibility]), honoured so an unpublished programme is not imported.
 *
 * `place` (Saal / Klub / Biergarten / Weingarten) is a room with no field in the model, not
 * stored, but read for the visibility filter and to disambiguate "Open Air", a DJ day party in
 * the Weingarten and ice skating in the Biergarten ([resolveEventType]).
 *
 * @see ZENNER_LIMITATIONS for what the venue does not publish.
 * @see ZennerWebsiteImporter for the HTTP fetch orchestrator.
 */
class ZennerApiScraper(
    /** Clock for the past-event cutoff. Defaults to the venue's own time zone; override in tests for determinism. */
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    // Unknown fields are ignored, so the base64 blur placeholders, srcSets and SEO block
    // deserialize away.
    private val jsonMapper: JsonMapper =
        JsonMapper
            .builder()
            .addModule(kotlinModule())
            .build()

    /**
     * Parses every published, upcoming event from the page-data [json].
     *
     * @param json the raw body of the `/page-data/<page>/page-data.json` artefact.
     * @param sourceUrl the programme page, stored on every event; Zenner has no per-event pages.
     * @return upcoming [ScrapedEvent]s in rooms the venue publishes; empty if the payload is absent,
     * unparseable or has no nodes.
     */
    @Suppress("ReturnCount") // Guard clauses for the unparseable body and missing node array are clearer than nesting.
    fun scrape(
        json: String,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val data = parseData(json) ?: return emptyList()

        val nodes = data.path(EVENTS_QUERY).path("nodes")
        if (!nodes.isArray) {
            logger.warn { "Zenner page-data has no '$EVENTS_QUERY.nodes' array" }
            return emptyList()
        }
        logger.info { "Found ${nodes.size()} event(s) in Zenner page-data" }

        val visibility = parseVisibility(data)

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed events without aborting the import.
        val events =
            nodes.mapNotNull { node ->
                try {
                    parseEvent(jsonMapper.treeToValue(node, ZennerEventNode::class.java), sourceUrl, visibility)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse Zenner event, skipping" }
                    null
                }
            }

        return events.dropPastEvents(clock) { dropped ->
            logger.info { "Dropped $dropped past event(s) from Zenner page-data" }
        }
    }

    /** Parses the response body and returns its `result.data` object, or null when unparseable or absent. */
    @Suppress(
        "TooGenericExceptionCaught", // A malformed payload must degrade to null, never abort the import.
        "ReturnCount" // Guard clauses for the unparseable body and missing data block are clearer than nesting.
    )
    private fun parseData(json: String): JsonNode? {
        val root =
            try {
                jsonMapper.readTree(json)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Zenner page-data response" }
                return null
            }
        val data = root.path("result").path("data")
        if (!data.isObject) {
            logger.warn { "Zenner page-data has no 'result.data' object" }
            return null
        }
        return data
    }

    /**
     * The per-room publish flags from `queryShowHidePlaces`, degrading to
     * [ZennerPlaceVisibility.ALL_VISIBLE] when absent or unreadable: failing open, so a renamed
     * field empties nothing.
     */
    private fun parseVisibility(data: JsonNode): ZennerPlaceVisibility {
        val node = data.path(PLACES_QUERY).path("nodes").firstOrNull()
        if (node == null) {
            logger.info { "Zenner page-data carries no '$PLACES_QUERY' flags; treating every place as published" }
            return ZennerPlaceVisibility.ALL_VISIBLE
        }
        return jsonMapper.treeToValue(node, ZennerPlaceVisibility::class.java)
    }

    @Suppress("ReturnCount") // Guard clauses for the required id, title, date, and place visibility are clearer than nesting.
    private fun parseEvent(
        node: ZennerEventNode,
        sourceUrl: String,
        visibility: ZennerPlaceVisibility
    ): ScrapedEvent? {
        val id = node.id.blankToNull()
        if (id == null) {
            logger.warn { "Zenner event has no id, skipping" }
            return null
        }

        val title = node.title.blankToNull()?.let { cleanEventTitle(it) }
        if (title == null) {
            logger.warn { "Zenner event '$id' has no title, skipping" }
            return null
        }

        val start = parseStart(node.eventDate)
        if (start == null) {
            logger.warn { "Zenner event '$id' has no parseable eventDate '${node.eventDate}', skipping" }
            return null
        }

        val place = node.place.blankToNull()
        if (!visibility.isPublished(place)) {
            logger.info { "Zenner event '$title' is in unpublished place '$place', skipping" }
            return null
        }

        val description = flattenPortableText(node.rawText)
        val eventType = resolveEventType(node.typeOfEvent.blankToNull(), place, title)

        return ScrapedEvent(
            title = title,
            description = description,
            eventType = eventType,
            eventDate = start.toLocalDate(),
            startTime = start.toLocalTime(),
            imageUrl =
                node.image
                    ?.asset
                    ?.fluid
                    ?.src
                    .blankToNull()
                    ?.takeIf { it.startsWith("http") },
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.ZENNER.sourceIdPrefix}${id.trimStart('-')}",
            // A room-booking enquiry (`mailto:reservierung@zenner.berlin`) is not a ticket shop.
            ticketUrl = node.linkEvent.blankToNull()?.takeIf { it.startsWith("http") },
            // The venue announces a cancellation only in the blurb ("Wegen schlechten Wetters abgesagt!").
            status = description?.let { parseEventStatus(it) } ?: EventStatus.SCHEDULED.name,
            artists = buildArtists(title, eventType)
        )
    }

    /**
     * Converts the node's UTC `eventDate` to the [BERLIN] wall clock, or null. `OffsetDateTime`
     * rather than `Instant` so an explicitly offset value (`…+02:00`) parses too.
     */
    private fun parseStart(raw: String?): ZonedDateTime? {
        val value = raw.blankToNull() ?: return null
        @Suppress("SwallowedException") // The unparseable value is reported by the caller, which knows the event id.
        return try {
            OffsetDateTime.parse(value).atZoneSameInstant(BERLIN)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /**
     * Types the event from `typeOfEvent`, two of whose values name a format or location. "Open Air"
     * in the Weingarten is the SIP! day-party series, always a DJ line-up, so the room disambiguates
     * and the event is [PARTY][EventType.PARTY]; the same label in the Biergarten is not, since that
     * room's Open Airs are ice-skating sessions (`Eisdisko`, `Eislaufen`), a Fête de la Musique and
     * a festival day. "Event" is the catch-all (a flea market, a wine tasting, a World Cup public
     * viewing) and falls with any other Open Air to [inferUnmarkedTitleType]. A title that
     * unambiguously names a festival is exempted from the Weingarten rule and left `OTHER`, because
     * the `FESTIVAL` promotion in [ScrapedEvent.toEventEntity] overrides only `CONCERT`/`OTHER`.
     */
    private fun resolveEventType(
        label: String?,
        place: String?,
        title: String
    ): String =
        mapEventType(label)
            ?: EventType.PARTY.name.takeIf { isWeingartenOpenAir(label, place) && !isFestivalTitle(title) }
            ?: inferUnmarkedTitleType(title)

    /**
     * The lineup from the title, keyed off the venue's own type. Titles are overwhelmingly event
     * names ("NICE ONE", "Crossover", "SIP!"), so a performer is only read out of an explicit
     * billing frame: concerts via [concertHeadliners] (`"<series|promoter> presents:"` / `"<n> min
     * w/"` plus an optional `", support:"` tail), parties via [djsFromWithFrame] (`"<series> w/
     * <DJs>"`), everything else nothing.
     */
    private fun buildArtists(
        title: String,
        eventType: String
    ): List<ScrapedArtist> =
        when (eventType) {
            EventType.CONCERT.name -> concertHeadliners(title)
            EventType.PARTY.name -> djsFromWithFrame(title)
            else -> emptyList()
        }

    /**
     * A concert's headliners after [stripSeriesFrame]: even concerts are billed under a series or
     * promoter ("180 min w/ Barker (live)", "Trinity presents: Nathan Fake", "Analogue Foundation
     * presents Lyra Pramuk (live)"), and raw they would mint "180 min w/ Barker". A title that is
     * only the series name ("180 MINUTES", "Analogue Foundation") yields nothing ([isBareSeriesTitle]).
     */
    private fun concertHeadliners(title: String): List<ScrapedArtist> {
        val billed = stripSeriesFrame(title)?.takeUnless { isBareSeriesTitle(it) } ?: return emptyList()
        val (headlineText, supportNames) = splitSupportTail(billed)
        val supportActs =
            supportNames
                .filterNot { isNonArtistName(it) }
                .map { ScrapedArtist(name = it, role = "SUPPORT") }
        return headlinersFromTitle(headlineText) + supportActs
    }

    /**
     * Flattens a Sanity Portable Text blob: spans concatenated per block, blocks as lines, blanks
     * dropped. Null when absent or without a letter or digit, which covers the bare `"."`
     * placeholder Zenner leaves for an event with no blurb.
     */
    private fun flattenPortableText(blocks: List<ZennerTextBlock>?): String? =
        blocks
            ?.mapNotNull { block ->
                block.children
                    ?.mapNotNull { it.text }
                    ?.joinToString("")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }?.joinToString("\n")
            ?.trim()
            ?.takeIf { text -> text.any { it.isLetterOrDigit() } }

    private companion object {
        /** The page-data GraphQL alias holding the programme nodes. */
        const val EVENTS_QUERY = "queryKultur"

        /** The page-data GraphQL alias holding the per-room publish flags. */
        const val PLACES_QUERY = "queryShowHidePlaces"
    }
}

/** The venue's "Open Air" format label. */
private const val OPEN_AIR_LABEL = "open air"

/** The wine-garden room, whose whole Open Air programme is the SIP! day-party series. */
private const val WEINGARTEN_PLACE = "weingarten"

/**
 * Whether this is an "Open Air" in the Weingarten, a SIP! day party. Both halves required: the
 * label alone covers the Biergarten's ice skating, the room alone hosts wine tastings.
 */
private fun isWeingartenOpenAir(
    label: String?,
    place: String?
): Boolean = label?.trim()?.lowercase() == OPEN_AIR_LABEL && place?.trim()?.lowercase() == WEINGARTEN_PLACE

/**
 * The promoter/series frame up to the marker introducing the act: `"<series|promoter>
 * presents[:]"` / `"pres.[:]"` ("Trinity presents: Nathan Fake"), or `"<n> min[utes] w/"` (the
 * "180 min w/ <act>" series). The `w/` alternative is anchored to a leading duration because
 * `w/` also joins two collaborating acts ("David August w/ MFO").
 */
private val SERIES_FRAME_PATTERN =
    Regex("""^.+?\b(?:presents|pres\.)\s*:?\s+|^\d+\s*min(?:utes)?\s+w/\s*""", RegexOption.IGNORE_CASE)

/**
 * Strips a leading [SERIES_FRAME_PATTERN], or returns null when nothing is left (a bare "180
 * MINUTES" edition). A title with no frame ("Yeule", "KALI MALONE (Live)") is unchanged.
 */
private fun stripSeriesFrame(title: String): String? = title.replaceFirst(SERIES_FRAME_PATTERN, "").trim().takeIf { it.isNotBlank() }

/**
 * Zenner's recurring concert series whose name is sometimes the whole title: "180 MINUTES"
 * (otherwise "180 min w/ <act>") and "Analogue Foundation" (otherwise "Analogue Foundation
 * presents: <act>"); the numeric series matched structurally, the named one by entry, case- and
 * whitespace-insensitive.
 */
private val BARE_SERIES_TITLES: Set<String> = setOf("analogue foundation")

/** The "180 min" / "180 MINUTES" series name standing alone as a whole title, with no act billed after it. */
private val BARE_DURATION_SERIES_PATTERN = Regex("""^\d+\s*min(?:utes)?$""", RegexOption.IGNORE_CASE)

/**
 * True when [title] is nothing but a recurring series name ([BARE_SERIES_TITLES]).
 */
private fun isBareSeriesTitle(title: String): Boolean {
    val normalized = title.trim().replace(WHITESPACE, " ")
    return BARE_DURATION_SERIES_PATTERN.matches(normalized) || normalized.lowercase() in BARE_SERIES_TITLES
}

/**
 * The `"<series> w/ <acts>"` frame, the only frame a party title is mined for: a `w/` names a
 * guest ("SIP! w/ Coco Maria"). `"<promoter> presents <x>"` is not used: its tail is as often an
 * event name ("Gene On Earth presents Rave 'n' Cruise").
 */
private val WITH_FRAME_PATTERN = Regex("""^.+?\bw/\s*""", RegexOption.IGNORE_CASE)

/**
 * A set-length note appended to a guest DJ, "(All Day Long)", not covered by [stripArtistSuffix];
 * curated to the venue's forms so a parenthesised alias survives.
 */
private val SET_LENGTH_NOTE_PATTERN =
    Regex("""\s*\(\s*all\s+(?:day|night)\s+long\s*\)\s*$""", RegexOption.IGNORE_CASE)

/**
 * A party's DJ line-up from a `"<series> w/ <DJs>"` title: "SIP! w/ Haseeb Iqbal (All Day Long)"
 * to `[Haseeb Iqbal (DJ)]`. Empty without the frame, the common case ("SIP!", "SIP! Closing",
 * "NICE ONE"). Guests split on the shared separators, notes and suffixes stripped, non-artists
 * dropped.
 */
@Suppress("ReturnCount") // Guard clauses for the missing frame and its empty tail are clearer than nesting.
private fun djsFromWithFrame(title: String): List<ScrapedArtist> {
    val match = WITH_FRAME_PATTERN.find(title) ?: return emptyList()
    val billed = title.substring(match.range.last + 1).replace(SET_LENGTH_NOTE_PATTERN, "").trim()
    if (billed.isBlank()) return emptyList()
    return splitHeadlinerTitle(billed)
        .map { stripArtistSuffix(it.trim()) }
        .filterNot { it.isBlank() || isNonArtistName(it) }
        .distinct()
        .map { ScrapedArtist(name = it, role = "DJ") }
}

/** The `", support: <acts>"` tail Zenner appends to a concert title — "Erland Cooper, support: Meredi". */
private val SUPPORT_TAIL_PATTERN = Regex(""",?\s*\bsupport\s*:\s*""", RegexOption.IGNORE_CASE)

/**
 * Splits a title on `", support:"` into the headline and the support acts; unchanged with none.
 */
private fun splitSupportTail(title: String): Pair<String, List<String>> {
    val match = SUPPORT_TAIL_PATTERN.find(title) ?: return title to emptyList()
    val supports =
        title
            .substring(match.range.last + 1)
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    return title.substring(0, match.range.first).trim() to supports
}

/**
 * One event in `queryKultur.nodes`, mapped by Jackson; only the fields Zenner populates, every
 * one nullable, validated in [ZennerApiScraper].
 */
private data class ZennerEventNode(
    /** Gatsby node id, derived deterministically from the Sanity document id. */
    val id: String? = null,
    val title: String? = null,
    /** The venue's own category label — `Konzert`, `Concert`, `Party`, `Lesung`, `Event`, `Open Air`. */
    val typeOfEvent: String? = null,
    /** The room within the venue — `Saal`, `Klub`, `Biergarten`, `Weingarten`. */
    val place: String? = null,
    /** ISO 8601 **UTC instant** of the event's start (e.g. `2026-08-09T13:00:00.000Z`). */
    val eventDate: String? = null,
    /** Ticket-shop link (Resident Advisor, DICE, Ticketmaster) — or a `mailto:` enquiry address. */
    val linkEvent: String? = null,
    val image: ZennerImage? = null,
    /** Sanity Portable Text blurb; its `_rawText` key (leading underscore) is not derivable from the property name. */
    @param:JsonProperty("_rawText") val rawText: List<ZennerTextBlock>? = null
)

/** The Sanity image reference; only the CDN [ZennerImageFluid.src] is used. */
private data class ZennerImage(
    val asset: ZennerImageAsset? = null
)

private data class ZennerImageAsset(
    val fluid: ZennerImageFluid? = null
)

private data class ZennerImageFluid(
    /** Absolute Sanity CDN URL of the poster image. */
    val src: String? = null
)

/** One Portable Text block; only its child spans' [ZennerTextSpan.text] is used. */
private data class ZennerTextBlock(
    val children: List<ZennerTextSpan>? = null
)

private data class ZennerTextSpan(
    val text: String? = null
)

/**
 * The per-room publish flags from `queryShowHidePlaces`: a room whose flag is `false` renders
 * no events. Note the payload's misspelling `wiengartenShow`, and that the Klub's programme flag
 * is `klubShowProgramm`, distinct from `klubShowLocation` / `klubShowMieten`, which govern other
 * page sections. Every flag defaults to `true` ([ZennerApiScraper.parseVisibility]).
 */
internal data class ZennerPlaceVisibility(
    val saalShow: Boolean = true,
    val klubShowProgramm: Boolean = true,
    val biergartenShow: Boolean = true,
    val wiengartenShow: Boolean = true
) {
    /**
     * Whether the venue publishes the programme of [place]; an unrecognized place is treated as
     * published.
     */
    fun isPublished(place: String?): Boolean =
        when (place?.trim()?.lowercase()) {
            "saal" -> saalShow
            "klub" -> klubShowProgramm
            "biergarten" -> biergartenShow
            "weingarten" -> wiengartenShow
            else -> true
        }

    companion object {
        /** Fallback used when the payload carries no flags — every room treated as published. */
        val ALL_VISIBLE = ZennerPlaceVisibility()
    }
}
