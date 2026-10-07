package de.norm.events.scraper.so36

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ROLE_LABEL_PREFIX
import de.norm.events.scraper.START_LABELS
import de.norm.events.scraper.SUPPORT_ROLE_PREFIX
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.hasFreeEntryPhrase
import de.norm.events.scraper.hasLanguageMarker
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.isBoxOfficeLabel
import de.norm.events.scraper.isFestivalTitle
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.jsonLdEvents
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.refineConcertVenueType
import de.norm.events.scraper.schemaDate
import de.norm.events.scraper.schemaStatus
import de.norm.events.scraper.splitBilingualDescription
import de.norm.events.scraper.splitSupportActs
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import java.math.BigDecimal
import java.time.LocalTime
import java.util.Locale

/**
 * Pure HTML parser for SO36 event detail (`/produkte/…`) pages — the **primary data source**.
 *
 * Most fields come straight from the server-rendered HTML and schema.org microdata:
 * - title (`h1 [itemprop=name]`), category (`.supertitle`), subtitle (`.subtitle`)
 * - doors / start times (the "Einlass … Beginn …" clock line)
 * - description (`.product_description`), poster image (`og:image`)
 * - ticket price (`[itemprop=price]` content) and the external ticket-shop link
 * - free admission (the ticket tab's `Eintritt frei / Admission free!` notice)
 * - promoter (the `Anbieter/Veranstalter` field, `.product_merchant`)
 *
 * Only two fields come from the schema.org `Event` JSON-LD block, having no reliable HTML
 * rendering: the ISO `startDate` (four-digit, time-zoned) and the `eventStatus` (scheduled /
 * cancelled / postponed). The JSON-LD *offer* `availability` is deliberately **ignored** for
 * sold-out detection: SO36 sells most events through external shops, which report on-platform
 * availability as `SoldOut` even when tickets are freely available elsewhere.
 *
 * @see So36OverviewPageScraper for overview parsing (discovery, date/title fallback).
 * @see So36WebsiteImporter for the HTTP fetch orchestrator.
 */
