package de.norm.events.scraper.panke

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.NIGHT_ENDS
import de.norm.events.scraper.RunningOrderClock
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.cssUrlValue
import de.norm.events.scraper.euroAmounts
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.knownGenresInStyleTail
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseLabelledPrices
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.splitSupportActs
import de.norm.events.scraper.stripArtistSuffix
import de.norm.events.scraper.textAt
import de.norm.events.scraper.textLines
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure HTML parser for Panke Culture's `/programme/` page — the Wedding club and gallery's whole
 * programme, server-rendered in one WordPress (Divi) page.
 *
 * Two lists from the same template, headed **UPCOMING EVENTS** and **PAST EVENTS**. Only the
 * first is read: the venue's own division is authoritative, and the past entries are waste.
 *
 * Each event is an `<article>` identified by its WordPress post id — no per-event page, the full
 * text expands inline, so every event's `sourceUrl` is the programme page. The date is an ISO
 * `data-date` attribute; the clock is prose ("The event takes place on the 5th of August
 * starting at 19:00."), sometimes with seconds and sometimes with no minutes at all
 * ("starting at 19.").
 *
 * **That prose clock is the doors, not the start, on an event that states both** (#1758). A
 * handful of bodies print a precise pair — "🕐 Doors 19:00 · Concert 21:00" — and on every one
 * of them the prose line repeats the Doors figure while the concert begins one or two hours
 * later. [parseTimes] reads the pair where it exists and falls back to the prose line, which is
 * the only clock the other events publish.
 *
 * **The lineup is read from Resident Advisor links, else from the title or a presenter line.** The bodies
 * are free prose with no convention, so an `ra.co/dj/…` anchor is the one unambiguous marker in a body. A
 * night that links none bills its title's `feat.` acts ([featuredActs]), a `+` bill whose every act carries
 * a `(live)` or a `[style]` tag ([markedBill]), or the title a body line presents ([presentedAct]). One with
 * none of these stores no artists. A `[style]` tag also gives the genre.
 *
 * Those links also type the event: no category is published, and titles are series names
 * rather than formats, so a billed DJ lineup is the best evidence of a club night. See [eventTypeOf].
 *
 * The price and the ticket link each sit in a labelled body line; see [parsePankePrice] and [pankeTicketUrl].
 *
 * @see PankeWebsiteImporter for the HTTP fetch orchestrator.
 */
class PankeProgrammePageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every upcoming event on the programme page.
     *
     * @param sourceUrl the URL the document was fetched from, stored as every event's `sourceUrl`.
     */
    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val articles = document.select("$UPCOMING_MODULE article[id]")

        val events =
            articles.mapSkippingFailures(logger, "Panke event article") { article ->
                parseArticle(article, sourceUrl)
            }
        logger.info { "Found ${events.size} upcoming event(s) on the Panke programme page" }
        return events
    }

    /** Parses one article into a [ScrapedEvent], or `null` without a date or title. */
    private fun parseArticle(
        article: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val postId = article.id().takeIf { it.isNotBlank() } ?: return null
        val eventDate = article.attrAt(".event-content-wrapper", "data-date")?.let { parseIsoDate(it) }
        if (eventDate == null) {
            logger.warn { "Panke article '$postId' states no date, skipping" }
            return null
        }
        val cleanedTitle = article.textAt("h2.entry-title")?.let { cleanEventTitle(it) }
        val title = cleanedTitle?.text
        if (title.isNullOrBlank()) {
            logger.warn { "Panke article '$postId' has no title, skipping" }
            return null
        }
        // The Sunday afternoon market is out of scope (EVENT_SCOPE.md §3.2).
        if (MARKET_TITLE.containsMatchIn(title)) return null
        val residentAdvisor = residentAdvisorLineup(article)
        val paragraphs = article.select("$BODY_COLUMN p")
        val presented = presentedAct(paragraphs.flatMap { it.textLines() }, title)
        val billed = residentAdvisor.ifEmpty { featuredActs(title) }.ifEmpty { markedBill(title) }.ifEmpty { presented }
        val lineup = withTimetable(billed, article, eventDate)
        val times = parseTimes(article)
        val price = parsePankePrice(paragraphs.flatMap { it.textLines() })
        val description = descriptionOf(article)

        return ScrapedEvent(
            title = title,
            description = description,
            // Only a linked lineup types the night: a `feat.` title bills a concert's guests as well as a DJ's.
            eventType = eventTypeOf(title, residentAdvisor, description, presented.isNotEmpty(), times.start),
            genre = knownGenresInStyleTail(STYLE_TAG.findAll(title).joinToString("/") { it.groupValues[1] }),
            eventDate = eventDate,
            doorsTime = times.doors,
            startTime = times.start,
            imageUrl = parseBackgroundImageUrl(article.attr("style")),
            pricePresale = price.presale,
            priceBoxOffice = price.boxOffice,
            priceNote = price.note,
            sourceUrl = sourceUrl,
            ticketUrl = pankeTicketUrl(paragraphs),
            // No per-event page, so the WordPress post id is the identity.
            sourceId = "${EventSource.PANKE.sourceIdPrefix}$postId",
            soldOut = cleanedTitle.soldOut,
            artists = lineup
        )
    }

    /**
     * The event's type; the venue states none, so two signals decide. An unmistakable title
     * keyword wins first — a rave is a club night. Where the title says nothing,
     * **an event that bills DJs on Resident Advisor is a club night**: the venue links a profile
     * only for the acts on its floor, a far better signal than the name, a series title rather
     * than a format. Then a live show is a concert: a title that names one (`… MIT LIVE BANDS`,
     * `… Tour`, `… Live at Panke`, #2398) or a body that bills `live bands`. Last, a `Disko` or
     * `Fiesta` title, or a body that names a dancefloor, is a club night (#2992). An act a promoter
     * presents by the title's name ([presentedAct]) is a concert. Last, a night that starts at
     * [LATE_START] or later is a club night: every such entry is a `Baile`, a `Fusion` or a DJ night.
     * An event with none of these stays `OTHER` rather than being guessed.
     */
    private fun eventTypeOf(
        title: String,
        lineup: List<ScrapedArtist>,
        description: String?,
        presented: Boolean,
        start: LocalTime?
    ): String {
        val fromTitle = inferUnmarkedTitleType(title)
        val body = description.orEmpty()
        return when {
            fromTitle != EventType.OTHER.name -> fromTitle
            lineup.isNotEmpty() -> EventType.PARTY.name
            LIVE_SHOW_TITLE.containsMatchIn(title) || LIVE_BANDS.containsMatchIn(body) -> EventType.CONCERT.name
            PARTY_TITLE.containsMatchIn(title) || CLUB_NIGHT_BODY.containsMatchIn(body) -> EventType.PARTY.name
            presented -> EventType.CONCERT.name
            start != null && start >= LATE_START -> EventType.PARTY.name
            else -> fromTitle
        }
    }

    /**
     * The event's text: the full body the "Show more" button reveals, else the card's teaser. The
     * full block repeats date and clock in its first column, so only the second is taken.
     */
    private fun descriptionOf(article: Element): String? =
        article.textAt(BODY_COLUMN)
            ?: article.textAt(".post-content-excerpt")

    /**
     * The DJs an event links to on Resident Advisor, in the venue's order. Deduplicated on the
     * profile URL: a night billing the same DJ twice — in the lineup and in a timetable — must
     * not store them twice.
     */
    private fun residentAdvisorLineup(article: Element): List<ScrapedArtist> =
        article
            .select("a[href]")
            .filter { RESIDENT_ADVISOR_PROFILE.containsMatchIn(it.attr("href")) }
            .distinctBy { it.attr("href").trimEnd('/').lowercase() }
            .mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
            .map { ScrapedArtist(name = it, role = DJ_ROLE) }

    /**
     * The linked DJs, each with the set the body's timetable gives them (#2002), matched by slug:
     * `Timetable<br>23:00-00:00 sixstar<br>…`. A line naming no linked DJ adds nobody, so the one
     * night seen with a timetable, (c)rave on 29 August, which links no DJ, stores no set time.
     * The first slot before [NIGHT_ENDS] is already the next day; [RunningOrderClock] has the rest.
     */
    private fun withTimetable(
        lineup: List<ScrapedArtist>,
        article: Element,
        eventDate: LocalDate
    ): List<ScrapedArtist> {
        val slots = article.select("$BODY_COLUMN p").flatMap { it.textLines() }.mapNotNull { TIMETABLE_LINE.matchEntire(it.trim()) }
        if (lineup.isEmpty() || slots.isEmpty()) return lineup
        val clock = RunningOrderClock(eventDate, dayBreak = NIGHT_ENDS)
        val sets = mutableMapOf<String, Pair<Instant, Instant?>>()
        for (slot in slots) {
            val (startText, endText, name) = slot.destructured
            val start = parseTime(startText.replace('.', ':').padStart(CLOCK_LENGTH, '0')) ?: continue
            val end = parseTime(endText.replace('.', ':').padStart(CLOCK_LENGTH, '0'))
            sets.putIfAbsent(SlugGenerator.slugify(name.trim()), clock.slot(start, end))
        }
        return lineup.map { act -> sets[SlugGenerator.slugify(act.name)]?.let { act.copy(setStart = it.first, setEnd = it.second) } ?: act }
    }

    /**
     * The event's doors and start.
     *
     * The body's "Doors HH:mm · Concert HH:mm" line wins where it exists: it is the venue's own
     * statement of both clocks, and the prose line beside it repeats the doors figure. Where the
     * body prints no such pair the venue publishes one clock and calls it the start, so the prose
     * line supplies it and no door time is stored.
     */
    private fun parseTimes(article: Element): EventTimes {
        val pair = DOORS_AND_CONCERT.find(article.textAt(BODY_COLUMN).orEmpty())
        if (pair != null) {
            return EventTimes(doors = parseTime(pair.groupValues[DOORS_GROUP]), start = parseTime(pair.groupValues[CONCERT_GROUP]))
        }
        return EventTimes(doors = null, start = parseProseTime(article.textAt(".eventInfo")))
    }

    /**
     * The clock out of the prose line, stated as `HH:mm`, with seconds it never means
     * (`23:00:00`), or with no minutes at all (`19.`). `null` when the line names no time.
     */
    private fun parseProseTime(info: String?): LocalTime? {
        val match = START_TIME.find(info.orEmpty()) ?: return null
        val hour = match.groupValues[HOUR_GROUP].padStart(2, '0')
        val minute = match.groupValues[MINUTE_GROUP].ifEmpty { "00" }
        return parseTime("$hour:$minute")
    }
}

