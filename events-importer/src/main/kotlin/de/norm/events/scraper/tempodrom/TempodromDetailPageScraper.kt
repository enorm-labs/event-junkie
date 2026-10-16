package de.norm.events.scraper.tempodrom

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import org.jsoup.nodes.Document

/**
 * Pure HTML parser for a Tempodrom `/event/<slug>/` page, read for what the listing's JSON-LD
 * lacks: the promoter, and the comedy a fallback night's blurb names.
 *
 * Every page credits it once, in a `div.copy` below the share links: `Veranstalter <a
 * href=http://www.trinitymusic.de>Trinity Music GmbH</a>`. The link is the promoter's own site.
 * The blurb's "präsentiert von Radio Eins" names a media partner, not a promoter, so it is never
 * read (#2653). A few credits are stored double-encoded ("KonzertbÃ¼ro Augsburg"), so the name is
 * decoded once more where that yields valid UTF-8.
 *
 * A comedian billed by name alone is a fallback `CONCERT` in the listing. The page's Beschreibung
 * often says what the night is: "The iconic standup comedian Shaden …". A whole-word comedy cue
 * there, or in the title and format line, types the night [EventType.COMEDY] ([addComedyType],
 * #3380). The promoter credit is outside the Beschreibung, so "The Comedy Cornershop" is no cue:
 * that promoter also books a night whose blurb names no comedy.
 *
 * @see TempodromOverviewPageScraper for the listing parser, which supplies every other field.
 * @see <a href="https://www.tempodrom.de/event/jill_scott_2026-10-06_20/">A Tempodrom event page</a>
 */
class TempodromDetailPageScraper {
    /** [event] with every field the page adds: the promoter, then the comedy type. */
    fun enrich(
        event: ScrapedEvent,
        document: Document
    ): ScrapedEvent = addComedyType(addPromoter(event, document), document)

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

    /**
     * [event] typed [EventType.COMEDY] when its type is the fallback and the Beschreibung, the title
     * or the format line names comedy. The venue's word wins over the fallback (ADR-039). [event]
     * unchanged otherwise, so a cued type is never overridden.
     */
    fun addComedyType(
        event: ScrapedEvent,
        document: Document
    ): ScrapedEvent {
        if (!event.typeIsFallback) return event
        val texts = listOfNotNull(event.title, event.subtitle, description(document))
        return if (texts.any { COMEDY_CUE.containsMatchIn(it) }) {
            event.copy(eventType = EventType.COMEDY.name, typeIsFallback = false)
        } else {
            event
        }
    }

    /** The text of the region the "Beschreibung" button opens, or `null` when the page has none. */
    private fun description(document: Document): String? =
        document
            .select(ACCORDION_BUTTON)
            .firstOrNull { it.ownText().trim().equals(DESCRIPTION_LABEL, ignoreCase = true) }
            ?.attr("aria-controls")
            ?.takeIf { it.isNotBlank() }
            ?.let { document.getElementById(it) }
            ?.text()

    /** [text] read back as UTF-8 when its characters are the Latin-1 view of UTF-8 bytes: `Ã¼` is `ü`. */
    private fun repairDoubleEncoding(text: String): String {
        if (!DOUBLE_ENCODED.containsMatchIn(text) || text.any { it.code > LATIN_1_MAX }) return text
        val decoded = String(text.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        return if (decoded.contains(REPLACEMENT_CHARACTER)) text else decoded
    }

    private companion object {
        /** The block that holds the promoter credit. */
        const val CREDIT = "div.copy"

        /** An accordion button: "Beschreibung", "Hinweis zur Einlasskontrolle". */
        const val ACCORDION_BUTTON = "button.acc-button[aria-controls]"

        /** The label of the button that opens the blurb. The house rules sit behind another button. */
        const val DESCRIPTION_LABEL = "Beschreibung"

        /** A word that bills a comedy night: "standup comedian", "Ein Abend voller Comedy", "Kabarettistin". */
        val COMEDY_CUE = Regex("""\b(?:comedy|comedians?|stand-?up|kabarett(?:ist(?:in|en)?)?)\b""", RegexOption.IGNORE_CASE)

        /** The label in front of the promoter's name. */
        val PROMOTER_LABEL = Regex("""^\s*Veranstalter(?:\*in)?\s*:?\s*""", RegexOption.IGNORE_CASE)

        /** A UTF-8 lead byte read as Latin-1 (`Ã`, `Â`) followed by a continuation byte. */
        val DOUBLE_ENCODED = Regex("""[\u00C2-\u00C3][\u0080-\u00BF]""")

        const val LATIN_1_MAX = 0xFF
        const val REPLACEMENT_CHARACTER = '\uFFFD'
    }
}
