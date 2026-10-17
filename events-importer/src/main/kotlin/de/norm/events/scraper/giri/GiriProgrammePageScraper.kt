package de.norm.events.scraper.giri

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.endOn
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.isPlaceholderName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseRealDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate

/**
 * Pure parser for one month of Giri's programme. The page renders the month twice: a calendar,
 * whose day cells carry the ISO date and link each event by `data-event-id`, and a hidden list
 * of `article.programme-detail` with the full record. The date comes from the calendar, because
 * the list prints only `21` and `OCT WED`. Acts are the items under the `Lineup` heading, in
 * three markups: `Name — links`, one list per act, or a paragraph per name and per link.
 */
class GiriProgrammePageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        pageUrl: String
    ): List<ScrapedEvent> {
        val dates =
            document
                .select(".giri-calendar-day[data-calendar-date]")
                .flatMap { day ->
                    day.select("a.giri-calendar-event[data-event-id]").map { it.attr("data-event-id") to day.attr("data-calendar-date") }
                }.toMap()
        val articles = document.select(ARTICLE_SELECTOR)
        logger.info { "Found ${articles.size} event(s) on the Giri programme page $pageUrl" }
        return articles.mapSkippingFailures(logger, "Giri programme entry") { parseArticle(it, dates) }
    }

    private fun parseArticle(
        article: Element,
        dates: Map<String, String>
    ): ScrapedEvent? {
        val title = article.textAt("h2")
        val eventKey = article.hrefAt("h2 a")?.let { EVENT_KEY.find(it)?.groupValues?.get(1) }
        val date = parseRealDate(dates[article.id().removePrefix("event-")])
        if (title == null || eventKey == null || date == null) {
            logger.warn { "Giri entry ${article.id()} lacks a title, an event key or a calendar date, skipping" }
            return null
        }
        val times = CLOCK.findAll(article.fact("Time").orEmpty()).mapNotNull { parseTime(it.value.padStart(HH_MM_LENGTH, '0')) }.toList()
        val startTime = times.firstOrNull()
        val endTime = times.getOrNull(1)
        val acts = lineup(article)
        val eventType = eventType(title, article.attr("data-event-category"), acts)
        return ScrapedEvent(
            title = title,
            description = description(article),
            eventType = eventType,
            typeIsFallback = eventType == EventType.PARTY.name && inferUnmarkedTitleType(title) == EventType.OTHER.name,
            eventDate = date,
            startTime = startTime,
            endDate = endTime?.let { endOn(date, startTime, it) },
            endTime = endTime,
            imageUrl = article.imgSrcAt("figure img"),
            sourceUrl = "$PROGRAMME_URL?month=${date.toString().take(YEAR_MONTH_LENGTH)}&view=list#${article.id()}",
            sourceId = "${EventSource.GIRI.sourceIdPrefix}$eventKey",
            ticketUrl = article.hrefAt("a.programme-announcement"),
            genre = article.textAt(".programme-detail-subtitle"),
            priceNote = article.fact("Entry"),
            artists = acts.map { ScrapedArtist(name = it.name, role = roleOf(it, eventType)) }
        )
    }

    /** The `<dd>` beside the `<dt>` that reads [label] in the event's facts list. */
    private fun Element.fact(label: String): String? =
        select("dl.event-facts > div").firstOrNull { it.textAt("dt").equals(label, ignoreCase = true) }?.textAt("dd")

    /** The paragraphs before the first sub-heading; the headings open the lineup, release notes and credits. */
    private fun description(article: Element): String? =
        article
            .selectFirst(".giri-event-description")
            ?.children()
            ?.takeWhile { it.tagName() != "h3" }
            ?.mapNotNull { it.text().blankToNull() }
            ?.joinToString("\n\n")
            .blankToNull()

    private fun lineup(article: Element): List<Act> {
        val heading = article.select(".giri-event-description > h3").firstOrNull { it.text().trim().equals("Lineup", ignoreCase = true) } ?: return emptyList()
        return generateSequence(heading.nextElementSibling()) { it.nextElementSibling() }
            .takeWhile { it.tagName() != "h3" }
            .flatMap { if (it.tagName() == "ul") it.children().asSequence() else sequenceOf(it) }
            .mapNotNull(::actOf)
            .distinctBy { it.name }
            .toList()
    }

    /** One lineup item without its links, or null for an item that is only links or a placeholder. */
    private fun actOf(item: Element): Act? {
        val text =
            item
                .clone()
                .apply { select("a").remove() }
                .text()
                .substringBefore(" — ")
                .trim()
                .trimEnd('—', '·', ' ')
                .blankToNull() ?: return null
        val match = ROLE_NOTE.matchEntire(text)
        val act = if (match != null) Act(match.groupValues[1].trim(), match.groupValues[2]) else Act(text, null)
        return act.takeUnless { isPlaceholderName(it.name) || isNonArtistName(it.name) }
    }

    /** The title's own words first; then a music night is a concert when every act plays live, else a party. */
    private fun eventType(
        title: String,
        category: String,
        acts: List<Act>
    ): String {
        val byTitle = inferUnmarkedTitleType(title).takeUnless { it == EventType.OTHER.name }
        return when {
            byTitle != null -> byTitle
            category != MUSIC_CATEGORY -> EventType.OTHER.name
            acts.isNotEmpty() && acts.all { it.isLive } -> EventType.CONCERT.name
            else -> EventType.PARTY.name
        }
    }

    private fun roleOf(
        act: Act,
        eventType: String
    ): String =
        when {
            eventType != EventType.PARTY.name -> "HEADLINER"
            act.isLive -> "LIVE"
            else -> "DJ"
        }

    private data class Act(
        val name: String,
        val note: String?
    ) {
        val isLive: Boolean get() = note?.contains("live", ignoreCase = true) == true
    }

    private companion object {
        const val ARTICLE_SELECTOR = "article.programme-detail"
        const val MUSIC_CATEGORY = "music"
        const val PROGRAMME_URL = "https://giri.berlin/programme/"
        const val YEAR_MONTH_LENGTH = 7
        const val HH_MM_LENGTH = 5
        val CLOCK = Regex("""\d{1,2}:\d{2}""")

        /** The key in an event's link: `?giri_programme_event=<key>` since 2026-10, `?giri_event=<key>` before. */
        val EVENT_KEY = Regex("""[?&]giri(?:_programme)?_event=([^&#]+)""")

        /** A trailing `(live)`, `(Live Performance)` or `(hybrid live+dj set)`; any other bracket stays in the name. */
        val ROLE_NOTE = Regex("""^(.+?)\s*\(([^()]*\b(?:live|dj|set|performance)\b[^()]*)\)$""", RegexOption.IGNORE_CASE)
    }
}