/**
 * The acts a title names after `feat.` or `ft.`, in title order; none without that frame. The
 * night's name before it is a series, never an act.
 */
internal fun featuredActs(title: String): List<ScrapedArtist> {
    val frame = FEATURED_FRAME.find(title) ?: return emptyList()
    return splitSupportActs(title.substring(frame.range.last + 1))
        .map { stripArtistSuffix(it) }
        .filterNot(::isNonArtistName)
        .map { ScrapedArtist(name = it, role = HEADLINER_ROLE, titleDerived = true) }
}

/**
 * The acts of a `+` bill whose every act carries a `(live)` or `[style]` tag, after an optional
 * `Release Ritual:` lead and before a closing `live at Panke`: `Grafi (live) + Donna Haringwey (live)`
 * (event 12333), `Aantz [ambient/drone/Berlin] + UnoX [experimental dub/Berlin]` (event 12328). An
 * untagged `+` joins a series and a guest as often as two acts, so it bills nobody.
 */
internal fun markedBill(title: String): List<ScrapedArtist> {
    val bill = title.substringAfter(": ").replace(LIVE_AT_PANKE, "").trim()
    val acts =
        bill.split(PLUS_SEPARATOR).map {
            MARKED_ACT
                .matchEntire(it.trim())
                ?.groupValues
                ?.get(1)
                ?.trim()
        }
    if (acts.size < 2 || acts.any { it.isNullOrEmpty() }) return emptyList()
    return acts
        .filterNotNull()
        .filterNot(::isNonArtistName)
        .map { ScrapedArtist(name = it, role = HEADLINER_ROLE, titleDerived = true) }
}

