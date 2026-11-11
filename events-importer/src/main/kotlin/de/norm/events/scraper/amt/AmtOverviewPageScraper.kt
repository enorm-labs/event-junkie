package de.norm.events.scraper.amt

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.ENGLISH_LONG_DATE
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.dateOrNull
import de.norm.events.scraper.germanDecimals
import de.norm.events.scraper.hasFreeEntryPhrase
import de.norm.events.scraper.hostedActsFromTitle
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.isBoxOfficeLabel
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.isPlaceholderName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Pure HTML parser for a single AMT `/month/<name>` page (e.g. `/month/june`).
 *
 * Each night is a `.div-grid-dan` block carrying the full event data:
 * - `data-date` — `"June 19, 2026"` (`MMMM d, yyyy`, English), the reliable date source (a
 * four-digit year, so no weekday inference);
 * - `.name` — the title (a party/series name like `SUBSTATION`, not a performer);
 * - `.sexpos` — an optional theme tag (`[SEX POSITIV]`), stored as the subtitle;
 * - `.perf` — the DJ line, stored as the description; split into [ScrapedArtist]s only when
 * delimited with `//` (a space-separated line cannot be split reliably), and dropped entirely
 * when it is a `tba` placeholder;
 * - `a.div-strela` — the external ticket link (Resident Advisor / EventJet);
 * - `.text-block-4` — the price: a tiered `min – max` (presale / box-office), a single figure
 * (presale), `Door sale only. 10` (box-office only), or `FREE ENTRY`, which sets the free flag; and
 * - `a[href^="/event/"]` — the detail-page link, the stable per-event identity for `sourceId`.
 *
 * Every event is [EventType.PARTY]: a techno club whose nights are DJ dance parties, so the
 * title is a series name rather than a headliner. A night with an empty DJ line can still bill its
 * DJs in the title, after a host: `Rx presents: Molly Lollen / DSC7 / Clarisse` or `Loone x Anja
 * Schneider, Milena Adamis, Anna Amato & Steven Shade` (see [billedActsFromTitle]).
 *
 * @see AmtWebsiteImporter for the fetch orchestration (entry page → month pages).
 */
class AmtOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from one month page.
     *
     * @param baseUrl the URL the document was fetched from, for resolving the relative
     * `/event/<slug>` links into absolute `sourceUrl` values.
     * @return one [ScrapedEvent] per night with a parseable date and title. Recently-passed nights
     * stay on the current-month page and are dropped centrally at persistence
     * (`EventUpsertService`), so every dated night is returned as-is.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val items = document.select("div.div-grid-dan[data-date]")
        logger.info { "Found ${items.size} event block(s) on AMT month page $baseUrl" }

        return items.mapSkippingFailures(logger, "AMT event block") { item ->
            parseEvent(item, baseUrl)
        }
    }

    private fun parseEvent(
        item: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val eventDate =
            parseAmtDate(item.attr("data-date")) ?: run {
                logger.warn { "Could not parse AMT date '${item.attr("data-date")}', skipping event" }
                return null
            }
        val title =
            item.textAt(".name") ?: run {
                logger.warn { "No title in AMT event block on $eventDate, skipping event" }
                return null
            }

        val detailHref = item.attrAt("a[href^=\"/event/\"]", "href")
        val slug = detailHref?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val sourceUrl = detailHref?.let { resolveUrl(baseUrl, it) } ?: baseUrl
        // Prefer the detail-page slug (stable per-event identity); fall back to date + slugified title.
        val identity = slug ?: "$eventDate-${SlugGenerator.slugify(title)}"

        val lineup = item.textAt(".perf")?.takeUnless { isPlaceholderName(it) }
        val priceCell = item.textAt(".text-block-4")
        val (presale, boxOffice) = parsePrices(priceCell)

        return ScrapedEvent(
            title = title,
            subtitle = item.textAt(".sexpos"),
            description = lineup,
            eventType = EventType.PARTY.name,
            eventDate = eventDate,
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.AMT.sourceIdPrefix}$identity",
            ticketUrl = item.hrefAt("a.div-strela"),
            pricePresale = presale,
            priceBoxOffice = boxOffice,
            free = hasFreeEntryPhrase(priceCell),
            artists =
                parseDjs(lineup)
                    .ifEmpty { hostedActsFromTitle(title, role = ArtistRole.DJ) }
                    .ifEmpty { billedActsFromTitle(title) }
        )
    }

    /**
     * The DJ line split into performers — only when delimited with `//`. A space-separated line
     * (`"Rubi ChrizzT Reza"`) cannot be split reliably, so no artists come from it.
     * Placeholder/non-artist tokens are filtered out.
     */
    private fun parseDjs(lineup: String?): List<ScrapedArtist> {
        if (lineup == null || !lineup.contains(DJ_SEPARATOR)) return emptyList()
        return lineup
            .split(DJ_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotBlank() && !isNonArtistName(it) }
            .map { ScrapedArtist(name = it, role = ArtistRole.DJ) }
    }

    /**
     * The DJs a `<host> presents: <bill>` or `<host> x <bill>` title lists. Only a list of two names or more
     * counts: one name after the host is as often a night or a crew (`Rx presents: Noise Daughters Showcase`,
     * `Loone x FEIERAMT`, `PUDDINGS x SCREAM QUEENS`) as a DJ.
     */
    private fun billedActsFromTitle(title: String): List<ScrapedArtist> {
        val bill = HOSTED_BILL.find(title.trim())?.groupValues?.get(1) ?: return emptyList()
        val names = bill.split(BILL_SEPARATOR).map { it.trim() }.filter { it.isNotBlank() && !isNonArtistName(it) }
        if (names.size < 2) return emptyList()
        return names.map { ScrapedArtist(name = it, role = ArtistRole.DJ, titleDerived = true) }
    }

    /** Parses the `MMMM d, yyyy` (`"June 19, 2026"`) `data-date`, null when absent or unparseable. */
    private fun parseAmtDate(text: String?): LocalDate? {
        if (text.isNullOrBlank()) return null
        return dateOrNull { LocalDate.parse(text.trim(), ENGLISH_LONG_DATE) }
    }

    /**
     * Splits a tiered price cell into (presale, box-office). A range (`"10 – 20"`) maps lower to
     * presale and upper to box-office; a single value (`"25"`) is the presale price with no
     * box-office tier, unless the cell names the door (`"Door sale only. 10"`). `(null, null)` when
     * blank or without digits.
     */
    private fun parsePrices(text: String?): Pair<BigDecimal?, BigDecimal?> {
        if (text.isNullOrBlank()) return null to null
        val values = germanDecimals(text)
        val doorOnly = values.size == 1 && (DOOR.containsMatchIn(text) || isBoxOfficeLabel(text))
        return if (doorOnly) null to values.single() else values.getOrNull(0) to values.getOrNull(1)
    }

    companion object {
        /** The `//` delimiter AMT uses between DJs in the `.perf` line. */
        private const val DJ_SEPARATOR = "//"

        /** The bill after a host's `presents:` or ` x `, as the venue's titles write it. */
        private val HOSTED_BILL = Regex("""^.+?(?:\s+presents:|\s+[x×]\s+)\s*(.+)$""", RegexOption.IGNORE_CASE)

        /** The separators a title bill puts between DJs. */
        private val BILL_SEPARATOR = Regex("""\s*(?:/|,|&)\s*""")

        // The shared label set leaves a bare `door` out because `Doors 19:00` is a time; this cell holds no time.
        private val DOOR = Regex("""(?<!\p{L})door(?!\p{L})""", RegexOption.IGNORE_CASE)
    }
}
