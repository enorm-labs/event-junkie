package de.norm.events.scraper.tempodrom

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.decodeHtmlEntities
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.isIceShow
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.jsonLdEvents
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.schemaDate
import de.norm.events.scraper.schemaImageUrl
import de.norm.events.scraper.schemaName
import de.norm.events.scraper.schemaSoldOut
import de.norm.events.scraper.schemaStatus
import de.norm.events.scraper.schemaTime
import de.norm.events.scraper.splitSupportActs
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode
import java.math.BigDecimal

/**
 * Pure parser for Tempodrom's programme, read from the schema.org JSON-LD its listing page embeds.
 *
 * `/programm-und-tickets/` carries the **entire** programme as one
 * `<script type="application/ld+json">` array of `Event` objects — 145 at the time of writing —
 * each with `startDate`, `doorTime`, `image`, `description`, `eventStatus` and an `offers`
 * block. The rendered cards add nothing, so the structured data is the source and the markup is
 * never selected against (ADR-007 §"Selector Strategy" priority 1).
 *
 * Two fields are not taken at face value. `performer.name` is always a copy of the event `name`,
 * not an act, so it is ignored and artists derive from the title as for any concert hall. And
 * `location.name` is always "Tempodrom Berlin". The room is the last part of `description`
 * instead ("Germany Tour | Kleine Arena"), so it moves from the subtitle to `room` ([splitRoom], #3380).
 *
 * Tempodrom publishes no category, and about a third of its programme is comedy or Kabarett. The
 * `description` line names the format when the venue bills it as comedy ("COMEDY - Clubtour 2026",
 * "“Comedy Perle”"), so a whole-word "comedy" there types the night [EventType.COMEDY]
 * ([tempodromEventType], #2314). A comedian billed by name alone ("Dieter Nuhr" / "Live 2026")
 * carries no cue, so the night is a fallback `CONCERT` its comedian headliner retypes (ADR-039).
 *
 * **Sport is dropped** (`docs/EVENT_SCOPE.md` §3.1), esports included: the snooker German Masters
 * and a GeoGuessr championship would default to `CONCERT`. With no taxonomy to read, the title or
 * format line naming a sport is the cue ([isTempodromSport], #2470, #2478).
 *
 * The JSON-LD strings are HTML-escaped and script content is not decoded by Jsoup, so `name`
 * and `description` go through [decodeHtmlEntities] before anything touches them — see that
 * function for why decoding late would be too late.
 *
 * @see TempodromWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.tempodrom.de/programm-und-tickets/">Tempodrom programme</a>
 */
class TempodromOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every event from the listing page's JSON-LD; empty when the page carries no
     * parseable schema.org `Event` data.
     */
    fun scrape(document: Document): List<ScrapedEvent> {
        val nodes = document.jsonLdEvents()
        logger.info { "Found ${nodes.size} schema.org Event object(s) on the Tempodrom programme" }

        val parsed =
            nodes.mapSkippingFailures(logger, "Tempodrom event") { node ->
                parseEvent(node)
            }
        val (sport, ours) = parsed.partition(::isTempodromSport)
        if (sport.isNotEmpty()) {
            logger.info { "Skipping ${sport.size} Tempodrom sport fixture(s): ${sport.joinToString { it.sourceId }}" }
        }
        return ours
    }

    /** Maps one schema.org `Event` onto a [ScrapedEvent], or `null` without a name or date. */
    @Suppress("ReturnCount") // Guard clauses for the required name/date are clearer than nesting
    private fun parseEvent(event: JsonNode): ScrapedEvent? {
        val title = event.schemaName()?.let(::cleanEventTitle) ?: return null
        val eventDate = event.schemaDate("startDate") ?: return null
        // Every event carries an `endDate`, and 140 of 145 repeat the start date without a time — a
        // same-day, date-only end says nothing. The five that differ are the runs: a circus over
        // Christmas, a snooker week, Holiday on Ice (ADR-029).
        val endDate = event.schemaDate("endDate")?.takeIf { it > eventDate || event.schemaTime("endDate") != null }

        val url = event.path("url").asString("").trim()
        val (subtitle, room) =
            splitRoom(
                event
                    .path("description")
                    .asString(null)
                    ?.let(::decodeHtmlEntities)
            )
        val production = tempodromProduction(title, subtitle)
        val eventType = production?.eventType?.name ?: tempodromEventType(title, subtitle)
        val offers = event.path("offers")
        val (presale, priceNote) = parsePrices(offers)

        return ScrapedEvent(
            title = title,
            // The venue's `description` is the tour or edition name ("The Ca$ino Tour", "Jungle Vibes
            // Edition"), not a blurb — it belongs in the subtitle.
            subtitle = subtitle,
            room = room,
            eventType = eventType,
            // CONCERT is the default unless a production cue typed it (ADR-039).
            typeIsFallback = production == null && eventType == EventType.CONCERT.name,
            eventDate = eventDate,
            // `doorTime` is a full timestamp of its own; only its clock part is wanted.
            doorsTime = event.schemaTime("doorTime"),
            // A multi-day run publishes a date-only `startDate`, so it simply has no start time.
            startTime = event.schemaTime("startDate"),
            endDate = endDate,
            endTime = endDate?.let { event.schemaTime("endDate") },
            imageUrl = event.schemaImageUrl(),
            sourceUrl = url,
            sourceId = "${EventSource.TEMPODROM.sourceIdPrefix}${extractEventSlug(url, EVENT_PATH_PREFIX)}",
            ticketUrl = offers.path("url").asString(null)?.takeIf { it.startsWith("http") && it != url },
            pricePresale = presale,
            priceNote = priceNote,
            soldOut = event.schemaSoldOut(),
            status = event.schemaStatus(),
            artists = production?.artists ?: tempodromArtists(title, subtitle, eventType)
        )
    }

    /**
     * The format line and the room the venue's `description` ends in: "Germany Tour | Kleine Arena"
     * is `Germany Tour` in `Kleine Arena`. A description that is only the room has no format line.
     */
    private fun splitRoom(description: String?): Pair<String?, String?> {
        val text = description.blankToNull()
        val room = text?.let(ROOM_SUFFIX::find) ?: return text to null
        return text.substring(0, room.range.first).blankToNull() to room.groupValues[1]
    }

    /**
     * The cheapest ticket price and, when the offer spans a range, a note recording it. `offers`
     * publishes `price` and `lowPrice` identically (the cheapest tier) plus a `highPrice`; 68 of
     * the 86 priced events span a range, so the low price alone would understate what most seats
     * cost — the range is kept verbatim in the note. No `offers`, or only an availability, yields neither.
     */
    private fun parsePrices(offers: JsonNode): Pair<BigDecimal?, String?> {
        val low = parseDecimal(offers.path("lowPrice").asString(null) ?: offers.path("price").asString(null))
        val high = parseDecimal(offers.path("highPrice").asString(null))
        val currency = offers.path("priceCurrency").asString(DEFAULT_CURRENCY)
        val note = if (low != null && high != null && high > low) "$low – $high $currency" else null
        return low to note
    }

    /**
     * Parses a JSON-LD money value such as `"65.00"`. Not
     * [parsePriceValue][de.norm.events.scraper.parsePriceValue]: that reads a *rendered* price and
     * requires the `€` sign this machine-readable field does not carry.
     */
    private fun parseDecimal(value: String?): BigDecimal? = value.blankToNull()?.let { runCatching { BigDecimal(it) }.getOrNull() }

    /**
     * Whether the title or the format line names a sport: "Snooker" / "German Masters 2027". Whole
     * words only, and no tournament word on its own — "Masters" also bills music ("Masters of Rock"),
     * and "World Championship" a dance or a choir contest.
     */
    private fun isTempodromSport(event: ScrapedEvent): Boolean = listOfNotNull(event.title, event.subtitle).any { SPORT.containsMatchIn(it) }

    private companion object {
        /** Path prefix of a Tempodrom event permalink, stripped to obtain the slug identity. */
        const val EVENT_PATH_PREFIX = "/event/"

        /** One of the two rooms, as the whole description or after its last `|`: "… | Kleine Arena". */
        val ROOM_SUFFIX = Regex("""(?:^|\s*\|\s*)((?:Große|Grosse|Kleine)\s+Arena)\s*$""", RegexOption.IGNORE_CASE)

        /** Currency assumed when an offer omits one; every Tempodrom offer states EUR. */
        const val DEFAULT_CURRENCY = "EUR"

        /**
         * A sport the Tempodrom hosts or could host, as a word: the venue publishes no category to read
         * instead. Esports is sport, named by the word or by a game that is only ever a tournament here.
         */
        val SPORT =
            Regex(
                """\b(?:snooker|darts|billard|boxen|boxkampf|tischtennis|e-?sports?|geoguessr)\b""",
                RegexOption.IGNORE_CASE
            )
    }
}