/**
 * The title as the act, when a body line presents it: `Kingstar präsentiert` above `ATTIC 109`
 * (event 12332). A promoter presents a band by name; a series is presented in the title itself.
 */
internal fun presentedAct(
    lines: List<String>,
    title: String
): List<ScrapedArtist> {
    val trimmed = lines.map { it.trim() }.filter { it.isNotEmpty() }
    val presents = trimmed.zipWithNext().any { (line, next) -> PRESENTER_LINE.matches(line) && next.equals(title, ignoreCase = true) }
    return if (presents && !isNonArtistName(title)) listOf(ScrapedArtist(name = title, role = HEADLINER_ROLE, titleDerived = true)) else emptyList()
}

/**
 * The link of the first body paragraph that opens with a tickets label: `Tickets: <a>`,
 * `TICKETS: <a>`, `Tickets on RA: <a>` or `Tickets <a>`. Any seller's link counts. A label
 * paragraph with no web link, such as FUKSTEP's `Tickets on RA (Limited capacity!)`, is skipped,
 * and so is a paragraph where prose stands between the label and the link.
 */
internal fun pankeTicketUrl(paragraphs: List<Element>): String? =
    paragraphs.firstNotNullOfOrNull { paragraph ->
        paragraph.selectFirst("a[href]")?.let { anchor ->
            anchor.absUrl("href").takeIf { it.startsWith("http") && TICKETS_LEAD.matches(textBefore(anchor, paragraph)) }
        }
    }

