package de.norm.events.scraper.schokoladen

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.hasSoldOutMarker
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.splitSegmentOnConjunctions
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalTime

/**
 * Pure HTML parser for Schokoladen Mitte's Laravel-based event overview page.
 *
 * Each listing page (`/`, `?page=N`) holds ten events with details inline. Each is a `div.event`
 * split across two `div.container` children: a collapsible header (category, date, promoter, title,
 * subtitle) and a `div.event-info` body (times, ticket link, description, image carousel).
 * Events are addressed only by page fragment (`#e20260711`), not a URL — no detail fetch, a
 * single-page importer.
 *
 * `div.event-info` carries a machine-readable `data-event-date` (ISO 8601, `2026-07-11`) and a
 * matching `id` (`e<yyyymmdd>`) — the most stable targets: no year inference, and the fragment
 * id is a stable `sourceId`.
 *
 * @see SchokoladenWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.schokoladen-mitte.de/">Schokoladen Mitte</a>
 */
class SchokoladenOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from one listing page.
     *
     * @param baseUrl the URL the document was fetched from, for resolving relative links.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val eventBlocks = document.select("div.event")
        logger.info { "Found ${eventBlocks.size} event block(s) on page" }

        return eventBlocks.mapSkippingFailures(logger, "Schokoladen event block") { block ->
            parseEvent(block, baseUrl)
        }
    }

    /**
     * Parses one `div.event` into a [ScrapedEvent], or `null` when title, date or fragment id is missing.
     */
    @Suppress("ReturnCount") // Null-safe early exits for each required field are clearer than nested let-chains
    private fun parseEvent(
        block: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val title = block.textAt("h2.fw-bold")
        if (title.isNullOrBlank()) {
            logger.warn { "Schokoladen event block has no title, skipping" }
            return null
        }

        val info = block.selectFirst(".event-info")
        val eventId = info?.id()?.takeIf { it.isNotBlank() }
        if (eventId == null) {
            logger.warn { "Event '$title' has no fragment id, skipping" }
            return null
        }

        val eventDate = info.attr("data-event-date").takeIf { it.isNotBlank() }?.let { parseIsoDate(it) }
        if (eventDate == null) {
            logger.warn { "Could not parse event date for '$title', skipping" }
            return null
        }

        // The site addresses each event by page fragment; that fragment is its canonical, stable identity.
        val sourceUrl = resolveUrl(baseUrl, "#$eventId")

        val eventType = mapEventType(block.textAt("h6.category"), CATEGORY_SYNONYMS)
        val subtitle = block.textAt("h6.subtitle")
        val (doorsTime, startTime) = parseTimes(info, block)
        val soldOut = isSoldOut(title, subtitle, info)

        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            description = parseDescription(info),
            eventType = eventType,
            eventDate = eventDate,
            doorsTime = doorsTime,
            startTime = startTime,
            imageUrl = parseImageUrl(info, baseUrl),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.SCHOKOLADEN.sourceIdPrefix}$eventId",
            // The page keeps the shop button on a sold-out show; its own words are "Tickets on the Doors".
            ticketUrl = block.hrefAt("a.ticket-btn").takeUnless { soldOut },
            genre = parseGenre(title),
            soldOut = soldOut,
            artists = parseArtists(title, subtitle, eventType),
            promoters = parsePromoters(block)
        )
    }

    /**
     * Whether the venue marked the entry sold out — "---> Ausverkauft / Sold Out / 10 Tickets on
     * the Doors at 19:00 <---" as the subtitle, repeated as a description paragraph (#1495), or a
     * bracketed title note, "[ausverkauft]" / "[pre-sale sold out, 10 tix on the doors]". The banner
     * is matched at its own start, so a bio mentioning a record that "sold out in a record 3 months"
     * does not mark the night sold out.
     */
    private fun isSoldOut(
        title: String,
        subtitle: String?,
        info: Element?
    ): Boolean =
        hasSoldOutMarker(title) ||
            SOLD_OUT_BANNER.containsMatchIn(subtitle.orEmpty()) ||
            info?.select(".event-description p")?.any { SOLD_OUT_BANNER.containsMatchIn(it.text()) } == true

    /**
     * The genres the title's per-act "(genre, origin)" annotations name, joined for the shared
     * normalizer — "1000 Rabbits (art-pop, uk) + Lande Hekt (indie-pop/songwriter, uk)" →
     * "art-pop, indie-pop/songwriter" (#1495). The origin is not a genre (#314): an annotation with
     * more than one comma-separated part loses its last, or its first when that is the country or
     * city code ("(Bln, punk)"), and a code or a spelled-out place is dropped wherever it stands.
     * A one-part annotation is a genre unless it is a code ("(SWE)").
     */
    private fun parseGenre(title: String): String? =
        GENRE_PARENTHETICAL
            .findAll(title)
            .flatMap { genresFromAnnotation(it.groupValues[1]) }
            .distinct()
            .joinToString(", ")
            .takeIf { it.isNotBlank() }

    /**
     * Doors and show times from the event-facts "Time" line: free text under a
     * `<strong>Time</strong>` label, varying — `"doors 19:00 - show 20:00"`, `"Doors 19h / Show
     * 20h"`, `"Einlass 19h Beginn 20h"`, `"Einlass: 18:30 Uhr"`, `"19:00 Einlass - 20:00 Konzert -
     * 22:00 DJ-Set"`. [timeBesideLabel] picks the two labelled times; the header's `span.d-none`
     * (`"19:00 Uhr"`) is a doors fallback when the line has no parseable time.
     */
    private fun parseTimes(
        info: Element?,
        block: Element
    ): Pair<LocalTime?, LocalTime?> {
        val timeText = info?.textAt(".event-facts p:has(strong:contains(Time)) span").orEmpty()
        val doors = timeBesideLabel(timeText, DOORS_LABEL_FIRST, DOORS_TIME_FIRST) ?: flexTime(HEADER_TIME_PATTERN.find(block.textAt("span.d-none").orEmpty()))
        val start = timeBesideLabel(timeText, SHOW_LABEL_FIRST, SHOW_TIME_FIRST)
        return doors to start
    }

    /**
     * The event image from the media column: a Bootstrap carousel (first `.carousel-item` is
     * `active`) or a single `.imageWrapper`; either way the first `<img>` is the primary image.
     * Sources are site-relative (`/media/images/…`), resolved against [baseUrl].
     */
    private fun parseImageUrl(
        info: Element?,
        baseUrl: String
    ): String? {
        val src = info?.attrAt(".col-md-9.offset-md-3 img", "src") ?: return null
        return resolveUrl(baseUrl, src)
    }

    /**
     * The paragraphs of `.event-description` joined newline-separated, or `null`.
     */
    private fun parseDescription(info: Element?): String? {
        val description = info?.selectFirst(".event-description") ?: return null
        return description
            .select("p")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
    }

    /**
     * The promoters from the header's `span.promoter`. The span is cut at its "presents" word or at a
     * ": " tagline, because the night's own billing may follow (`"m:soundtrack prsnts: Falling into
     * Autumn: Dreamy Indie"`). Co-promoters are joined by ",", "&" and "+" (`"beav boloney, wild wax &
     * little league shows prsnt:"`), each its own promoter row (#328). [JOINED_PROMOTERS] carry an
     * "&" in their name and stay whole.
     */
    private fun parsePromoters(block: Element): List<String> {
        val credit =
            block
                .textAt("span.promoter")
                ?.replace(PRESENTS_TAIL, "")
                ?.replace(TAGLINE_TAIL, "")
                ?: return emptyList()
        return PROMOTER_TOKEN
            .findAll(credit)
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .map { PROMOTER_ABBREVIATIONS[it.lowercase()] ?: it }
            .toList()
    }

    /**
     * Artist entries from the title (headliner) plus any support acts. A "(genre, origin)"
     * annotation follows each act (`"MOLOCH (punk, bln) + PINK WONDER (scumpunk, bln)"`), stripped
     * before the shared [buildArtistsForEventType][de.norm.events.scraper.buildArtistsForEventType]
     * co-bill splitter runs, so headliners come out clean (`MOLOCH`, `PINK WONDER`). A bracketed
     * note ("[ausverkauft]") is the venue's, not the act's, and goes the same way, as does a
     * trailing "& more". A `"<night> mit <acts>"` / `"<night> w/ <acts>"` title bills the acts only
     * (`15 Jahre Gut Drauf – … mit mOck & Petula`). The title is stored verbatim — only the derived
     * names are cleaned. Non-concert events (readings, specials) yield no artists unless a
     * "Support:" line is present. The subtitle's aftershow DJs follow, from [aftershowDjs].
     */
    private fun parseArtists(
        title: String,
        subtitle: String?,
        eventType: String?
    ): List<ScrapedArtist> {
        val artistTitle =
            title
                .replace(GENRE_PARENTHETICAL, " ")
                .replace(TITLE_NOTE, " ")
                .replace(WHITESPACE, " ")
                .replace(MORE_ACTS_TAIL, "")
                .replace(RELEASE_SHOW_OF, "")
                .replace(QUOTED_WORK, "")
                .trim()
        return buildArtistsForEventType(artistTitle, subtitle, eventType, unpackWithFrame = true) + aftershowDjs(subtitle)
    }

    /**
     * The time beside a label, whichever side the venue wrote it: "Einlass 19h" and "19:00
     * Einlass" both name the doors. Label-first is tried first, then time-first (#1141).
     */
    private fun timeBesideLabel(
        text: String,
        labelFirst: Regex,
        timeFirst: Regex
    ): LocalTime? = flexTime(labelFirst.find(text)) ?: flexTime(timeFirst.find(text))

    /** A [LocalTime] from a doors/show regex match whose groups are (hour, optional minute), or `null`. */
    private fun flexTime(match: MatchResult?): LocalTime? {
        val hour = match?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val minute = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        return runCatching { LocalTime.of(hour, minute) }.getOrNull()
    }

    companion object {
        /** Venue category labels the shared [mapEventType] table doesn't cover. "Musik" is live music → CONCERT. */
        private val CATEGORY_SYNONYMS = mapOf("musik" to "CONCERT")

        /** A "(genre, origin)" annotation appended to each act in a title, stripped before artist derivation and read for [parseGenre]. */
        private val GENRE_PARENTHETICAL = Regex("""\s*\(([^)]*)\)""")

        /** A bracketed venue note in a title — "[ausverkauft]", "[pre-sale sold out, 10 tix on the doors]". */
        private val TITLE_NOTE = Regex("""\s*\[([^\]]*)]""")

        /** A trailing "& more" / "+ more": the co-bill split takes the joiner and would bill "more", which is also a band's name. */
        private val MORE_ACTS_TAIL = Regex("""\s*[&+]\s*more\s*$""", RegexOption.IGNORE_CASE)

        /** The night's name before its act: "Berlin Release Show von Angela Aux" bills Angela Aux (#2099). */
        private val RELEASE_SHOW_OF =
            Regex("""^.*?\brelease(?:[\s-]*(?:show|party|konzert))?\s+(?:von|of|by)\s+""", RegexOption.IGNORE_CASE)

        /** A quoted album after `w/`: `Angela Aux w/ "Das Nichts macht Kopien von sich selbst"` names a work, not an act. */
        private val QUOTED_WORK = Regex("""\s+w/\s*["„“][^"„“”]*["“”]""", RegexOption.IGNORE_CASE)

        /** The sold-out banner, at the start of the subtitle or of a description paragraph: "---> Ausverkauft / Sold Out / …". */
        private val SOLD_OUT_BANNER = Regex("""^\W*(?:ausverkauft|sold\s*out)\b""", RegexOption.IGNORE_CASE)

        /** Promoters whose own name carries an "&", which would otherwise split them. */
        private val JOINED_PROMOTERS = listOf("""thirsty\s*&\s*miserable""", """old\s*&\s*fast\b""")

        /** One promoter inside a span: a [JOINED_PROMOTERS] name, or a run between ",", "&" and "+". */
        private val PROMOTER_TOKEN = Regex("""\s*(?:${JOINED_PROMOTERS.joinToString("|")})|[^,&+]+""", RegexOption.IGNORE_CASE)

        /** The venue's short forms for a promoter it credits in full elsewhere. */
        private val PROMOTER_ABBREVIATIONS = mapOf("lls" to "little league shows")

        /** The "presents" word and anything after it — "prsnts:", "present:", "pres.:", "prsnts a … festival:". */
        private val PRESENTS_TAIL = Regex("""\s*\b(?:presents?|prsnts?|pres\.?)(?!\w).*$""", RegexOption.IGNORE_CASE)

        /** A tagline after the name, "m:soundtrack: bridging the psychedelic traditions …"; a colon inside a name has no space after it. */
        private val TAGLINE_TAIL = Regex(""":(?:[\s\u00A0].*)?$""")

        /** The words the venue labels its doors time with. */
        private const val DOORS_WORDS = """(?:doors|einlass)"""

        /** The words the venue labels its start time with; "Konzert" is the house format's own ("19:00 Einlass - 20:00 Konzert - 22:00 DJ-Set"). */
        private const val SHOW_WORDS = """(?:show|beginn|konzert|start)"""

        /** An hour with an optional `:mm`, the two capture groups [flexTime] reads. */
        private const val CLOCK = """(\d{1,2})(?:[:.](\d{2}))?"""

        /** Doors time after its label — "Einlass: 18:30 Uhr", "Einlass 19h". */
        private val DOORS_LABEL_FIRST = Regex("""$DOORS_WORDS\s*:?\s*$CLOCK""", RegexOption.IGNORE_CASE)

        /** Doors time before its label — "19:00 Einlass", "19h Einlass". */
        private val DOORS_TIME_FIRST = Regex("""$CLOCK\s*(?:h|uhr)?\s+$DOORS_WORDS""", RegexOption.IGNORE_CASE)

        /** Show time after its label — "Beginn 20h", "Konzert: 20:00". */
        private val SHOW_LABEL_FIRST = Regex("""$SHOW_WORDS\s*:?\s*$CLOCK""", RegexOption.IGNORE_CASE)

        /** Show time before its label — "20:00 Konzert", "20h Beginn". */
        private val SHOW_TIME_FIRST = Regex("""$CLOCK\s*(?:h|uhr)?\s+$SHOW_WORDS""", RegexOption.IGNORE_CASE)

        /** Header `span.d-none` time ("19:00 Uhr"), used as a doors fallback. */
        private val HEADER_TIME_PATTERN = Regex("""(\d{1,2}):(\d{2})""")
    }
}

