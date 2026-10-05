package de.norm.events.scraper.tempodrom

import de.norm.events.scraper.ScrapedEvent
import org.jsoup.nodes.Document

/**
 * Pure HTML parser for a Tempodrom `/event/<slug>/` page, read for the one field the listing's
 * JSON-LD lacks: the promoter.
 *
 * Every page credits it once, in a `div.copy` below the share links: `Veranstalter <a
 * href=http://www.trinitymusic.de>Trinity Music GmbH</a>`. The link is the promoter's own site.
 * The blurb's "präsentiert von Radio Eins" names a media partner, not a promoter, so it is never
 * read (#2653). A few credits are stored double-encoded ("KonzertbÃ¼ro Augsburg"), so the name is
 * decoded once more where that yields valid UTF-8.
 *
 * @see TempodromOverviewPageScraper for the listing parser, which supplies every other field.
 * @see <a href="https://www.tempodrom.de/event/jill_scott_2026-10-06_20/">A Tempodrom event page</a>
 */
class TempodromDetailPageScraper {
    /**
     * [event] with the promoter the page credits, and its website when the credit links one.
     * [event] unchanged when the page credits nobody: a page that loaded is not a failed page.
     */
    fun addPromoter(
        event: ScrapedEvent,
        document: Document
    ): ScrapedEvent {
        val credit = document.select(CREDIT).firstOrNull { PROMOTER_LABEL.containsMatchIn(it.text()) } ?: return event
        val link = credit.selectFirst("a")
        val name = repairDoubleEncoding((link?.text() ?: credit.text().replaceFirst(PROMOTER_LABEL, "")).trim())
        val website = link?.absUrl("href")?.takeIf { it.startsWith("http") }
        return if (name.isBlank()) {
            event
        } else {
            event.copy(promoters = listOf(name), promoterWebsites = website?.let { mapOf(name to it) }.orEmpty())
        }
    }

    /** [text] read back as UTF-8 when its characters are the Latin-1 view of UTF-8 bytes: `Ã¼` is `ü`. */
    private fun repairDoubleEncoding(text: String): String {
        if (!DOUBLE_ENCODED.containsMatchIn(text) || text.any { it.code > LATIN_1_MAX }) return text
        val decoded = String(text.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        return if (decoded.contains(REPLACEMENT_CHARACTER)) text else decoded
    }

    private companion object {
        /** The block that holds the promoter credit. */
        const val CREDIT = "div.copy"

        /** The label in front of the promoter's name. */
        val PROMOTER_LABEL = Regex("""^\s*Veranstalter(?:\*in)?\s*:?\s*""", RegexOption.IGNORE_CASE)

        /** A UTF-8 lead byte read as Latin-1 (`Ã`, `Â`) followed by a continuation byte. */
        val DOUBLE_ENCODED = Regex("""[\u00C2-\u00C3][\u0080-\u00BF]""")

        const val LATIN_1_MAX = 0xFF
        const val REPLACEMENT_CHARACTER = '\uFFFD'
    }
}