/**
 * [EventType.COMEDY] when the venue's format line says "comedy" as a word, [EventType.SHOW] when
 * it bills an ice show ("Musik-Show auf Eis"), else the shared concert-hall rule on the title.
 * Beyond the production cues of [tempodromProduction], no other cue is read from the subtitle: the
 * line is also a tour name, and "Die beste Wolfgang Petry Party" is a tribute show, not a party.
 */
private fun tempodromEventType(
    title: String,
    subtitle: String?
): String =
    when {
        subtitle != null && COMEDY_FORMAT.containsMatchIn(subtitle) -> EventType.COMEDY.name
        isIceShow(title, subtitle) -> EventType.SHOW.name
        else -> inferConcertVenueType(title)
    }

/** A night billed by a production's name, so its title is no act: its type, and the acts its format line names (#3162). */
private data class TempodromProduction(
    val eventType: EventType,
    val artists: List<ScrapedArtist> = emptyList()
)

/**
 * The production [title] and [subtitle] bill, or `null` for an act's night. A film-music night is a
 * concert; a musical, a film with live orchestra, a gala or a tribute concert is a show. The
 * orchestra a film names after `mit dem` plays it, so it is billed.
 */
private fun tempodromProduction(
    title: String,
    subtitle: String?
): TempodromProduction? =
    when {
        listOfNotNull(title, subtitle).any { FILM_MUSIC.containsMatchIn(it) } -> TempodromProduction(EventType.CONCERT)

        subtitle != null && PRODUCTION_FORMAT.containsMatchIn(subtitle) -> TempodromProduction(EventType.SHOW, filmOrchestra(subtitle))

        // Loriot died in 2011: a night under his name stages his sketches, and the cast is not billed.
        LORIOT.matches(title) -> TempodromProduction(EventType.SHOW)

        // A dance congress is days of workshops and parties, not a concert: "Berlin Salsacongress 2027".
        DANCE_CONGRESS.containsMatchIn(title) -> TempodromProduction(EventType.OTHER)

        else -> null
    }

/** The orchestra after `mit dem`, in the nominative: "mit dem Deutschen Filmorchester Babelsberg". */
private fun filmOrchestra(subtitle: String): List<ScrapedArtist> =
    FILM_ORCHESTRA
        .find(subtitle)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.takeUnless { BACKING.matches(it) }
        ?.replace(DATIVE_ADJECTIVE, "$1es")
        ?.let { listOf(ScrapedArtist(it, "HEADLINER")) }
        .orEmpty()

/**
 * The title is the act at Tempodrom, comedy included: "Oliver Polak" / "COMEDY - Clubtour 2026".
 * A comedy night is billed like a concert without the format line, which names no support act.
 * The shared comedy rule wants "<Performer> – <Show>" in the title, and Tempodrom never writes it.
 * A format line that opens with `mit` names the acts instead of the title. Two acts joined by `&`
 * lose the show after the last ` - ` first, or the second act keeps it: `"Beisenherz & Polak - Friendly
 * Fire"` billed `Polak - Friendly Fire` (#3415).
 */
private fun tempodromArtists(
    title: String,
    subtitle: String?,
    eventType: String
): List<ScrapedArtist> {
    val acts = tempodromActs(title)
    return subtitle?.let(::castFromFormatLine)?.takeIf { it.isNotEmpty() }
        ?: if (eventType == EventType.COMEDY.name) {
            buildArtistsForEventType(acts, subtitle = null, eventType = EventType.CONCERT.name)
        } else {
            buildArtistsForEventType(acts, subtitle, eventType)
        }
}

