package de.norm.events.scraper.so36

import de.norm.events.scraper.isNonArtistName
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The acts a festival page bills in its description: each a bold name with its origin in brackets after it,
 * `<strong>PLANLOS X</strong> (Berlin) – …` (#2829). The bracket tells an act from the festival's own name and
 * the presenters, which the venue sets in bold too. Bold is `<strong>`, `<b>` or a `font-weight` style.
 *
 * A two-day festival prints both days on both pages, each under a `Live - Day N:` label. [day] picks the
 * section; without it, a page with several sections bills nobody rather than the wrong night.
 */
internal fun descriptionRoster(
    document: Document,
    day: Int?
): List<String> {
    val description = document.selectFirst(".product_description") ?: return emptyList()
    val sections = linkedMapOf<Int, MutableList<String>>()
    var section = 0
    description.allElements.forEach { element ->
        DAY_LABEL.find(element.ownText())?.let { section = it.groupValues[1].toInt() }
        if (element.isBold() && element.parents().none { it.isBold() }) {
            originTaggedName(element)?.let { sections.getOrPut(section) { mutableListOf() } += it }
        }
    }
    val days = sections.filterKeys { it > 0 }
    val names =
        when {
            days.isEmpty() -> sections[0].orEmpty()
            days.size == 1 -> days.values.single()
            else -> day?.let { days[it] }.orEmpty()
        }
    return names
        .distinct()
        .filterNot(::isNonArtistName)
        .takeIf { it.size >= MIN_ROSTER }
        .orEmpty()
}

/**
 * Which night of a run this page is: the `Tag N` its subtitle names, else this date's rank among the dates the
 * page's own programme list gives the same title (`Tickets <title> in Berlin am 23.10.2026`).
 */
internal fun festivalDay(
    document: Document,
    title: String,
    subtitle: String?,
    date: LocalDate?
): Int? =
    SUBTITLE_DAY
        .find(subtitle.orEmpty())
        ?.groupValues
        ?.get(1)
        ?.toInt()
        ?: date?.let { runDates(document, title).indexOf(it).takeIf { rank -> rank >= 0 }?.plus(1) }

/** The distinct dates, sorted, that the page's programme list gives [title]. */
private fun runDates(
    document: Document,
    title: String
): List<LocalDate> =
    document
        .select("a[href*=/produkte/][title]")
        .mapNotNull { PROGRAMME_LINK_TITLE.matchEntire(it.attr("title"))?.destructured }
        .filter { (linkTitle) -> linkTitle.equals(title, ignoreCase = true) }
        .map { (_, date) -> LocalDate.parse(date, LINK_DATE) }
        .distinct()
        .sorted()

/** The bold text as an act when an origin in brackets ends it or follows it, else `null`. */
private fun originTaggedName(element: Element): String? {
    val text = element.text().replace(NBSP, ' ').trim()
    val originInside = ORIGIN_SUFFIX.find(text)
    val originAfter =
        (element.nextSibling() as? TextNode)
            ?.text()
            ?.replace(NBSP, ' ')
            ?.trimStart()
            ?.startsWith("(") == true
    return when {
        originInside != null -> text.substring(0, originInside.range.first).trim().ifBlank { null }
        originAfter && text.isNotBlank() -> text
        else -> null
    }
}

private fun Element.isBold(): Boolean = tagName() in BOLD_TAGS || (tagName() == "span" && BOLD_STYLE.containsMatchIn(attr("style")))

private const val MIN_ROSTER = 2
private const val NBSP = Typography.nbsp
private val BOLD_TAGS = setOf("strong", "b")
private val BOLD_STYLE = Regex("""font-weight:\s*(?:bold|[6-9]00)""", RegexOption.IGNORE_CASE)

/** `Live - Day 1:`, `Tag 2:`. */
private val DAY_LABEL = Regex("""\b(?:day|tag)\s*(\d)\s*:""", RegexOption.IGNORE_CASE)

/** `Punk- und Hardcore-Festival - Tag 1`. */
private val SUBTITLE_DAY = Regex("""\b(?:day|tag)\s*(\d)\b""", RegexOption.IGNORE_CASE)

/** `Hendrikje Wassenaar (Berlin)`: the origin inside the bold text. */
private val ORIGIN_SUFFIX = Regex("""\s*\([^()]+\)$""")

private val PROGRAMME_LINK_TITLE = Regex("""Tickets (.+) in Berlin am (\d{2}\.\d{2}\.\d{4})""")

private val LINK_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
