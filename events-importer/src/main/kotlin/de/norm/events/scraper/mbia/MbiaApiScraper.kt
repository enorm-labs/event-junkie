package de.norm.events.scraper.mbia

import de.norm.events.common.BERLIN
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.SCRAPER_JSON
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WpRestPage
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.decodeHtmlEntities
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.splitSupportActs
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/**
 * One parsed page of M-BIA's `posts` listing. [oldestDate] ends the walk: the posts are ordered
 * newest-first by their date, which is the night's date, so a page reaching the past is the last.
 */
data class MbiaPage(
    override val items: List<ScrapedEvent>,
    override val postCount: Int,
    val oldestDate: LocalDate?
) : WpRestPage<ScrapedEvent>

/**
 * Pure parser for M-BIA's WordPress posts (`/wp-json/wp/v2/posts?_embed=wp:featuredmedia`). A post is
 * one night: the post date is the night's date, the title its name, the body a ticket link, the
 * featured image its flyer. Past nights are dropped here.
 *
 * Every post date carries `23:00`, a scheduling default the site never shows, so no time is kept.
 * Acts are read only behind a marker in the title: `with`, `w/`, `[pres. …]`, or `•` after the
 * night's name. A bare night name (`Freakquency`) names no act.
 */
class MbiaApiScraper(
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    /** One page of the listing; an unparseable or non-array body yields an empty page, which ends the walk. */
    @Suppress("TooGenericExceptionCaught") // A malformed payload must degrade to an empty page, never abort the import.
    fun scrape(json: String): MbiaPage {
        val posts =
            try {
                SCRAPER_JSON.readTree(json).takeIf { it.isArray }?.toList()
            } catch (e: Exception) {
                logger.warn(e) { "M-BIA posts response is not parseable JSON" }
                null
            } ?: return MbiaPage(items = emptyList(), postCount = 0, oldestDate = null)

        val today = LocalDate.now(clock)
        val events = posts.mapSkippingFailures(logger, "M-BIA post") { parsePost(it, today) }
        val oldest = posts.mapNotNull { parseDate(it.path("date").asString("")) }.minOrNull()
        return MbiaPage(items = events, postCount = posts.size, oldestDate = oldest)
    }

    private fun parsePost(
        post: JsonNode,
        today: LocalDate
    ): ScrapedEvent? {
        val id = post.path("id").asLong(0L).takeIf { it > 0 } ?: return null
        val date = parseDate(post.path("date").asString(""))
        if (date == null) {
            logger.warn { "M-BIA post $id has no parseable date, skipping" }
            return null
        }
        if (date < today) return null
        val title = decodeHtmlEntities(post.path("title").path("rendered").asString("")).blankToNull()?.let(::cleanEventTitle)
        if (title == null) {
            logger.warn { "M-BIA post $id has no title, skipping" }
            return null
        }
        val body = Jsoup.parseBodyFragment(post.path("content").path("rendered").asString(""))
        val ticketUrl = body.selectFirst("a[href^=http]")?.attr("href")?.blankToNull()
        body.select("a").remove()

        return ScrapedEvent(
            title = title,
            description = htmlParagraphText(body.body().html()),
            eventType = EventType.PARTY.name,
            eventDate = date,
            imageUrl =
                post
                    .path("_embedded")
                    .path("wp:featuredmedia")
                    .path(0)
                    .stringOrNull("source_url")
                    ?.takeIf { it.startsWith("http") },
            sourceUrl = post.stringOrNull("link") ?: SITE_URL,
            sourceId = "${EventSource.M_BIA.sourceIdPrefix}$id",
            ticketUrl = ticketUrl,
            artists = actsFromTitle(title)
        )
    }

    /** WordPress's local `yyyy-MM-dd'T'HH:mm:ss` post date, reduced to the night's date. */
    private fun parseDate(raw: String): LocalDate? {
        val cleaned = raw.blankToNull() ?: return null
        return try {
            LocalDateTime.parse(cleaned).toLocalDate()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private companion object {
        const val SITE_URL = "https://www.m-bia.de/"
    }
}

/**
 * The DJs a title names behind a marker: `Rave Cage with Dr. Sheppat & NYXEA`, `Goanautika w./ Bliss`,
 * `GOA Herbst Edition [pres. Naturalize, Vertigos]`, `9Y TEKKNOLOGIE • D-FENCE • FABITEKK`. What
 * stands before the marker is the night's name.
 */
private fun actsFromTitle(title: String): List<ScrapedArtist> {
    val billing =
        PRESENTED_ACTS.find(title)?.groupValues?.get(1)
            ?: WITH_MARKER.find(title)?.let { title.substring(it.range.last + 1) }
            ?: title.split(BULLET).drop(1).joinToString(", ")
    return splitSupportActs(billing)
        .map { it.trim() }
        .filterNot { it.isBlank() || isNonArtistName(it) }
        .distinct()
        .map { ScrapedArtist(name = it, role = "DJ", titleDerived = true) }
}

/** `[pres. <acts>]` in brackets. */
private val PRESENTED_ACTS = Regex("""\[\s*pres(?:ents)?\.?\s+([^\]]+)\]""", RegexOption.IGNORE_CASE)

/** `with`, `w/` or `w./` between the night's name and its acts. */
private val WITH_MARKER = Regex("""\s(?:with|w\.?/)\s+""", RegexOption.IGNORE_CASE)

/** The bullet the site writes between a night's name and each act. */
private val BULLET = Regex("""\s*•\s*""")
