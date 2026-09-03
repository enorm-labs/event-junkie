package de.norm.events.scraper.zenner

import com.fasterxml.jackson.annotation.JsonProperty
import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
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
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/** Time zone the venue publishes in — its UTC `eventDate` instants are converted to this wall clock. */
internal val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

/**
 * Pure parser for Zenner's programme, sourced from the Gatsby page-data artefact
 * (`/page-data/programm/page-data.json`) that backs the venue's `/programm` page.
 *
 * Zenner runs a Gatsby front end over a Sanity headless CMS, and Gatsby publishes the GraphQL result
 * its React shell renders from as a static JSON artefact next to the page — so the whole programme is
 * structured data and no CSS selector is involved (ADR-007 §"Selector Strategy" priority 1).
 *
 * Events nest under `result.data.queryKultur.nodes`, each with a Gatsby node `id` derived
 * deterministically from the Sanity document id (the stable [ScrapedEvent.sourceId] key), a `title`,
 * `typeOfEvent`, `place`, an ISO `eventDate`, a ticket-shop `linkEvent`, an `image` and a Portable
 * Text `_rawText` blurb. Three properties of that payload shape the parsing:
 *
 *  1. **`eventDate` is a true UTC instant**, converted client-side by the site, so a `21:45Z` party is
 *     a `23:45` Berlin door time. Reading it as a wall clock would shift every event one to two hours
 *     early and roll a late night onto the previous day.
 *  2. **The artefact holds the venue's whole archive**, years of past events for a handful of upcoming
 *     ones, so past dates are dropped here ([dropPastEvents]) rather than minting a hundred throwaway
 *     events per run for the persistence layer to discard.
 *  3. **A sibling `queryShowHidePlaces` block carries the venue's per-room publish flags**
 *     ([ZennerPlaceVisibility]), honoured so a programme Zenner has unpublished is not imported.
 *
 * `place` (Saal / Klub / Biergarten / Weingarten) is a room within one venue and has no field in the
 * model, so it is not stored — but it is read twice: for that visibility filter, and to disambiguate
 * the "Open Air" label, which means a DJ day party in the Weingarten and ice skating in the Biergarten
 * (see [resolveEventType]).
 *
 * @see ZENNER_LIMITATIONS for what the venue does not publish.
 * @see ZennerWebsiteImporter for the HTTP fetch orchestrator.
 */