class So36DetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses a detail page into a [ScrapedEvent], or `null` without an event title, so the
     * importer can fall back to the overview data.
     *
     * @param sourceUrl the event's URL: [ScrapedEvent.sourceUrl] and the [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // Guard clause for the required title is clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val title = document.textAt("h1 [itemprop=name]")
        if (title.isNullOrBlank()) {
            logger.warn { "Detail page has no event title, skipping" }
            return null
        }

        val subtitle = document.textAt("small.subtitle")
        val eventType =
            refineBySubtitle(refineConcertVenueType(mapEventType(document.textAt("small.supertitle:not(.ticketsfor)")), title), title, subtitle)
        val jsonLd = document.jsonLdEvents().firstOrNull()
        val (doorsTime, startTime) = parseTimes(document)
        val (presale, boxOffice, priceNote) = parsePrices(document)
        val description = parseDescription(document)
        val bilingual = splitBilingualDescription(description?.takeIf(::hasLanguageMarker)?.let { lineBrokenDescription(document) })
        val eventDate = jsonLd?.schemaDate("startDate")

        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            description = bilingual?.original ?: description,
            descriptionAlt = bilingual?.alt,
            eventType = eventType,
            // The detail JSON-LD carries the authoritative date; sentinel when absent, so the overview's
            // date is used via So36WebsiteImporter.fillGapsFromOverview.
            eventDate = eventDate ?: UNRESOLVED_EVENT_DATE,
            doorsTime = doorsTime,
            startTime = startTime,
            imageUrl = parseImageUrl(document),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.SO36.sourceIdPrefix}${extractProductId(sourceUrl)}",
            ticketUrl = parseTicketUrl(document, sourceUrl),
            pricePresale = presale,
            priceBoxOffice = boxOffice,
            priceNote = priceNote,
            free = document.isFreeAdmission(),
            status = jsonLd?.schemaStatus() ?: EventStatus.SCHEDULED.name,
            artists =
                if (eventType == EventType.FESTIVAL.name) {
                    descriptionRoster(document, festivalDay(document, title, subtitle, eventDate)).map { ScrapedArtist(name = it, role = "HEADLINER") }
                } else {
                    parseArtists(title, subtitle, eventType)
                },
            promoters = listOfNotNull(document.parsePromoter())
        )
    }

    /**
     * The "Einlass: HH:mm … Beginn: HH:mm" clock line as a (doors, start) pair. Scoped to the block
     * carrying the clock icon so a stray time elsewhere cannot be mistaken for it.
     */
    private fun parseTimes(document: Document): Pair<LocalTime?, LocalTime?> {
        val clockText =
            document
                .select(".inside")
                .firstOrNull { it.selectFirst("i.fa-clock-o") != null }
                ?.text()
                .orEmpty()
        val doorsTime = labelledClock(clockText, DOORS_LABELS)
        val startTime = labelledClock(clockText, START_LABELS)
        return doorsTime to startTime
    }

    /** The poster image from the Open Graph `og:image` meta tag. */
    private fun parseImageUrl(document: Document): String? =
        document
            .selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.takeIf { it.startsWith("http") }

    /** The paragraphs of `.product_description` joined into the event description. */
    private fun parseDescription(document: Document): String? =
        document
            .select(".product_description p")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

    /** [parseDescription] with each `<br>` kept: a text in both languages is one `<p>`, broken where the halves meet (#330). */
    private fun lineBrokenDescription(document: Document): String? =
        document
            .select(".product_description p")
            .mapNotNull { htmlParagraphText(it.html()) }
            .joinToString("\n")
            .ifEmpty { null }

    /**
     * The ticket link: an outside shop's, or this page itself when it sells through the venue's own
     * cart (`/cart/add/<id>`), since the page is then the place to buy (#2274).
     */
    private fun parseTicketUrl(
        document: Document,
        sourceUrl: String
    ): String? =
        document.hrefAt(".variants-listing a.btn-buyme")
            ?: sourceUrl.takeIf { document.selectFirst(".variants-listing a.btn-buyme[href^=/cart/add/]") != null }

    /**
     * The presale and box-office prices from the schema.org offers, one table row per category, named
     * `<night> | <category>` (`| regulär`, `| ermäßigt`, `| Abendkasse`). An `Abendkasse` row is the
     * box-office price and a concession row is skipped; the presale is the cheapest of the rest.
     * Without offer rows, the lowest `[itemprop=price]` on the page is the presale. Presale tiers at
     * different prices (`Ticket social` / `regular` / `support`) keep the cheapest and name every
     * tier in the note, so the dearer ones stay visible (docs/DATA_MODEL.md, #2274).
     */
    private fun parsePrices(document: Document): Triple<BigDecimal?, BigDecimal?, String?> {
        val offers =
            document.select("[itemprop=offers]").mapNotNull { offer ->
                val price = offer.selectFirst("[itemprop=price][content]")?.attr("content")?.toBigDecimalOrNull()
                price?.let {
                    offer
                        .textAt("[itemprop=name]")
                        .orEmpty()
                        .substringAfterLast("|")
                        .trim() to it
                }
            }
        if (offers.isEmpty()) {
            val lowest = document.select("[itemprop=price][content]").mapNotNull { it.attr("content").toBigDecimalOrNull() }.minOrNull()
            return Triple(lowest, null, null)
        }
        val (door, online) = offers.partition { (category, _) -> isBoxOfficeLabel(category) }
        val tiers = online.filterNot { (category, _) -> CONCESSION_CATEGORY.containsMatchIn(category) }
        val note =
            tiers
                .takeIf { t ->
                    t.map { it.second.stripTrailingZeros() }.distinct().size > 1
                }?.joinToString(" / ") { (category, price) -> "$category ${euros(price)}" }
        return Triple(tiers.minOfOrNull { it.second }, door.minOfOrNull { it.second }, note)
    }

    /** A price as the venue prints it: `24,50 €`. */
    private fun euros(price: BigDecimal): String = "%.2f €".format(Locale.GERMANY, price)

    /**
     * The artist list for concerts: the title is the headliner (unless a placeholder like "TBA"),
     * then support acts from the subtitle ([parseSupportActs]). A festival bills the acts of its
     * description ([descriptionRoster]); any other night (parties, shows, readings) carries no roster.
     *
     * The venue names a night and its acts as `"<night> mit <acts>"` ("SADTEMBER mit TAHA, JOHNBOY
     * M.IKARUS"), so the shared billing frame is switched on: the acts after the marker are the
     * headliners and the night's name is never one (#1132).
     *
     * A ` | ` splits the billing from a tag the venue adds (`SADSVIT | HYPHEN DASH`, whose ticket
     * line is `SADSVIT | Ticket`), so only the part before it is read.
     */
    private fun parseArtists(
        title: String,
        subtitle: String?,
        eventType: String?
    ): List<ScrapedArtist> {
        if (eventType != EventType.CONCERT.name) return emptyList()

        val supportActs =
            parseSupportActs(subtitle).map { ScrapedArtist(name = it, role = "SUPPORT") }
        return headlinersFromTitle(title.substringBefore(" | "), unpackWithFrame = true) + supportActs
    }

    /**
     * Splits a support subtitle into act names. SO36 writes the line in two shapes: opening with a
     * joiner, "+" or "&" ("+ GUM + CLAVV", "+ Support: cosmic joke & bad beat", "& CRAWLSPACE &
     * PINTGLASS & GHETTO JUSTICE"), or with a support label ("Support: DEMOB HAPPY", "Special Guest:
     * The Flatliners"). Any other subtitle is a tagline ("Die Indie-Pop Party") and yields nothing —
     * `feat.` included, because "feat. Birte Volta mit Special-Guests" bills the headliner's guest,
     * not a support act (#1903).
     *
     * An "&" line is support, not a co-bill: SO36 puts co-headliners in the title ("MASTER BOOT
     * RECORD & FULCI"), and the NASTY page names only NASTY on its ticket and the "&" acts as "mit
     * dabei" (#1928). A band with "&" in its name ("PÖBEL & GESOCKS") is a `KNOWN_SINGLE_ACTS` pin.
     *
     * [splitSupportActs] cuts on commas, `+` and `/` and handles `&` / `and` /
     * `und` per boundary — "Earth Tongue und Scott Hepple & The Sun Band" yields "Earth Tongue"
     * and "Scott Hepple & The Sun Band" without mangling either. Each act's leading role label
     * ("Support:", "Special Guest(s):", "div. Supports", …) is stripped, and any chunk that is not
     * an act — a bare label, "TBA", an event-segment label like "ACID AFTERSHOW"
     * ([isNonArtistName]) — is dropped.
     */
    private fun parseSupportActs(subtitle: String?): List<String> {
        val line = subtitle?.trimStart().orEmpty()
        if (!isSupportLine(line)) return emptyList()
        return splitSupportActs(if (line.first() in SUPPORT_JOINERS) line.drop(1) else line)
            .map { it.replaceFirst(ROLE_LABEL_PREFIX, "").trim() }
            .filter { it.isNotBlank() && !isNonArtistName(it) }
    }

    /** The numeric product id from a `/produkte/<id>-…` detail URL. */
    private fun extractProductId(url: String): String =
        PRODUCT_ID_PATTERN
            .find(url)
            ?.groupValues
            ?.get(1)
            .orEmpty()

    private companion object {
        /** The numeric product id from a `/produkte/<id>-…` path. */
        private val PRODUCT_ID_PATTERN = Regex("""/produkte/(\d+)""")
    }
}

