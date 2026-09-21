package de.norm.events.scraper.klunkerkranich

import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.textAt
import org.jsoup.nodes.Document
import java.math.BigDecimal

/**
 * Pure HTML parser for a Klunkerkranich `/events/<slug>` page.
 *
 * The page restates the listing's fields — title, date, opening hours, thumbnail — and adds
 * three: the blurb, the entry price, the full-size poster. Those three are all this class
 * returns, which is why [KlunkerkranichWebsiteImporter] implements `EventImporter` directly
 * rather than extending
 * [AbstractTwoPageWebsiteImporter][de.norm.events.scraper.AbstractTwoPageWebsiteImporter], whose
 * detail scraper is the primary source and must return a whole event (ADR-007 §"Shared Detail
 * Pages" makes the same split for Crack Bellmer and Bar jeder Vernunft).
 *
 * 1. **The blurb is bracketed by boilerplate.** Every page opens with a schedule preamble — the
 * date, then one line per room ("*Wohnzimmer ab 17 Uhr") — repeats the title in styled
 * paragraphs, and closes with a `_*_` rule followed by the price, the standing "Please note …"
 * notice and the venue's URL. [scrapeDescription] cuts at the rule, drops the preamble and
 * title restatements, keeps the prose between.
 * 2. **The sidebar's price is a range, not a ticket price.** "5-9€" is what the door charges
 * by arrival time, as the page spells out, so a range is a
 * [priceNote][de.norm.events.scraper.ScrapedEvent.priceNote] and only a lone figure ("3€") a
 * box-office price. The standing notice after it is identical on every event and dropped.
 *
 * The sidebar also restates date and opening hours in full ("Sa. 08 Aug. 2026", "16:00 —
 * 03:00"). Neither is read: the listing resolved both, its slug carrying the ISO date, so
 * parsing the German rendering again would only add a way to disagree.
 *
 * @see KlunkerkranichOverviewPageScraper for the listing parser, which supplies every other field.
 * @see <a href="https://klunkerkranich.org/events/2026-08-09-la-maison-x-klunkerkranich/">A Klunkerkranich event page</a>
 */
class KlunkerkranichDetailPageScraper {
    /**
     * The night's blurb, or `null`.
     *
     * Paragraphs from the `_*_` rule on are the standing footer, cut off. Of the rest, four kinds
     * are dropped: the leading run of "… ab NN Uhr" schedule lines (only the leading run, so a
     * blurb mentioning a set time keeps it), the `*`-prefixed room labels used in that preamble
     * and as headings over each floor's lineup, any paragraph merely restating part of the
     * [title], and any with no word at all — the rule itself and the empty paragraphs around the
     * venue's SoundCloud embeds.
     */
    fun scrapeDescription(
        document: Document,
        title: String
    ): String? =
        document
            .select(CONTENT_PARAGRAPH)
            .map { it.text().trim() }
            .takeWhile { it != FOOTER_RULE }
            .dropWhile { SCHEDULE_LINE.containsMatchIn(it) }
            .filter { paragraph ->
                !paragraph.startsWith(ROOM_LABEL_MARKER) && paragraph.any(Char::isLetterOrDigit) && paragraph !in title
            }.joinToString("\n")
            .takeIf { it.isNotBlank() }

    /**
     * The sidebar's "Wieviel" entry charge as (box-office price, price note).
     *
     * A lone figure ("3€") is a box-office price. A range ("5-9€") is not — the venue charges by
     * arrival time — and is kept verbatim as the note. Text with no figure ("Eintritt frei")
     * becomes the note as-is, which lets [detectFree][de.norm.events.scraper.detectFree] recognise
     * a free night. Either half may be `null`; both are without a "Wieviel" block.
     */
    fun scrapePrice(document: Document): Pair<BigDecimal?, String?> {
        val text = document.textAt(PRICE_BLOCK) ?: return null to null
        val match = ENTRY_PRICE.find(text)
        return when {
            match == null -> null to text
            match.groupValues[RANGE_TOP_GROUP].isBlank() -> parseAmount(match.groupValues[AMOUNT_GROUP]) to null
            else -> null to match.value.trim()
        }
    }

    /** The full-size poster the header image links to, falling back to the rendered crop. */
    fun scrapeImageUrl(document: Document): String? = document.hrefAt("$HEADER_MEDIA a[href]") ?: document.imgSrcAt("$HEADER_MEDIA img")

    /** A euro amount with a German comma or a dot decimal separator. */
    private fun parseAmount(text: String): BigDecimal? = runCatching { BigDecimal(text.replace(",", ".")) }.getOrNull()

    private companion object {
        /** The event page's prose block; the sidebar beside it holds date and price. */
        const val CONTENT_PARAGRAPH = ".c-article__content p"

        /** The page header's image, linked to the unresized original. */
        const val HEADER_MEDIA = ".o-page-header__media"

        /** The sidebar's entry-charge paragraph, found by its "Wieviel" heading, not by position. */
        const val PRICE_BLOCK = ".c-article__sidebar h2:containsOwn(Wieviel) + p"

        /** The typographic rule closing every blurb, before the standing footer. */
        const val FOOTER_RULE = "_*_"

        /** The `*` prefixed to each room — "*Wohnzimmer", "*Ostflügel", "*Club „Hinter den Alpen“". */
        const val ROOM_LABEL_MARKER = "*"

        /** A schedule-preamble line stating when the night or a room opens — "Samstag 8. August 2026, ab 16 Uhr". */
        val SCHEDULE_LINE = Regex("""\bab\s+\d{1,2}(?:[:.]\d{2})?\s*Uhr\b""", RegexOption.IGNORE_CASE)

        /**
         * The entry charge opening the "Wieviel" text: an amount, optionally the upper end of a range
         * ("3€", "5-9€"). Anchored at the start so the "Please note …" notice after it stays out of
         * value and note.
         */
        val ENTRY_PRICE = Regex("""^(\d+(?:[.,]\d{1,2})?)(?:\s*[-–—]\s*(\d+(?:[.,]\d{1,2})?))?\s*€""")

        /** [ENTRY_PRICE]'s amount group. */
        const val AMOUNT_GROUP = 1

        /** [ENTRY_PRICE]'s upper-range group — blank for a single price. */
        const val RANGE_TOP_GROUP = 2
    }
}