/** The genres one "(genre, origin)" annotation names, with the origin left out — see [SchokoladenOverviewPageScraper.parseGenre]. */
private fun genresFromAnnotation(annotation: String): List<String> {
    val parts = annotation.split(',').map { it.trim() }.filter { it.isNotBlank() }
    val genres =
        when {
            parts.size < 2 -> parts
            isOrigin(parts.last()) -> parts.dropLast(1)
            isOrigin(parts.first()) -> parts.drop(1)
            else -> parts.dropLast(1)
        }
    return genres
        .map { part ->
            part
                .split('/')
                .map { it.trim() }
                .filterNot { it.isBlank() || isOrigin(it) }
                .joinToString("/")
        }.filter { it.isNotBlank() }
}

/**
 * A two- or three-letter country or city code, possibly two of them ("bln/aus"), or a place the
 * venue spells out. A [SHORT_GENRES] word has the shape of a code and is not one: `Beat/Mod` lost
 * its `Mod` (#2327).
 */
private fun isOrigin(part: String): Boolean = (ORIGIN_CODE.matches(part) && part.lowercase() !in SHORT_GENRES) || part.lowercase() in ORIGIN_PLACES

/** A country or city code inside an annotation — "uk", "bln", "aus", "bln/tlv". */
private val ORIGIN_CODE = Regex("""[a-z]{2,3}(?:\s*/\s*[a-z]{2,3})*""", RegexOption.IGNORE_CASE)

