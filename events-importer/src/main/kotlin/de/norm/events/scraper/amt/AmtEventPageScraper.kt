package de.norm.events.scraper.amt

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.parseClock
import org.jsoup.nodes.Document
import java.time.LocalTime

/**
 * Adds what an AMT `/event/<slug>` page holds beyond the month page: its prose, after the DJ line
 * in the description, its poster, and a start time the prose states. The month page carries only
 * the DJ line, and many nights leave it empty. The poster's `src` is the original file; its `srcset`
 * lists only smaller copies. A night without a poster shows Webflow's empty-binding placeholder,
 * marked `w-dyn-bind-empty`.
 */
internal fun addAmtEventPageFields(
    event: ScrapedEvent,
    document: Document
): ScrapedEvent {
    val prose = htmlParagraphText(document.selectFirst(".rich-text-block")?.html())
    val poster = document.selectFirst("img.slika-event:not(.w-dyn-bind-empty)")?.absUrl("src")?.blankToNull()
    return event.copy(
        description = listOfNotNull(event.description, prose).joinToString("\n\n").blankToNull(),
        imageUrl = poster ?: event.imageUrl,
        startTime = event.startTime ?: proseStartTime(prose)
    )
}

/**
 * The start most nights do not print: a `Start: 7:00 PM` label, else the first running-order slot
 * (`19:00–20:00 — Pamela Svart`, or `7PM mchvlsm` on the Loone nights). A slot counts only at the head
 * of a paragraph, so a ticket window mid-sentence is no start, and a bare `19 - 20` has no minutes or meridiem.
 */
private fun proseStartTime(prose: String?): LocalTime? =
    labelledClock(prose, START_LABEL)
        ?: prose
            ?.let { RUNNING_ORDER_SLOT.find(it) }
            ?.groupValues
            ?.get(1)
            ?.let { parseClock(it) }
        ?: prose
            ?.let { MERIDIEM_SLOT.find(it) }
            ?.destructured
            ?.let { (hour, minute, meridiem) -> parseClock("$hour:${minute.ifEmpty { "00" }}", meridiem) }

private const val START_LABEL = "start"

private val RUNNING_ORDER_SLOT = Regex("""^(\d{1,2}:\d{2})\s*[–—-]\s*\d{1,2}:\d{2}(?!\d)""", RegexOption.MULTILINE)

// The venue's editor leads some paragraphs with a zero-width joiner.
private val MERIDIEM_SLOT =
    Regex("""^[\u200D\h]*(\d{1,2})(?::(\d{2}))?\h*([ap])\.?m\.?(?!\p{L})\h+\p{L}""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
