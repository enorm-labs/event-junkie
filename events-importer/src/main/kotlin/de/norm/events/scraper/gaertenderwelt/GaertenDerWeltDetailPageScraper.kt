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
 * **The prose block is where the structured fields hide.** The park has no CMS fields for them, so
 * its editors write them as bold-labelled paragraphs among the description — `Einlass: ab 17:30 Uhr`,
 * `Tickets: ab 60,00 €` with `Abendkasse: ab 65 €` beneath, `Veranstalter*in: Loft Concert GmbH`,
 * `Support: Peter Gregson`. Each recognised label ([FIELD_LABELS]) is lifted out and then excluded
 * from the stored description, so the blurb reads as prose rather than repeating the metadata beside
 * it. The support billing is appended to the subtitle in the shared `"Support: A & B"` form, where
 * [buildArtistsForEventType][de.norm.events.scraper.buildArtistsForEventType] looks for it.
 *
 * There is no house style behind those paragraphs — they are prose an editor typed — so the parsing
 * is written to the variation rather than to one spelling: the price is `Tickets:` on some pages and
 * `Kosten:` on others, and the doors time comes with or without minutes and with or without a
 * sentence of entrance directions trailing it.
 *
 * The `h2` date line is **not** parsed: it renders weekday and day-month without a year ("Samstag,
 * 08.08."), and a multi-day run as a range, where the URL stamp
 * [the overview reads][GaertenDerWeltOverviewPageScraper] gives an unambiguous start. This scraper
 * leaves the date as [UNRESOLVED_EVENT_DATE] and the importer's merge always takes the overview's.
 *
 * `.venuesList` names where inside the grounds an event happens (the Arena, the Saal der Empfänge,
 * the Japanischer Garten) and is left unread: an event belongs to one venue and has no room or stage
 * field, and folding it into a text field it does not belong in would be worse than losing it.
 *
 * @see GaertenDerWeltOverviewPageScraper for discovery, identity and the authoritative date.
 * @see GaertenDerWeltWebsiteImporter for the fetch orchestrator and the merge.
 */
class GaertenDerWeltDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses one detail page into a [ScrapedEvent], or `null` when the page carries no single-view
     * block, no title, or no URL stamp to key it on — the importer then keeps the listing row's
     * own data rather than persisting a half-parsed event.
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
     * Indexes the bold-labelled prose paragraphs by their lowercased label, keeping each
     * paragraph's `<br>`-separated lines with the label itself stripped off the first one.
     *
     * The lines matter: a single `Tickets:` paragraph carries the presale price on its first line
     * and `Abendkasse: …` on its second, so flattening it with `.text()` would run two prices
     * together. A label appearing twice keeps its first paragraph, matching how the shared price
     * parsing resolves duplicates.
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
     * Strips what follows a matched label off the value: the gender-inclusive ending the park
     * writes on its `Veranstalter*in:` label (also spelled `_in` and `:in`), then the colon and
     * surrounding space. Matched as a whole word so a value that simply starts with "in" is left
     * alone.
     */
    private fun String.stripLabelTail(): String = replaceFirst(GENDER_INCLUSIVE_SUFFIX, "").trim(':', ' ')

    /**
     * The teaser above the description, with the page's support billing appended in the shared
     * `"Support: …"` form so the acts reach
     * [buildArtistsForEventType][de.norm.events.scraper.buildArtistsForEventType]. Either half may
     * be absent — a concert page often carries the billing and an empty `p.lead`.
     */
    private fun Element.subtitle(fields: Map<String, List<String>>): String? {
        val support = fields[SUPPORT_LABEL]?.firstOrNull()?.let { "Support: $it" }
        return listOfNotNull(textAt("p.lead"), support).joinToString(" — ").takeIf { it.isNotBlank() }
    }

    /**
     * The description: every prose paragraph that is *not* one of the labelled metadata lines,
     * joined by blank lines. Returns `null` when the page carries only metadata.
     */
    private fun Element.prose(): String? =
        select(PROSE_SELECTOR)
            .map { it.text().trim() }
            .filter { text -> text.isNotBlank() && EXCLUDED_LABELS.none { label -> text.startsWith(label, ignoreCase = true) } }
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }

    /**
     * Reads the doors time out of the `Einlass:` line, which the park writes as free prose around
     * it — with an optional "ab", with or without minutes, and sometimes trailing a whole sentence
     * of entrance directions ("ab 17:30 Uhr", "18 Uhr", "15:30 Uhr, ausschließlich über den
     * Haupteingang"). The clock time is therefore matched *inside* the line, and the first match
     * wins: a second time on the same line is the show's own start ("… Beginn: 17:00 Uhr"), which
     * the URL stamp already supplies.
     */
    private fun Map<String, List<String>>.doorsTime(): LocalTime? =
        this[DOORS_LABEL]
            ?.firstNotNullOfOrNull { DOORS_TIME_PATTERN.find(it) }
            ?.destructured
            ?.let { (hour, minute) -> runCatching { LocalTime.of(hour.toInt(), minute.ifBlank { "0" }.toInt()) }.getOrNull() }

    /**
     * Splits the pricing paragraph into presale and box-office prices. Its first line is the
     * presale price ("ab 60,00 €", "Tickets ab 47,00€"); an `Abendkasse:` line — below it or in a
     * paragraph of its own — is the box-office one. Either may be absent; plenty of events name no
     * price at all, and an absent price is unknown rather than free.
     */
    private fun Map<String, List<String>>.prices(): Pair<BigDecimal?, BigDecimal?> {
        val (inlineBoxOffice, presale) = this[TICKETS_LABEL].orEmpty().partition { it.startsWith(BOX_OFFICE_LABEL, ignoreCase = true) }
        val boxOffice = inlineBoxOffice + this[BOX_OFFICE_LABEL].orEmpty()
        return presale.firstNotNullOfOrNull { parsePriceValue(it) } to boxOffice.firstNotNullOfOrNull { parsePriceValue(it) }
    }

    /**
     * Reads the promoter out of the `Veranstalter*in:` line, dropping the contact address the park
     * appends after a comma ("Loft Concert GmbH, tickets(at)loft.de").
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
         * The paragraph labels the park writes, mapped onto the field each is lifted into. The
         * park has no house style, so one field arrives under several labels: the price is
         * `Tickets:` on the pages its own box office sells and `Kosten:` on the ones an external
         * promoter does. `veranstalter` is a prefix of both spellings it uses (`Veranstalter:` and
         * the gender-inclusive `Veranstalter*in:`), and `abendkasse` is listed in its own right
         * for the pages that give it a paragraph rather than a line under the price.
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
         * The labels kept out of the stored description: everything lifted into a field of its
         * own, plus the `Kontakt:` line, which is a booking mailbox rather than prose about the
         * event and which the model has no field for.
         */
        private val EXCLUDED_LABELS = FIELD_LABELS.keys + "kontakt"

        /** A clock time inside the `Einlass:` prose, with the minutes the park often leaves off ("18 Uhr"). */
        private val DOORS_TIME_PATTERN = Regex("""(\d{1,2})(?::(\d{2}))?\s*Uhr""", RegexOption.IGNORE_CASE)

        /** The gender-inclusive ending on a label, as in `Veranstalter*in:` / `Veranstalter_in:`. */
        private val GENDER_INCLUSIVE_SUFFIX = Regex("""^[*_:/]in\b""", RegexOption.IGNORE_CASE)
    }
}