/** The [paragraph]'s text before its [anchor]: all of it when the link shows no text. */
private fun textBefore(
    anchor: Element,
    paragraph: Element
): String {
    val text = paragraph.text()
    return anchor
        .text()
        .takeIf { it.isNotBlank() }
        ?.let { text.substringBefore(it) }
        ?.trim() ?: text
}

/** An event's two clocks, either of which the source may leave unstated. */
private data class EventTimes(
    val doors: LocalTime?,
    val start: LocalTime?
)

/**
 * The price from the first of [lines] that opens with a price label and names a figure.
 *
 * `Tickets: 20€`, `ENTRY 15€/18€/20€/25€`, `Cost: 12 euro`; a line without its label, such as
 * `15€ (link in bio)`, is prose. A labelled presale or door figure wins. Otherwise `Tickets` is
 * the presale and every other label the door, as at the venues that print `Eintritt`. A tier
 * stores its lowest figure and keeps the line as the note (#2083). A donation is only a note.
 */
internal fun parsePankePrice(lines: List<String>): PankePrice {
    val line =
        lines
            .map { it.trim() }
            .firstOrNull { PRICE_LINE.containsMatchIn(it) && euroAmounts(it).isNotEmpty() }
            ?: return PankePrice()
    val labelled = parseLabelledPrices(line)
    val tiered = TIER.containsMatchIn(line)
    val donation = DONATION.containsMatchIn(line)
    val note = line.takeIf { euroAmounts(it).size > 1 || tiered || donation }
    return when {
        labelled.presale != null || labelled.boxOffice != null -> {
            PankePrice(labelled.presale, labelled.boxOffice, note)
        }

        donation -> {
            PankePrice(note = line)
        }

        else -> {
            val lowest = (if (tiered) tierFigures(line) else euroAmounts(line)).minOrNull()
            if (line.startsWith(TICKETS_LABEL, ignoreCase = true)) {
                PankePrice(presale = lowest, note = note)
            } else {
                PankePrice(boxOffice = lowest, note = note)
            }
        }
    }
}

/** An event's price as the body states it; every part may be absent. */
internal data class PankePrice(
    val presale: BigDecimal? = null,
    val boxOffice: BigDecimal? = null,
    val note: String? = null
)

/** Every figure of a `10/15 euro` tier, where only the last one carries the currency. */
private fun tierFigures(line: String): List<BigDecimal> =
    TIER_FIGURE
        .findAll(line)
        .mapNotNull { it.value.replace(',', '.').toBigDecimalOrNull() }
        .toList()

/**
 * The poster out of an article's inline `background-image: url(…)`, the only place the listing
 * carries one — the template renders no `<img>` for an event.
 */
internal fun parseBackgroundImageUrl(style: String?): String? = cssUrlValue(style)?.takeIf { it.startsWith("http") }

/** The venue's own "upcoming" list, as opposed to the identically templated past one. */
private const val UPCOMING_MODULE = ".et_pb_events_0"

/**
 * `"starting at 19:00."`, `"starting at 23:00:00."` or `"starting at 19."` — the seconds are
 * template noise, and the minutes are the venue's to omit. Requiring them dropped the whole clock
 * from the one event that wrote a bare hour (#1758).
 */
private val START_TIME = Regex("""starting\s+at\s+(\d{1,2})(?::(\d{2}))?(?::\d{2})?""", RegexOption.IGNORE_CASE)

/** [START_TIME]'s hour group. */
private const val HOUR_GROUP = 1

/** [START_TIME]'s minute group — empty for a bare hour. */
private const val MINUTE_GROUP = 2

/** The hour from which an otherwise unmarked night is a club night. */
private val LATE_START: LocalTime = LocalTime.of(22, 0)

/** A space-padded `+` between two acts of a [markedBill]. */
private val PLUS_SEPARATOR = Regex("""\s+\+\s+""")

/** One act of a [markedBill]: its name, then a `(live)` or a `[style]` tag. */
private val MARKED_ACT = Regex("""(.+?)\s*(?:\(live\)|\[[^\]]+])""", RegexOption.IGNORE_CASE)

/** The `[ambient/drone/Berlin]` tag after an act in a title; its known genres are the event's. */
private val STYLE_TAG = Regex("""\[([^\]]+)]""")