class ZennerApiScraper(
    /** Clock for the past-event cutoff. Defaults to the venue's own time zone; override in tests for determinism. */
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    // Unknown fields are ignored (Jackson 3 default), so the payload's presentation-only
    // extras (base64 blur placeholders, srcSets, SEO block) deserialize away silently.
    private val jsonMapper: JsonMapper =
        JsonMapper
            .builder()
            .addModule(kotlinModule())
            .build()

    /**
     * Parses every currently-published, upcoming event from the page-data response [json].
     *
     * @param json the raw JSON body of the `/page-data/<page>/page-data.json` artefact.
     * @param sourceUrl the venue's own programme page, stored on every event — Zenner has no
     *   per-event detail pages, so this is the canonical link back to the source.
     * @return upcoming [ScrapedEvent]s (today onward) in rooms the venue currently publishes;
     *   empty if the payload is absent, unparseable, or carries no event nodes.
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
     * Reads the venue's per-room publish flags from the sibling `queryShowHidePlaces` node.
     *
     * Absent or unreadable flags degrade to [ZennerPlaceVisibility.ALL_VISIBLE] — failing
     * open, so a renamed field silently empties nothing; the worst case is importing a
     * programme the venue has hidden, which is far better than importing none at all.
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
     * Converts the node's UTC `eventDate` instant to the venue's own [BERLIN] wall clock, or
     * null when it is missing or unparseable. `OffsetDateTime` is used rather than `Instant`
     * so an explicitly-offset value (`…+02:00`) parses too, should the CMS ever emit one.
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
     * Types the event from Zenner's own `typeOfEvent` label, which for two of its values names
     * a *format or location* rather than a kind and needs resolving further.
     *
     * **"Open Air" in the Weingarten** is the venue's SIP! day-party series — every Open Air
     * the wine garden has ever hosted, and always a DJ line-up — so the room disambiguates the
     * label and the event is typed [PARTY][EventType.PARTY]. The *same* label in the
     * Biergarten deliberately is **not**: that room's Open Airs are ice-skating sessions
     * (`Eisdisko`, `Eislaufen`), a Fête de la Musique and a festival day, so a blanket
     * "Open Air means party" rule would mislabel as much as it fixed.
     *
     * **"Event"** is the venue's catch-all (a flea market, a wine tasting, a World Cup public
     * viewing) and says nothing about the kind, as does an Open Air anywhere else. Both fall
     * to [inferUnmarkedTitleType], which promotes only an unambiguous title cue and otherwise
     * leaves the event `OTHER`.
     *
     * A title that unambiguously names a festival is exempted from the Weingarten rule and
     * left on the `OTHER` path, because the `FESTIVAL` promotion in
     * [ScrapedEvent.toEventEntity] overrides only `CONCERT`/`OTHER` — a `PARTY` returned here
     * would suppress it.
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
     * Derives the lineup from the title, keyed off the event type Zenner itself assigned.
     *
     * Zenner titles are overwhelmingly *event* names — its programme is built from recurring
     * series ("NICE ONE", "Crossover", "SIP!") — so a performer is only ever read out of a
     * title that carries an explicit billing frame, never assumed from the title as a whole:
     * - **Concerts** — [concertHeadliners], the `"<series|promoter> presents:"` /
     *   `"<n> min w/"` frame plus an optional `", support:"` tail.
     * - **Parties** — [djsFromWithFrame], the `"<series> w/ <DJs>"` frame alone.
     * - **Everything else** (readings, the untyped catch-alls) — no artists.
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
     * Derives a concert's headliners from its title, after stripping the promoter/series
     * frame Zenner wraps them in.
     *
     * Even its concerts are billed under a series or promoter — "180 min w/ Barker (live)",
     * "Trinity presents: Nathan Fake", "Analogue Foundation presents Lyra Pramuk (live)".
     * Feeding those to [headlinersFromTitle] raw would mint "180 min w/ Barker" as an artist,
     * so [stripSeriesFrame] removes the frame first. An edition announced with no act at all
     * — a title that is *only* the series name ("180 MINUTES", "Analogue Foundation") —
     * yields no artists either ([isBareSeriesTitle]).
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
     * Flattens a Sanity Portable Text blob to plain text: each block's `children` spans are
     * concatenated, blocks become separate lines, and blank lines are dropped.
     *
     * Returns null when the blob is absent or carries no readable text — including the bare
     * `"."` placeholder Zenner leaves in the field for an event with no blurb, which is
     * neither a description nor a status signal. A blob is kept only once it contains a
     * letter or digit.
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
 * Whether this is an "Open Air" in the Weingarten — the pairing that identifies a SIP! day
 * party. Both halves are required: the label alone also covers the Biergarten's ice-skating
 * and festival days, and the room alone also hosts wine tastings and a reading.
 */
private fun isWeingartenOpenAir(
    label: String?,
    place: String?
): Boolean = label?.trim()?.lowercase() == OPEN_AIR_LABEL && place?.trim()?.lowercase() == WEINGARTEN_PLACE

/**
 * The promoter/series frame Zenner wraps a billed act in, up to and including the marker
 * that introduces the act:
 *  - `"<series|promoter> presents[:]"` / `"pres.[:]"` — "Trinity presents: Nathan Fake",
 *    "Analogue Foundation presents Lyra Pramuk (live)";
 *  - `"<n> min[utes] w/"` — the venue's "180 min w/ <act>" extended-set series.
 *
 * The `w/` alternative is deliberately anchored to a leading *duration* rather than any
 * leading text, because `w/` also joins two collaborating acts mid-title ("David August w/
 * MFO"), where it must not be treated as a frame.
 */
private val SERIES_FRAME_PATTERN =
    Regex("""^.+?\b(?:presents|pres\.)\s*:?\s+|^\d+\s*min(?:utes)?\s+w/\s*""", RegexOption.IGNORE_CASE)

/**
 * Strips a leading [SERIES_FRAME_PATTERN] promoter/series frame so only the billed act
 * remains, or returns null when nothing is left after it — a bare series edition ("180
 * MINUTES") announces no act at all and must mint no artist. A title with no frame is
 * returned unchanged: Zenner's plain concert titles ("Yeule", "KALI MALONE (Live)") name
 * the act directly.
 */
private fun stripSeriesFrame(title: String): String? = title.replaceFirst(SERIES_FRAME_PATTERN, "").trim().takeIf { it.isNotBlank() }

/**
 * Zenner's own recurring concert series, whose name is sometimes the *whole* title when an
 * edition is announced before its act is: "180 MINUTES" (the extended-set series otherwise
 * billed "180 min w/ <act>") and "Analogue Foundation" (the label series otherwise billed
 * "Analogue Foundation presents: <act>"). Both are matched here — the numeric series
 * structurally, the named one by an entry — so the series name is never minted as a
 * performer. Comparison is case- and whitespace-insensitive.
 */
private val BARE_SERIES_TITLES: Set<String> = setOf("analogue foundation")

/** The "180 min" / "180 MINUTES" series name standing alone as a whole title, with no act billed after it. */
private val BARE_DURATION_SERIES_PATTERN = Regex("""^\d+\s*min(?:utes)?$""", RegexOption.IGNORE_CASE)

/**
 * True when [title] is nothing but one of Zenner's recurring series names (see
 * [BARE_SERIES_TITLES]) — an edition with no act billed, so no artist can be derived
 * from it.
 */
private fun isBareSeriesTitle(title: String): Boolean {
    val normalized = title.trim().replace(WHITESPACE, " ")
    return BARE_DURATION_SERIES_PATTERN.matches(normalized) || normalized.lowercase() in BARE_SERIES_TITLES
}

/**
 * The `"<series> w/ <acts>"` guest-billing frame, up to and including the `w/` marker.
 *
 * This is the *only* frame a party title is mined for. A `w/` names a guest joining the
 * night ("SIP! w/ Coco Maria"), so whatever follows it is a person. Zenner's other party
 * frame, `"<promoter> presents <x>"`, is deliberately **not** used here: its tail is as
 * often an event name as an act ("Gene On Earth presents Rave 'n' Cruise"), and minting
 * that would put a party's name in the artist table.
 */
private val WITH_FRAME_PATTERN = Regex("""^.+?\bw/\s*""", RegexOption.IGNORE_CASE)

/**
 * A set-length note Zenner appends to a guest DJ's billing — "(All Day Long)". A format
 * annotation, not part of the name, and not covered by the shared [stripArtistSuffix]
 * (which knows `(live)`, `(DJ-Set)` and the like). Curated to the forms the venue actually
 * uses rather than stripping every trailing parenthesis, so a parenthesised alias in an act's
 * name survives.
 */
private val SET_LENGTH_NOTE_PATTERN =
    Regex("""\s*\(\s*all\s+(?:day|night)\s+long\s*\)\s*$""", RegexOption.IGNORE_CASE)

/**
 * Derives a party's DJ line-up from a `"<series> w/ <DJs>"` title — "SIP! w/ Haseeb Iqbal
 * (All Day Long)" → `[Haseeb Iqbal (DJ)]`.
 *
 * Returns an empty list when the title carries no `w/` frame, which is the common case: a
 * bare series edition ("SIP!", "SIP! Closing", "NICE ONE") announces no guest, and its name
 * is the event's, not a performer's. Co-billed guests are split on the shared separators,
 * the set-length note and any tour/format suffix are stripped, and non-artists are dropped.
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
 * Splits a title on its `", support:"` marker into the headline text and the support act
 * names billed after it. Returns the title unchanged with no support acts when the marker
 * is absent.
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
 * One event in the `queryKultur.nodes` array, mapped from its JSON by Jackson.
 *
 * Only the fields Zenner populates are declared; unknown keys (the image's base64 blur
 * placeholder, srcSets, Portable Text mark definitions) are ignored. Every field is
 * nullable so a partial or evolving payload deserializes cleanly and is validated in
 * [ZennerApiScraper] instead.
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
 * The venue's per-room publish flags, from the `queryShowHidePlaces` node.
 *
 * Zenner toggles whole rooms' programmes on and off from the CMS; a room whose flag is
 * `false` renders no events on the site, so importing it would publish a programme the
 * venue has deliberately withheld. Note the payload's own misspelling of the Weingarten
 * flag (`wiengartenShow`), and that the Klub's programme flag is `klubShowProgramm` —
 * distinct from the sibling `klubShowLocation` / `klubShowMieten` flags, which govern
 * unrelated page sections and are not read here.
 *
 * Every flag defaults to `true` so an unknown or absent value fails open (see
 * [ZennerApiScraper.parseVisibility]).
 */
internal data class ZennerPlaceVisibility(
    val saalShow: Boolean = true,
    val klubShowProgramm: Boolean = true,
    val biergartenShow: Boolean = true,
    val wiengartenShow: Boolean = true
) {
    /**
     * Whether the venue currently publishes the programme of [place]. An unrecognized or
     * absent place has no flag to consult and is treated as published.
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
