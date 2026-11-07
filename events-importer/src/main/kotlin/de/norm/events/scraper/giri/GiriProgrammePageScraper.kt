package de.norm.events.scraper.giri

import de.norm.events.event.ArtistRole
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
import java.time.LocalTime

/**
 * Pure parser for one month of Giri's programme. The page renders a calendar whose
 * `a.calendar-event` links carry the event's id, ISO date, category and own page, and one
 * `<template data-event-content>` per id holding the full `article.event-detail`. Jsoup parses a
 * template's content as ordinary children, so `select` reaches it. Acts are the items under the
 * `Lineup` heading, in three markups: `Name — links`, one list per act, or a paragraph per name
 * and per link.
 */
class GiriProgrammePageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        pageUrl: String
    ): List<ScrapedEvent> {
        val links = document.select(CALENDAR_LINK_SELECTOR).distinctBy { it.attr("data-event") }
        logger.info { "Found ${links.size} event(s) on the Giri programme page $pageUrl" }
        return links.mapSkippingFailures(logger, "Giri programme entry") { parseEvent(it, document) }
    }

    private fun parseEvent(
        link: Element,
        document: Document
    ): ScrapedEvent? {
        val id = link.attr("data-event")
        val article = document.selectFirst("template[data-event-content=\"$id\"] article.event-detail")
        val title = article?.textAt("h2")
        val date = parseRealDate(link.attr("data-event-date"))
        if (article == null || title == null || date == null) {
            logger.warn { "Giri entry $id lacks an event record, a title or a calendar date, skipping" }
            return null
        }
        val startClocks = clocks(article.fact("Time") ?: article.fact("Start"))
        val doorsClocks = clocks(article.fact("Doors"))
        val startTime = startClocks.firstOrNull()
        val doorsTime = doorsClocks.firstOrNull()
        val endTime = startClocks.ifEmpty { doorsClocks }.getOrNull(1)
        val acts = lineup(article)
        val eventType = eventType(title, link.attr("data-event-category"), acts)
        return ScrapedEvent(
            title = title,
            description = description(article),
            eventType = eventType,
            typeIsFallback = eventType == EventType.PARTY.name && inferUnmarkedTitleType(title) == EventType.OTHER.name,
            eventDate = date,
            doorsTime = doorsTime,
            startTime = startTime,
            endDate = endTime?.let { endOn(date, startTime ?: doorsTime, it) },
            endTime = endTime,
            imageUrl = article.imgSrcAt(".event-detail-art img"),
            sourceUrl = link.absUrl("href").blankToNull() ?: PROGRAMME_URL,
            sourceId = "${EventSource.GIRI.sourceIdPrefix}$id",
            ticketUrl = article.hrefAt(".event-primary-action a"),
            genre = article.textAt("p.event-subtitle"),
            priceNote = article.fact("Entry"),
            artists = acts.map { ScrapedArtist(name = it.name, role = roleOf(it, eventType)) }
        )
    }

    private fun clocks(fact: String?): List<LocalTime> = CLOCK.findAll(fact.orEmpty()).mapNotNull { parseTime(it.value.padStart(HH_MM_LENGTH, '0')) }.toList()

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
    ): ArtistRole =
        when {
            eventType != EventType.PARTY.name -> ArtistRole.HEADLINER
            act.isLive -> ArtistRole.LIVE
            else -> ArtistRole.DJ
        }

    private data class Act(
        val name: String,
        val note: String?
    ) {
        val isLive: Boolean get() = note?.contains("live", ignoreCase = true) == true
    }

    private companion object {
        const val CALENDAR_LINK_SELECTOR = "a.calendar-event[data-event][data-event-date]"
        const val MUSIC_CATEGORY = "music"
        const val PROGRAMME_URL = "https://giri.berlin/programme/"
        const val HH_MM_LENGTH = 5
        val CLOCK = Regex("""\d{1,2}:\d{2}""")

        /** A trailing `(live)`, `(Live Performance)` or `(hybrid live+dj set)`; any other bracket stays in the name. */
        val ROLE_NOTE = Regex("""^(.+?)\s*\(([^()]*\b(?:live|dj|set|performance)\b[^()]*)\)$""", RegexOption.IGNORE_CASE)
    }
}
