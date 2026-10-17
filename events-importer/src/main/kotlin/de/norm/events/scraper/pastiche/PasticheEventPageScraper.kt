package de.norm.events.scraper.pastiche

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.blankToNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Pure parser for one event's Cargo page, which the programme's button links: the poster as the first
 * `media-item` and the text in the second `column-unit` beside it. A `media-item` names only a hash; the
 * file name comes from the page's media list in its scripts. The price sits in a Cargo shop widget that
 * the page renders with JavaScript, so it is not read.
 */
class PasticheEventPageScraper {
    fun enrich(
        event: ScrapedEvent,
        document: Document
    ): ScrapedEvent? {
        val content = document.selectFirst("div.pages bodycopy") ?: return null
        val text = content.selectFirst("column-set > column-unit[slot=1]")?.paragraphText()
        val poster = content.selectFirst("media-item[hash]")?.attr("hash")?.let { posterUrl(document, it) }
        if (text == null && poster == null) return null
        return event.copy(description = text ?: event.description, imageUrl = poster ?: event.imageUrl)
    }

    private fun posterUrl(
        document: Document,
        hash: String
    ): String? {
        val pattern = Regex(""""name":"([^"\\]+)","hash":"${Regex.escape(hash)}"""")
        val name = document.select("script").firstNotNullOfOrNull { pattern.find(it.data())?.groupValues?.get(1) } ?: return null
        return "$POSTER_BASE$hash/${URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")}"
    }

    private companion object {
        const val POSTER_BASE = "https://freight.cargo.site/w/1200/i/"
    }
}

/**
 * The element's text as paragraphs, one per line. Cargo wraps lines with a single `<br>`, which becomes a
 * space; two or more in a row end a paragraph.
 */
internal fun Element.paragraphText(): String? {
    val marked = html().replace(PARAGRAPH_BREAK, PARAGRAPH_MARK).replace(LINE_BREAK, " ")
    return Jsoup
        .parse(marked)
        .text()
        .split(PARAGRAPH_MARK)
        .mapNotNull { it.replace(WHITESPACE, " ").blankToNull() }
        .joinToString("\n")
        .blankToNull()
}

private const val PARAGRAPH_MARK = ""
private val PARAGRAPH_BREAK = Regex("""(?:<br\s*/?>\s*(?:</?span[^>]*>\s*)*){2,}""", RegexOption.IGNORE_CASE)
private val LINE_BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
