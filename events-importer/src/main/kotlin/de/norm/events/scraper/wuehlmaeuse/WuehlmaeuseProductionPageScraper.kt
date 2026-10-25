package de.norm.events.scraper.wuehlmaeuse

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** What a Wühlmäuse production page adds to its performances: the show text, null when the page has none yet. */
data class WuehlmaeuseProduction(
    val description: String?
)

/**
 * Pure HTML parser for a Wühlmäuse production page (`/veranstaltung/<slug>/`), which every
 * performance of one production links.
 *
 * The show text sits in an Elementor heading widget, after an empty `<h5>`:
 * `<p class="elementor-heading-title"><h5 class="elementor-heading-title"></h5>David Rauterberg … <br /> …`.
 * Jsoup closes the `<p>` at the `<h5>`, so the text is loose nodes beside it, up to the next
 * Elementor section. Lines break at `<br>`; a line that is only a link (`Homepage Rudelsingen`)
 * is dropped. A production not yet described says `Infos folgen…`, which is no text.
 *
 * A page without the widget parses to null, so its performances keep what was stored.
 */
class WuehlmaeuseProductionPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(document: Document): WuehlmaeuseProduction? {
        val heading = document.selectFirst(".elementor-widget-heading h5.elementor-heading-title")
        if (heading == null) {
            logger.warn { "Wühlmäuse production page has no show-text widget, keeping the stored text" }
            return null
        }
        val text = textLinesAfter(heading).joinToString("\n").takeUnless { it.isBlank() || PLACEHOLDER.matches(it) }
        return WuehlmaeuseProduction(text)
    }

    /** The `<br>`-split lines of the inline nodes after [heading], up to the first block element. */
    private fun textLinesAfter(heading: Element): List<String> {
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var onlyLinks = true
        val flush = {
            val line = current.toString().trim()
            if (line.isNotEmpty() && !onlyLinks) lines.add(line)
            current.clear()
            onlyLinks = true
        }
        var node = heading.nextSibling()
        while (node != null) {
            when {
                node is Element && node.tagName() == "br" -> {
                    flush()
                }

                node is Element && node.isBlock -> {
                    break
                }

                node is Element -> {
                    current.append(node.text())
                    if (node.tagName() != "a" && node.text().isNotBlank()) onlyLinks = false
                }

                node is TextNode -> {
                    current.append(node.text())
                    if (node.text().isNotBlank()) onlyLinks = false
                }
            }
            node = node.nextSibling()
        }
        flush()
        return lines
    }

    private companion object {
        val PLACEHOLDER = Regex("""(?i)(weitere\s+)?infos?\s+folgen\W*""")
    }
}