/** Genres of two or three letters, which [ORIGIN_CODE] would take for a code: `(Beat/Mod)`, `(Ska)`. */
private val SHORT_GENRES = setOf("mod", "ska", "emo", "pop", "dub", "rap")

/** The places the venue spells out instead of coding, as met in its titles. */
private val ORIGIN_PLACES = setOf("berlin", "hamburg", "bernau", "aachen", "leipzig", "warsaw", "basque country", "ho chi minh city")

/**
 * The DJs a subtitle bills after the show (#2327): `& after 22h: DJ Mietze Fiebels (post-punk /
 * …)`, `& after 22h: Under the Wires w/ DJs Mr Cigarette Butt & Moppi Galoppi`, `+ DJ
 * Calamidades Lola`. The `(genre)` notes go, and so does a night's name before `w/`. Each name
 * loses its `DJ`/`DJs` label or its `DJ-Set`/`DJ night`/`DJ-Team`/`DJs` suffix
 * (`Everything Crash DJ-Set`), and an `aka` alias. A `/` stays inside a name: `fragil/stabil`
 * is one night. A subtitle without the lead-in is not read.
 */
private fun aftershowDjs(subtitle: String?): List<ScrapedArtist> {
    val lineup = AFTERSHOW_LEAD_IN.find(subtitle.orEmpty())?.groupValues?.get(1) ?: return emptyList()
    val names =
        lineup
            .replace(DJ_NOTE, "")
            .replace(GUEST_TAIL, "")
            .let { it.substringAfter(NIGHT_WITH, it) }
    return names
        .split(DJ_HARD_SEPARATOR)
        .flatMap { splitSegmentOnConjunctions(it) }
        .map { name ->
            name
                .split(ALIAS)
                .first()
                .replace(DJ_LABEL, "")
                .replace(DJ_SUFFIX, "")
                .trim()
        }.filterNot { it.isBlank() || isNonArtistName(it) }
        .distinct()
        .map { ScrapedArtist(name = it, role = "DJ") }
}