/** The `live at Panke` a [markedBill] title closes with. */
private val LIVE_AT_PANKE = Regex("""\s+live\s+at\s+panke\s*$""", RegexOption.IGNORE_CASE)

/** `Kingstar präsentiert` or `AFROPUNX presents`, alone on its line. */
private val PRESENTER_LINE = Regex("""\S.{0,40}\s(?:präsentiert|presents):?""", RegexOption.IGNORE_CASE)

/** A title word that names a live show rather than a series: `live`, `tour`. */
private val LIVE_SHOW_TITLE = Regex("""\b(?:live|tour)\b""", RegexOption.IGNORE_CASE)

/** The Code Green Sunday market, `Grüne Markt` or `Panke Market`: a market, not a programme item. */
private val MARKET_TITLE = Regex("""markt|market""", RegexOption.IGNORE_CASE)

/** A disco or a fiesta in the title, the venue's own words for a dance night. */
private val PARTY_TITLE = Regex("""\b(?:disko|disco|fiesta)\b""", RegexOption.IGNORE_CASE)

/** The body bills live bands, as the FLIGHTMODE series does. */
private val LIVE_BANDS = Regex("""\blive\s+bands?\b""", RegexOption.IGNORE_CASE)

/** The body names a dancefloor or a night-long set: a club night. A bare DJ is none, since an open mic has one too. */
private val CLUB_NIGHT_BODY = Regex("""\bdance\s?floor\b|\ball\s+night\s+long\b""", RegexOption.IGNORE_CASE)

/**
 * The body's precise pair, "🕐 Doors 19:00 · Concert 21:00". Both labels are required and at most
 * four non-digits may separate them, so the venue's bullet, slash or dash all match and no two
 * unrelated clocks in a sentence do.
 */
private val DOORS_AND_CONCERT = Regex("""Doors\s+(\d{1,2}:\d{2})[^\d]{1,4}Concert\s+(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)

/** [DOORS_AND_CONCERT]'s doors group. */
private const val DOORS_GROUP = 1

/** [DOORS_AND_CONCERT]'s concert group. */
private const val CONCERT_GROUP = 2

/** A timetable line, `23:00-00:00 sixstar`: start, end and the name the venue gives the slot. */
private val TIMETABLE_LINE = Regex("""(\d{1,2}[:.]\d{2})\s*[-–]\s*(\d{1,2}[:.]\d{2})\s+(.+)""")

/** `HH:mm`, what a one-digit hour is padded to before parsing. */
private const val CLOCK_LENGTH = 5

/** The expanded body's prose column, where the pair is printed; the first column repeats the date. */
private const val BODY_COLUMN = ".post-content-full .et_pb_column_3_4"

/**
 * A body line that opens with a price label. The label may run straight into the figure, as the
 * venue's `<strong>ENTRY</strong>15€` does once flattened.
 */
private val PRICE_LINE =
    Regex(
        """^(?:tickets?|entry|eintritt|admission|cost|pre-?sale|vvk|recommended\s+donation|donation)(?!\p{L})""",
        RegexOption.IGNORE_CASE
    )

/** The label that makes an unlabelled figure the presale price, as `Tickets: 20€` does. */
private const val TICKETS_LABEL = "ticket"

/** Two figures joined by a slash, `15€/18€` or `10/15 euro`: a tiered price. */
private val TIER = Regex("""\d\s*€?\s*/\s*\d""")

/** One figure in a [TIER] line. */
private val TIER_FIGURE = Regex("""\d+(?:[.,]\d{1,2})?""")

/** A suggested amount rather than a price. */
private val DONATION = Regex("""\bdonation\b""", RegexOption.IGNORE_CASE)

/** A Resident Advisor artist profile, the page's one unambiguous artist marker. */
private val RESIDENT_ADVISOR_PROFILE = Regex("""^https?://(?:www\.)?ra\.co/(?:dj|artist)/""", RegexOption.IGNORE_CASE)

/** The `feat.` / `ft.` marker that ends a night's name and starts its acts. */
private val FEATURED_FRAME = Regex("""\s(?:feat\.?|ft\.)\s+""", RegexOption.IGNORE_CASE)

/** All of a paragraph's text before its link: the tickets label, optionally `on RA`, optionally a colon. */
private val TICKETS_LEAD = Regex("""tickets?(?:\s+on\s+ra)?\s*:?""", RegexOption.IGNORE_CASE)

/** An act the title names: the night is billed for them. */
private val HEADLINER_ROLE = ArtistRole.HEADLINER

/** Every act the venue links on Resident Advisor is billed there as a DJ. */
private val DJ_ROLE = ArtistRole.DJ
