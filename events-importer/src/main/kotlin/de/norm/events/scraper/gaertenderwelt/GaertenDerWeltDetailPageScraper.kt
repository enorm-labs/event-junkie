package de.norm.events.scraper.gaertenderwelt

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.detectFree
import de.norm.events.scraper.gaertenderwelt.GaertenDerWeltDetailPageScraper.Companion.FIELD_LABELS
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import de.norm.events.scraper.textLines
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.time.LocalTime

/**
 * Pure HTML parser for a single Gärten der Welt `…/detail/<stamp>/<slug>/` page.
 *
 * The prose block is where the structured fields hide: the park has no CMS fields, so editors
 * write bold-labelled paragraphs among the description (`Einlass: ab 17:30 Uhr`, `Tickets: ab
 * 60,00 €` with `Abendkasse: ab 65 €` beneath, `Veranstalter*in: Loft Concert GmbH`, `Support:
 * Peter Gregson`). Each recognised label ([FIELD_LABELS]) is lifted out and excluded from the
 * stored description; the support billing is appended to the subtitle in the shared `"Support:
 * A & B"` form for [buildArtistsForEventType][de.norm.events.scraper.buildArtistsForEventType].
 * There is no house style, so the parsing follows the variation: `Tickets:` or `Kosten:`, doors
 * with or without minutes and with or without trailing entrance directions.
 *
 * The `h2` date line is not parsed: it renders weekday and day-month without a year ("Samstag,
 * 08.08.") and a multi-day run as a range, where the URL stamp
 * [the overview reads][GaertenDerWeltOverviewPageScraper] gives the start. The date stays
 * [UNRESOLVED_EVENT_DATE] and the merge takes the overview's. `.venuesList` (the Arena, the
 * Saal der Empfänge, the Japanischer Garten) is left unread: an event has no room field.
 *
 * @see GaertenDerWeltOverviewPageScraper for discovery, identity and the authoritative date.
 * @see GaertenDerWeltWebsiteImporter for the fetch orchestrator and the merge.
 */
class GaertenDerWeltDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses one detail page into a [ScrapedEvent], or `null` when the page carries no single-view
     * block, no title or no URL stamp; the importer then keeps the listing row's own data.
     *
     * @param sourceUrl the URL the document was fetched from; also the `sourceId` source.
     */
    @Suppress("ReturnCount") // Guard clauses for the unparseable-page cases are clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val single = document.selectFirst(SINGLE_VIEW_SELECTOR)
        if (single == null) {
            logger.warn { "No $SINGLE_VIEW_SELECTOR block on Gärten der Welt detail page $sourceUrl" }
            return null
        }

        val rawTitle = single.textAt("h1")
        val identity = parseEventPath(sourceUrl)?.identity
        if (rawTitle == null || identity == null) {
            logger.warn { "Skipping Gärten der Welt detail page $sourceUrl: no title or no YYYY-MM-DD_HHmm stamp in its path" }
            return null
        }

        val title = cleanGaertenDerWeltTitle(rawTitle)
        val fields = single.labelledParagraphs()
        val (presale, boxOffice) = fields.prices()
        val priceNote = fields[TICKETS_LABEL]?.joinToString(" ")
        return ScrapedEvent(
            title = title,
            subtitle = single.subtitle(fields),
            description = single.prose(),
            // The listing row's category is the only classification the source publishes; the merge keeps it.
            eventDate = UNRESOLVED_EVENT_DATE,
            doorsTime = fields.doorsTime(),
            imageUrl = single.attrAt("figure.image img", "src")?.let { resolveUrl(sourceUrl, it) },
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.GAERTEN_DER_WELT.sourceIdPrefix}$identity",
            ticketUrl = single.hrefAt("a.ticket"),
            pricePresale = presale,
            priceBoxOffice = boxOffice,
            priceNote = priceNote,
            soldOut = isSoldOutTitle(rawTitle),
            free = detectFree(presale, boxOffice, priceNote, title),
            status = gaertenDerWeltStatus(rawTitle),
            promoters = listOfNotNull(fields.promoter())
        )
    }

    /**
     * Indexes the bold-labelled prose paragraphs by lowercased label, keeping each paragraph's
     * `<br>`-separated lines with the label stripped off the first. The lines matter: one `Tickets:`
     * paragraph carries the presale price on its first line and `Abendkasse: …` on its second. A
     * label appearing twice keeps its first paragraph.
     */
    private fun Element.labelledParagraphs(): Map<String, List<String>> =
        select(PROSE_SELECTOR)
            .mapNotNull { paragraph ->
                val lines = paragraph.textLines()
                val head = lines.firstOrNull() ?: return@mapNotNull null
                val label = FIELD_LABELS.keys.firstOrNull { head.startsWith(it, ignoreCase = true) } ?: return@mapNotNull null
                FIELD_LABELS.getValue(label) to (listOf(head.drop(label.length).stripLabelTail()) + lines.drop(1)).filter { it.isNotBlank() }
            }.reversed()
            .toMap()

    /**
     * Strips what follows a matched label: the gender-inclusive ending on `Veranstalter*in:` (also
     * `_in` and `:in`), then the colon and space. Whole-word, so a value starting with "in" is left
     * alone.
     */
    private fun String.stripLabelTail(): String = replaceFirst(GENDER_INCLUSIVE_SUFFIX, "").trim(':', ' ')

    /**
     * The teaser above the description, with the support billing appended in the shared
     * `"Support: …"` form. Either half may be absent; a concert page often has an empty `p.lead`.
     */
    private fun Element.subtitle(fields: Map<String, List<String>>): String? {
        val support = fields[SUPPORT_LABEL]?.firstOrNull()?.let { "Support: $it" }
        return listOfNotNull(textAt("p.lead"), support).joinToString(" — ").takeIf { it.isNotBlank() }
    }

    /**
     * The description: every prose paragraph that is not a labelled metadata line; `null` when the
     * page carries only metadata.
     */
    private fun Element.prose(): String? =
        select(PROSE_SELECTOR)
            .map { it.text().trim() }
            .filter { text -> text.isNotBlank() && EXCLUDED_LABELS.none { label -> text.startsWith(label, ignoreCase = true) } }
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }

    /**
     * Reads the doors time out of the `Einlass:` line, free prose with an optional "ab", with or
     * without minutes, sometimes trailing entrance directions ("ab 17:30 Uhr", "18 Uhr", "15:30 Uhr,
     * ausschließlich über den Haupteingang"). Matched inside the line, first match wins: a second
     * time is the show's own start ("… Beginn: 17:00 Uhr"), which the URL stamp supplies.
     */
    private fun Map<String, List<String>>.doorsTime(): LocalTime? =
        this[DOORS_LABEL]
            ?.firstNotNullOfOrNull { DOORS_TIME_PATTERN.find(it) }
            ?.destructured
            ?.let { (hour, minute) -> runCatching { LocalTime.of(hour.toInt(), minute.ifBlank { "0" }.toInt()) }.getOrNull() }

    /**
     * Splits the pricing paragraph: its first line is the presale price ("ab 60,00 €", "Tickets ab
     * 47,00€"); an `Abendkasse:` line, below it or in its own paragraph, is the box-office one.
     * Either may be absent, and an absent price is unknown rather than free.
     */
    private fun Map<String, List<String>>.prices(): Pair<BigDecimal?, BigDecimal?> {
        val (inlineBoxOffice, presale) = this[TICKETS_LABEL].orEmpty().partition { it.startsWith(BOX_OFFICE_LABEL, ignoreCase = true) }
        val boxOffice = inlineBoxOffice + this[BOX_OFFICE_LABEL].orEmpty()
        return presale.firstNotNullOfOrNull { parsePriceValue(it) } to boxOffice.firstNotNullOfOrNull { parsePriceValue(it) }
    }

    /**
     * Reads the promoter out of the `Veranstalter*in:` line, dropping the contact address after a
     * comma ("Loft Concert GmbH, tickets(at)loft.de").
     */
    private fun Map<String, List<String>>.promoter(): String? =
        this[PROMOTER_LABEL]
            ?.firstOrNull()
            ?.substringBefore(',')
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private companion object {
        /** The `events2` single-view block holding the whole event. */
        private const val SINGLE_VIEW_SELECTOR = ".tx-events2-single"

        /** The prose paragraphs, which carry both the description and the labelled metadata. */
        private const val PROSE_SELECTOR = "p.textNormal"

        private const val DOORS_LABEL = "einlass"
        private const val TICKETS_LABEL = "tickets"
        private const val PROMOTER_LABEL = "veranstalter"
        private const val BOX_OFFICE_LABEL = "abendkasse"
        private const val SUPPORT_LABEL = "support"

        /**
         * The paragraph labels the park writes, mapped onto fields. One field arrives under several
         * labels: `Tickets:` where its own box office sells, `Kosten:` where an external promoter does.
         * `veranstalter` is a prefix of both `Veranstalter:` and `Veranstalter*in:`; `abendkasse` is
         * listed for the pages that give it a paragraph.
         */
        private val FIELD_LABELS =
            mapOf(
                "einlass" to DOORS_LABEL,
                "tickets" to TICKETS_LABEL,
                "kosten" to TICKETS_LABEL,
                "veranstalter" to PROMOTER_LABEL,
                "abendkasse" to BOX_OFFICE_LABEL,
                "support" to SUPPORT_LABEL
            )

        /**
         * The labels kept out of the stored description: everything lifted into a field, plus the
         * `Kontakt:` booking mailbox, which the model has no field for.
         */
        private val EXCLUDED_LABELS = FIELD_LABELS.keys + "kontakt"

        /** A clock time inside the `Einlass:` prose, with the minutes the park often leaves off ("18 Uhr"). */
        private val DOORS_TIME_PATTERN = Regex("""(\d{1,2})(?::(\d{2}))?\s*Uhr""", RegexOption.IGNORE_CASE)

        /** The gender-inclusive ending on a label, as in `Veranstalter*in:` / `Veranstalter_in:`. */
        private val GENDER_INCLUSIVE_SUFFIX = Regex("""^[*_:/]in\b""", RegexOption.IGNORE_CASE)
    }
}
