package de.norm.events.scraper.huxleys

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.labelledTime
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal

/**
 * Pure HTML parser for Huxleys Neue Welt event detail pages (`/event/YYYY-MM-DD-<slug>`).
 *
 * One `article.event`: a `.tourtitel` tour name, an optional `.canceledsoldout` badge, an
 * `.event-info` box (poster, a labelled `Datum` / `Beginn` / `Einlass` block, the Eventim link)
 * and the `.event-content` blurb.
 *
 * Two things live only here. The **taxonomies** are slugs on the `article` element —
 * `event-tags-*` are real music genres (`electronic`, `indietronica`, unlike other venues' mixed
 * vocabularies) and `promoters-*` the booking agency — read from the class list at no extra
 * request. The promoter comes from the hero's visible `<promoter> presents` credit first, since
 * the slug loses what the editor left out (`promoters-schoneberg` for "Konzertbüro Schoneberg",
 * #1139). And the page has **no heading of its own**: the act's name is only in the document
 * title with a site suffix, so the overview's `.eventname` stays authoritative and this scraper
 * derives a title only to stand alone (see [HuxleysWebsiteImporter.fillGapsFromOverview]).
 *
 * @see HuxleysOverviewPageScraper for overview parsing (discovery, date, times, status, fallback).
 * @see HuxleysWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://huxleysneuewelt.de/event/2026-08-02-thievery-corporation">Example detail page</a>
 */
class HuxleysDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses a detail page into a [ScrapedEvent], or `null` without an event article or derivable title.
     *
     * @param sourceUrl the event's URL: [ScrapedEvent.sourceUrl], its date and [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // Guard clauses for the missing article / title are clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val article = document.selectFirst("article.event") ?: document.selectFirst("article")
        if (article == null) {
            logger.warn { "Detail page has no event article, skipping" }
            return null
        }
        val title = parseTitle(document)
        if (title == null) {
            logger.warn { "Detail page has no derivable title, skipping" }
            return null
        }

        val slug = extractEventSlug(sourceUrl, EVENT_PATH_PREFIX)
        val details = article.textAt(".event-info").orEmpty()
        val subtitle = article.textAt(".tourtitel")
        val eventType = inferConcertVenueType(title)
        val (presale, boxOffice, priceNote) = parsePrices(article)

        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            description = article.textAt(".event-content"),
            eventType = eventType,
            eventDate = parseIsoDate(slug.take(ISO_DATE_LENGTH)) ?: UNRESOLVED_EVENT_DATE,
            doorsTime = parseTime(labelledTime(details, DETAIL_DOORS_LABEL)),
            startTime = parseTime(labelledTime(details, DETAIL_START_LABEL)),
            // The linked file is the full-size poster; the nested <img> is a scaled-down variant.
            imageUrl = article.hrefAt("a.event-image"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.HUXLEYS.sourceIdPrefix}$slug",
            ticketUrl = article.hrefAt(".ticket-links a.ticket"),
            pricePresale = presale,
            priceBoxOffice = boxOffice,
            priceNote = priceNote,
            genre = parseTaxonomy(article, GENRE_CLASS_PREFIX).joinToString(", ").takeIf { it.isNotBlank() },
            soldOut = article.textAt(".canceledsoldout")?.contains(SOLD_OUT_TEXT, ignoreCase = true) == true,
            status = parseHuxleysStatus(article),
            artists = buildArtistsForEventType(title, subtitle, eventType),
            promoters = parsePromoters(document, article)
        )
    }

    /**
     * The promoter as the hero credits it — `Konzertbüro Schoneberg presents`, minus the verb —
     * else the `promoters-*` taxonomy slug. The hero sits above the article, so read off the document.
     */
    private fun parsePromoters(
        document: Document,
        article: Element
    ): List<String> =
        document
            .textAt(".promoter")
            ?.replace(PROMOTER_CREDIT_SUFFIX, "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf(it) }
            ?: parseTaxonomy(article, PROMOTER_CLASS_PREFIX)

    /**
     * The presale/box-office price, when stated at all. Most shows sell through Eventim and print
     * none — one of eleven sampled pages did — as a labelled line in the `Details` box ("VVK: 28 €
     * (zzgl. Gebühr)"). The whole line is the note whenever it carries a booking-fee qualifier,
     * which the bare amount cannot express.
     */
    private fun parsePrices(article: Element): Triple<BigDecimal?, BigDecimal?, String?> {
        val line =
            article
                .select(".event-info > p")
                .map { it.text().trim() }
                .firstOrNull { PRICE_LINE.containsMatchIn(it) } ?: return Triple(null, null, null)
        return Triple(
            parsePriceValue(PRESALE_PRICE.find(line)?.value),
            parsePriceValue(BOX_OFFICE_PRICE.find(line)?.value),
            line.takeIf { FEE_NOTE.containsMatchIn(it) }
        )
    }

    /**
     * The act's name from `og:title` with the site suffix stripped — the page renders no heading.
     * Only when this page stands alone; a successful merge keeps the overview's cleaner `.eventname`.
     */
    private fun parseTitle(document: Document): String? {
        val raw =
            document.attrAt("meta[property=og:title]", "content")
                ?: document.title().takeIf { it.isNotBlank() }
                ?: return null
        return raw
            .substringBeforeLast(SITE_TITLE_SUFFIX)
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let(::cleanEventTitle)
    }

    /**
     * A WordPress taxonomy off the `article` class list, de-slugified (`event-tags-indietronica` →
     * `Indietronica`, `promoters-trinity-music` → `Trinity Music`). The theme emits every term as a
     * class, so no extra request. Title-cased word by word, the display form the pipeline's name
     * and genre normalizers expect.
     */
    private fun parseTaxonomy(
        article: Element,
        prefix: String
    ): List<String> =
        article
            .classNames()
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { it.isNotBlank() }
            .map { term -> term.split('-').filter { it.isNotBlank() }.joinToString(" ") { word -> word.replaceFirstChar(Char::uppercaseChar) } }

    private companion object {
        /** The `article` class prefix carrying the venue's music-genre taxonomy. */
        const val GENRE_CLASS_PREFIX = "event-tags-"

        /**
         * The `article` class prefix carrying the booking agency. The sibling `presenters-*` taxonomy
         * (media partners such as `laut-de`, `radio-eins`) is deliberately **not** read: de-slugifying
         * a domain-shaped term mangles it (`laut-de` → "Laut De"), and the agency is what a user recognises.
         */
        const val PROMOTER_CLASS_PREFIX = "promoters-"

        /** The trailing "presents" / "präsentiert" verb on the hero's promoter credit. */
        val PROMOTER_CREDIT_SUFFIX = Regex("""\s*(?:presents?|präsentiert|pres\.)\s*$""", RegexOption.IGNORE_CASE)

        /** The suffix WordPress appends to every document title. */
        const val SITE_TITLE_SUFFIX = " - Huxleys Neue Welt"

        /** The sold-out badge text. */
        const val SOLD_OUT_TEXT = "ausverkauft"

        /** The German doors label in the detail page's `Details` box. */
        const val DETAIL_DOORS_LABEL = "Einlass"

        /** The German start label in the detail page's `Details` box. */
        const val DETAIL_START_LABEL = "Beginn"

        /** A `Details`-box line that states a price at all. */
        val PRICE_LINE = Regex("""\b(?:VVK|Vorverkauf|AK|Abendkasse)\b.*€""", RegexOption.IGNORE_CASE)

        /** The presale amount within such a line. */
        val PRESALE_PRICE = Regex("""(?:VVK|Vorverkauf)\s*:?\s*\d[\d.,]*\s*€""", RegexOption.IGNORE_CASE)

        /** The box-office amount within such a line. */
        val BOX_OFFICE_PRICE = Regex("""(?:AK|Abendkasse)\s*:?\s*\d[\d.,]*\s*€""", RegexOption.IGNORE_CASE)

        /** A booking-fee qualifier, which makes the raw line worth keeping as a note. */
        val FEE_NOTE = Regex("""zzgl|geb(?:ü|ue)hr""", RegexOption.IGNORE_CASE)
    }
}
