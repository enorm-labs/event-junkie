package de.norm.events.scraper.klunkerkranich

import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.textAt
import org.jsoup.nodes.Document
import java.math.BigDecimal

/**
 * Pure HTML parser for a Klunkerkranich `/events/<slug>` page.
 *
 * The page restates what the listing already carries — title, date, opening hours, thumbnail — and
 * adds three things it does not: the night's blurb, the entry price, and the poster at full size.
 * Those three are all this class returns, which is why [KlunkerkranichWebsiteImporter] implements
 * `EventImporter` directly rather than extending
 * [AbstractTwoPageWebsiteImporter][de.norm.events.scraper.AbstractTwoPageWebsiteImporter], whose
 * detail scraper is the primary source and must return a whole event (ADR-007 §"Shared Detail
 * Pages" makes the same split for Crack Bellmer and Bar jeder Vernunft).
 *
 * Two quirks of the page shape the parser:
 *
 * 1. **The blurb is bracketed by the venue's own boilerplate.** Every event page opens with a
 *    schedule preamble — the date, then one line per room ("*Wohnzimmer ab 17 Uhr") — repeats the
 *    title in styled paragraphs, and closes with a `_*_` rule followed by the price, the standing
 *    "Please note …" notice and the venue's own URL. [scrapeDescription] cuts at that rule, drops
 *    the leading preamble and the title restatements, and keeps the prose between them.
 * 2. **The sidebar's price is a range, not a ticket price.** "5-9€" is what the door charges
 *    depending on when you arrive, which the page itself spells out, so a range is stored as a
 *    [priceNote][de.norm.events.scraper.ScrapedEvent.priceNote] and only a lone figure ("3€") as a
 *    box-office price. The standing notice after it is identical on every event and is dropped.
 *
 * The sidebar also restates the date and opening hours, in full ("Sa. 08 Aug. 2026", "16:00 —
 * 03:00"). Neither is read: the listing already resolved both, its slug carrying the canonical ISO
 * date, so parsing the German rendering a second time would only add a way to disagree.
 *
 * @see KlunkerkranichOverviewPageScraper for the listing parser, which supplies every other field.
 * @see <a href="https://klunkerkranich.org/events/2026-08-09-la-maison-x-klunkerkranich/">A Klunkerkranich event page</a>
 */
class KlunkerkranichDetailPageScraper {
    /**
     * Reads the night's blurb, or `null` when the page carries none.
     *
     * Paragraphs from the `_*_` rule onward are the venue's standing footer and are cut off. Of
     * what remains, four kinds are dropped: the leading run of "… ab NN Uhr" schedule lines (only
     * the leading run, so a blurb that mentions a set time keeps it), the room labels the venue
     * marks with a leading `*` and uses both in that preamble and as headings over each floor's
     * lineup, any paragraph that merely restates part of the [title], and any that holds no word at
     * all — the rule itself, and the empty paragraphs the venue's SoundCloud embeds sit in.
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
     * Reads the sidebar's "Wieviel" entry charge as a (box-office price, price note) pair.
     *
     * A lone figure ("3€") is a real box-office price. A range ("5-9€") is not — the venue charges
     * by arrival time, so no single value is *the* price and the range is kept verbatim as the
     * note. Text with no figure at all ("Eintritt frei") becomes the note as-is, which is what lets
     * [detectFree][de.norm.events.scraper.detectFree] recognise a free night. Either half of the
     * pair may be `null`; both are when the sidebar has no "Wieviel" block.
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

    /** The full-size poster the page links its header image to, falling back to the rendered crop. */
    fun scrapeImageUrl(document: Document): String? = document.hrefAt("$HEADER_MEDIA a[href]") ?: document.imgSrcAt("$HEADER_MEDIA img")

    /** Parses a euro amount written with either a German comma or a dot decimal separator. */
    private fun parseAmount(text: String): BigDecimal? = runCatching { BigDecimal(text.replace(",", ".")) }.getOrNull()

    private companion object {
        /** The event page's prose block; the sidebar beside it holds the date and price instead. */
        const val CONTENT_PARAGRAPH = ".c-article__content p"

        /** The page header's image, linked to the unresized original. */
        const val HEADER_MEDIA = ".o-page-header__media"

        /** The sidebar's entry-charge paragraph, found by its "Wieviel" heading rather than by position. */
        const val PRICE_BLOCK = ".c-article__sidebar h2:containsOwn(Wieviel) + p"

        /** The typographic rule the venue closes every blurb with, before its standing footer. */
        const val FOOTER_RULE = "_*_"

        /** The `*` the venue prefixes each of its rooms with — "*Wohnzimmer", "*Ostflügel", "*Club „Hinter den Alpen“". */
        const val ROOM_LABEL_MARKER = "*"

        /** A schedule-preamble line stating when the night or a room opens — "Samstag 8. August 2026, ab 16 Uhr". */
        val SCHEDULE_LINE = Regex("""\bab\s+\d{1,2}(?:[:.]\d{2})?\s*Uhr\b""", RegexOption.IGNORE_CASE)

        /**
         * The entry charge opening the "Wieviel" text: an amount, optionally the upper end of a
         * range after it ("3€", "5-9€"). Anchored at the start so the standing "Please note …"
         * notice that follows is left out of both the value and the note.
         */
        val ENTRY_PRICE = Regex("""^(\d+(?:[.,]\d{1,2})?)(?:\s*[-–—]\s*(\d+(?:[.,]\d{1,2})?))?\s*€""")

        /** [ENTRY_PRICE]'s amount group. */
        const val AMOUNT_GROUP = 1

        /** [ENTRY_PRICE]'s upper-range group — blank when the venue named a single price. */
        const val RANGE_TOP_GROUP = 2
    }
}