/** The aftershow lead-in, capturing the lineup after it: `& after 22h:`, or a `+` straight before a `DJ` label. */
private val AFTERSHOW_LEAD_IN =
    Regex("""^\W*(?:after\s+\d{1,2}(?::\d{2})?\s*h?\s*:|\+\s*(?=djs?\s))\s*(.+)$""", RegexOption.IGNORE_CASE)

/** A `(genre)` note after a DJ, closed or cut off by the subtitle's end. */
private val DJ_NOTE = Regex("""\s*\([^)]*(?:\)|$)""")

/** An unnamed guest slot at the end of the lineup, `& Guests`; a collective rule would keep it on the name before. */
private val GUEST_TAIL = Regex("""\s*[&+]\s*guests?\s*$""", RegexOption.IGNORE_CASE)

/** A night's name before its DJs: `Under the Wires w/ DJs …`. */
private const val NIGHT_WITH = " w/ "

/** Comma and plus always part two DJs; a slash does not, because `fragil/stabil` is one name. */
private val DJ_HARD_SEPARATOR = Regex("""\s*[,+]\s*""")

/** The `DJ` / `DJs` label in front of a name. */
private val DJ_LABEL = Regex("""^djs?\s+""", RegexOption.IGNORE_CASE)

/** A format word after a name: `Everything Crash DJ-Set`, `fragil/stabil DJ night`, `Fish'n'Candy DJ-Team`, `Kreuzgut.Disko DJs`. */
private val DJ_SUFFIX = Regex("""\s+dj(?:s|[\s-]?(?:set|team|night))?$""", RegexOption.IGNORE_CASE)

/** A second name for the same DJ, `DJ Don Kamisi aka Schapur`; the first one is kept. */
private val ALIAS = Regex("""\s+a\.k\.a\.?\s*|\s+aka\s+""", RegexOption.IGNORE_CASE)