/** [title] without the ` - <show>` after a pair of acts joined by `&`; any other title unchanged. */
internal fun tempodromActs(title: String): String = ACTS_THEN_SHOW.matchEntire(title.trim())?.groupValues?.get(1) ?: title

/** Acts joined by a spaced `&`, then the last spaced hyphen and the show after it. */
private val ACTS_THEN_SHOW = Regex("""(.+?\s&\s.+)\s+-\s+[^-]+""")

/**
 * The acts a `mit <names>` format line bills, without a trailing `uvm.`: "mit Marc-Uwe Kling, Sarah
 * Bosetti uvm.". A backing band or orchestra is no act.
 */
private fun castFromFormatLine(subtitle: String): List<ScrapedArtist> =
    CAST_LINE
        .matchEntire(subtitle.trim())
        ?.groupValues
        ?.get(1)
        ?.replace(AND_MORE, "")
        ?.let(::splitSupportActs)
        ?.filterNot { isNonArtistName(it) || BACKING.matches(it) }
        ?.map { ScrapedArtist(it, "HEADLINER") }
        .orEmpty()

/** "comedy" as a word in Tempodrom's format line: "Comedy Perle", "MUSIK-COMEDY-STAND-UP-SHOW". */
private val COMEDY_FORMAT = Regex("""\bcomedy\b""", RegexOption.IGNORE_CASE)

/** A film-music night, in the title or the format line: "Die Nacht der Filmmusik". */
private val FILM_MUSIC = Regex("""\bfilmmusik\b""", RegexOption.IGNORE_CASE)

/**
 * A production's format line: "Das Musical", "Die Originalproduktion …", "Der Originalfilm …", "… Musicalgala …",
 * "Das Tribute-Konzert …". A bare "Musical" also bills a real act's tour ("A VERY SPECIAL MUSICAL CHRISTMAS TOUR"), so
 * the noun needs its article. "in Concert" is no cue: it also stands beside a real act.
 */
private val PRODUCTION_FORMAT =
    Regex(
        """\b(?:das|ein|the)\s+musical\b|\bmusicalgala\b|\boriginal(?:produktion|film)\b|\btribute[\s-]?konzert\b""",
        RegexOption.IGNORE_CASE
    )

/** The orchestra a film night names: "mit dem Deutschen Filmorchester Babelsberg". */
private val FILM_ORCHESTRA = Regex("""\bmit\s+dem\s+(\p{Lu}[^,|]*?(?i:orchester|orchestra)[^,|]*)""")

/** An adjective in the dative after `dem`, made nominative for a neuter orchestra: "Deutschen" → "Deutsches". */
private val DATIVE_ADJECTIVE = Regex("""\b(\p{Lu}\p{Ll}+)en\b(?=.*(?i:orchester))""")

/** Loriot's name as the whole title. */
private val LORIOT = Regex("""loriot""", RegexOption.IGNORE_CASE)

/** A dance congress, its dance compounded onto the word: "Salsacongress", "Tangokongress". */
private val DANCE_CONGRESS = Regex("""\b\p{L}+(?:congress|kongress)\b""", RegexOption.IGNORE_CASE)

/** A format line that bills its cast after a lowercase `mit`: "mit Marc-Uwe Kling, Sarah Bosetti uvm.". */
private val CAST_LINE = Regex("""mit\s+(\p{Lu}.*)""")

/** A trailing "and many more": `uvm.`, `u.v.m.`, `und viele mehr`. */
private val AND_MORE = Regex("""\s*(?:\bu\.?\s?v\.?\s?m\.?|\bund\s+viele\s+mehr)\s*$""", RegexOption.IGNORE_CASE)

/** A backing band or orchestra, which a cast line may name beside the acts. */
private val BACKING = Regex("""(?:live-?)?(?:band|orchester|orchestra|ensemble|chor|choir)""", RegexOption.IGNORE_CASE)
