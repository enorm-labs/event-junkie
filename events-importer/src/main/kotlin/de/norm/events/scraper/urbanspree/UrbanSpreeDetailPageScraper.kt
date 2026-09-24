package de.norm.events.scraper.urbanspree

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.detectFree
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Pure HTML parser for a single Urban Spree `/program/<category>/<slug>.html` page. The page
 * opens with a `#pseudo-card` hero: category (`.parent-name`), the untruncated `h1.title`, the
 * poster, a `.ct-dates` row with the date and start time as English prose ("Dec 12, 2026",
 * "20:00") plus the price ("25.00€" or "Free entry"), and a `.ct-btns` "Buy tickets" link
 * rendered with an empty `href` when there is no shop. Below, a `.ct-info` label/value list
 * carries the promoter and a Facebook link, and `.rte.tv-content` the description.
 *
 * Everything is read scoped to `#pseudo-card`, `.ct-info` or `.rte`, because the page also
 * renders an "upcoming events" slider from the same card markup the overview parses, and an
 * unscoped `li.infos` or `li.price` would pick up a neighbour's date and price. The page's own
 * date is parsed, but [UrbanSpreeWebsiteImporter] prefers the card's `data-dateStart` (ADR-007
 * §"Selector Strategy"). Doors time is not extracted: it appears only inside the description,
 * where an "Einlass: …" line may belong to another show mentioned in the blurb.
 *
 * @see UrbanSpreeOverviewPageScraper for discovery and the authoritative date.
 * @see UrbanSpreeWebsiteImporter for the fetch orchestrator and the merge.
 */
class UrbanSpreeDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses one detail page into a [ScrapedEvent], or `null` when the page carries no event hero
     * (a 404 body, a redesigned template); the importer then keeps the card's data.
     *
     * @param sourceUrl the URL the document was fetched from; also the `sourceId` source.
     */
    @Suppress("ReturnCount") // Guard clauses for the unparseable-page cases are clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val hero = document.selectFirst(HERO_SELECTOR)
        if (hero == null) {
            logger.warn { "No $HERO_SELECTOR block on Urban Spree detail page $sourceUrl" }
            return null
        }

        val rawTitle = hero.textAt("h1.title")
        if (rawTitle == null) {
            logger.warn { "No title on Urban Spree detail page $sourceUrl" }
            return null
        }

        // Peel off a "… | Special Guest: X" note before cleaning, so the support acts become their own
        // artists.
        val (headline, supportNote) = splitUrbanSpreeBilling(rawTitle)
        val title = cleanUrbanSpreeTitle(headline)
        val eventType = mapEventType(hero.textAt(".parent-name"), URBAN_SPREE_CATEGORY_SYNONYMS)
        val priceText = hero.textAt(".ct-dates li.price")
        val price = parsePriceValue(priceText)
        // The blurb opens with the night's own billing — one act per line under `Live:`, each with
        // its genres and origin — which is what corroborates a comma the title alone cannot (#1832).
        val description = document.textAt(".rte.tv-content")
        return ScrapedEvent(
            title = title,
            subtitle = supportNote,
            description = description,
            eventType = eventType,
            // The overview card's data-dateStart wins during the merge; this is the standalone value.
            eventDate = parseHeroDate(hero) ?: UNRESOLVED_EVENT_DATE,
            startTime = parseTime(hero.dateInfo(index = 1)),
            imageUrl = normalizeAssetUrl(hero.absUrlAt("img.img-feat-noslider", "src")),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.URBAN_SPREE.sourceIdPrefix}${urbanSpreeEventSlug(sourceUrl)}",
            // The "Buy tickets" anchor is always rendered; its href is empty when no shop is linked.
            ticketUrl = hero.absUrlAt(".ct-btns a", "href"),
            pricePresale = price,
            free = detectFree(pricePresale = price, priceNote = priceText),
            status = urbanSpreeStatus(rawTitle),
            artists = buildArtistsForEventType(title, subtitle = supportNote, eventType = eventType, description = description),
            promoters = infoValue(document, PROMOTER_LABEL)?.let(::splitPromoters).orEmpty()
        )
    }

    /**
     * Reads an absolute URL from [attributeKey] of the first element matching [cssQuery], or `null`.
     * Jsoup's `absUrl` rather than [hrefAt][de.norm.events.scraper.hrefAt] /
     * [imgSrcAt][de.norm.events.scraper.imgSrcAt]: every URL on the page is relative (`/assets/…`)
     * against a `<base href="https://www.urbanspree.com/">` tag, which Jsoup honours, and the raw
     * attribute would be rejected as non-absolute. The raw attribute is checked for emptiness first:
     * the "Buy tickets" anchor is always rendered with `href=""` when no shop is linked, and `absUrl`
     * would resolve that to the site root.
     */
    private fun Element.absUrlAt(
        cssQuery: String,
        attributeKey: String
    ): String? =
        selectFirst(cssQuery)
            ?.takeIf { it.attr(attributeKey).isNotBlank() }
            ?.absUrl(attributeKey)
            ?.takeIf { it.startsWith("http") }

    /** Parses the hero's English `MMM d, yyyy` date ("Dec 12, 2026"), or `null` when absent or malformed. */
    private fun parseHeroDate(hero: Element): LocalDate? {
        val text = hero.dateInfo(index = 0) ?: return null
        return try {
            LocalDate.parse(text, HERO_DATE_FORMATTER)
        } catch (_: DateTimeParseException) {
            logger.warn { "Unparseable Urban Spree detail date '$text'" }
            null
        }
    }

    /**
     * The [index]-th cell of the hero's date row, `0` the date ("Dec 12, 2026"), `1` the start time
     * ("20:00"), scoped to `.ct-dates` so the slider's identical `li.infos` cells cannot be read.
     */
    private fun Element.dateInfo(index: Int): String? =
        select(".ct-dates li.infos")
            .getOrNull(index)
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    /**
     * The value of the `.ct-info` pair whose label is [label] ("Promoter"), or `null`.
     */
    private fun infoValue(
        document: Document,
        label: String
    ): String? =
        document
            .select(".ct-info")
            .firstOrNull { it.textAt(".info-label").equals(label, ignoreCase = true) }
            ?.textAt(".info-data")

    private companion object {
        /** The hero block holding title, category, date, price, poster and ticket link. */
        private const val HERO_SELECTOR = "#pseudo-card"

        /** Label of the `.ct-info` row naming the event's promoter. */
        private const val PROMOTER_LABEL = "Promoter"

        /**
         * What the venue joins two promoters with ("Positive Transmitter & Crunch Tapes"). Only "&":
         * "Aufnahme + wiedergabe" carries its "+" in its own name (#328).
         */
        private val CO_PROMOTER_SEPARATOR = Regex("""\s*&\s*""")

        /**
         * Credits the split must leave whole: a name carrying the same "&" (#1356), case-insensitive.
         */
        private val SINGLE_NAMES_WITH_AMPERSAND = setOf("pure obsessions & red nights")

        /** The credit as one promoter, or as the two the venue joined. */
        private fun splitPromoters(credit: String): List<String> {
            val trimmed = credit.trim()
            return if (trimmed.lowercase() in SINGLE_NAMES_WITH_AMPERSAND) listOf(trimmed) else trimmed.split(CO_PROMOTER_SEPARATOR).map { it.trim() }
        }

        /** English hero date rendering, e.g. "Dec 12, 2026" / "Dec 05, 2026". */
        private val HERO_DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
    }
}