/**
 * Whether the ticket tab shows the free-admission notice where a price category would be. A free
 * night has no `€` figure at all, so the price microdata cannot say it.
 */
private fun Document.isFreeAdmission(): Boolean =
    select(".tabs .panel-body .alert")
        .any { hasFreeEntryPhrase(it.text()) }

/**
 * The `Anbieter/Veranstalter` name. The house's own nights credit `SO36`, the venue itself, so
 * that credit is dropped.
 */
private fun Document.parsePromoter(): String? =
    textAt(".product_merchant b")
        ?.takeUnless { it.equals(VENUE_NAME, ignoreCase = true) }

/** The promoter credit on the house's own nights. */
private const val VENUE_NAME = "SO36"

/**
 * The venue files every ticketed night under `Konzert`, and the subtitle or a title prefix names
 * the real format: a `Punk- und Hardcore-Festival`, a `Tattoo Convention`, a `PANEL:`, a live
 * `Qualitätspodcast`. A support line is never read this way, so `+ Festival Band` stays a concert.
 * A title that names a festival ([isFestivalTitle]) is one here already, so its roster is read (#2829).
 */
private fun refineBySubtitle(
    eventType: String,
    title: String,
    subtitle: String?
): String {
    val line = subtitle?.trimStart().orEmpty()
    return when {
        eventType != EventType.CONCERT.name -> eventType
        isFestivalTitle(title) -> EventType.FESTIVAL.name
        isSupportLine(line) -> eventType
        FESTIVAL_WORD.containsMatchIn(line) -> EventType.FESTIVAL.name
        PODCAST_WORD.containsMatchIn(line) -> EventType.SHOW.name
        CONVENTION_WORD.containsMatchIn(line) || PANEL_PREFIX.containsMatchIn(title) -> EventType.OTHER.name
        else -> eventType
    }
}

/** Whether a subtitle is a support line: it opens with a joiner or a support label. */
private fun isSupportLine(line: String): Boolean = line.firstOrNull() in SUPPORT_JOINERS || SUPPORT_ROLE_PREFIX.containsMatchIn(line)

/** A festival named in the subtitle: `Punk- und Hardcore-Festival - Tag 1`. */
private val FESTIVAL_WORD = Regex("""festival\b""", RegexOption.IGNORE_CASE)

/** A live podcast recording: `Qualitätspodcast mit Till Reiners und Moritz Neumeier`. */
private val PODCAST_WORD = Regex("""podcast\b""", RegexOption.IGNORE_CASE)

/** A convention: `queere antifaschistische Tattoo Convention`. */
private val CONVENTION_WORD = Regex("""\bconvention\b""", RegexOption.IGNORE_CASE)

/** A panel named as the title's prefix: `PANEL: ¡MASH-IT-UP! WE WERE ALWAYS THERE!`. */
private val PANEL_PREFIX = Regex("""^\s*panel\s*:""", RegexOption.IGNORE_CASE)

/** The characters that join a subtitle's acts to the headliner when they open it. */
private val SUPPORT_JOINERS = setOf('+', '&')

/** A concession category: `ermäßigt`, `erm.`, `reduced`, `Schüler`, `Student`. */
private val CONCESSION_CATEGORY = Regex("""erm(?:äßigt|aessigt|\.)|\breduced\b|\bconcession|sch(?:ü|ue)ler|\bstudent""", RegexOption.IGNORE_CASE)
