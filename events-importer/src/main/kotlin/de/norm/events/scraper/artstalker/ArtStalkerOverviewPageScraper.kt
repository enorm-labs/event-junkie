package de.norm.events.scraper.artstalker

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.knownGenresInStyleTail
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parseGermanMonthAbbreviation
import de.norm.events.scraper.splitDjList
import de.norm.events.scraper.stripFriendsTail
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalTime

private val logger = KotlinLogging.logger {}

/**
 * Pure parser for one page of the venue's listing, one `a[data-sync-id]` card per event in
 * `.p-venue__event-list`: the `time` lines hold the date and the start (`19:00 - 22:00 Uhr` starts at
 * 19:00), and the `h3` the title. The type and the artists are read here, from the title alone
 * ([splitTagline]); the event page adds nothing that decides either. A party names no act in its
 * title, but a `Mit DJ …` tagline bills its DJ (event 15018).
 */
class ArtStalkerOverviewPageScraper {
    fun scrape(document: Document): List<ScrapedEvent> =
        document
            .select(".p-venue__event-list a[data-sync-id]")
            .mapSkippingFailures(logger, { card -> "an ART Stalker card: ${card.attr("href")}" }) { parseCard(it) }

    private fun parseCard(card: Element): ScrapedEvent? {
        val url = card.absUrl("href")
        val id = SHOP_EVENT_ID.find(url)?.groupValues?.get(1)
        val rawTitle = card.textAt("h3")
        val times = card.select("time").joinToString(" ") { it.text() }
        val date = cardDate(times)
        if (id == null || rawTitle.isNullOrBlank() || date == null) {
            logger.warn { "Skipping an ART Stalker card without an id, a title or a date: $url" }
            return null
        }
        val (billing, endDate) = splitRun(rawTitle, date)
        val (title, subtitle) = splitTagline(billing)
        val eventType = inferConcertVenueType(title, subtitle?.replace(NEGATED_CUE, "")?.replace(TAGLINE_PARTY, ""), VENUE_FORMATS)
        val openStage = OPEN_STAGE.containsMatchIn(title)
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            genre = knownGenresInStyleTail(subtitle),
            eventType = eventType,
            eventDate = date,
            endDate = endDate,
            startTime = CARD_CLOCK.find(times)?.let { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) },
            imageUrl = card.imgSrcAt("img"),
            sourceUrl = url,
            sourceId = "${EventSource.ART_STALKER.sourceIdPrefix}$id",
            ticketUrl = url,
            artists =
                when {
                    openStage -> emptyList()
                    eventType == EventType.PARTY.name -> taglineDjs(subtitle)
                    else -> buildArtistsForEventType(billedActs(title), subtitle, eventType)
                }
        )
    }

    private fun cardDate(times: String): LocalDate? {
        val (day, month, year) = CARD_DATE.find(times)?.destructured ?: return null
        // The long forms `Juni`, `Juli` and `Sept.` share their first three letters with the abbreviation.
        val monthOfYear = parseGermanMonthAbbreviation(month.take(MONTH_LETTERS)) ?: return null
        return runCatching { LocalDate.of(year.toInt(), monthOfYear, day.toInt()) }.getOrNull()
    }

    /** The DJs a party's tagline names: `Mit DJ Louie Prima`. */
    private fun taglineDjs(subtitle: String?): List<ScrapedArtist> =
        TAGLINE_DJS
            .find(subtitle.orEmpty())
            ?.groupValues
            ?.get(1)
            ?.let(::splitDjList)
            .orEmpty()
            .mapNotNull { it.trim().blankToNull() }
            .filterNot(::isNonArtistName)
            .map { ScrapedArtist(name = it, role = ArtistRole.DJ) }

    private companion object {
        val CARD_DATE = Regex("""(\d{1,2})\.\s*(\p{L}+)\.?\s+(\d{4})""")
        val CARD_CLOCK = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b""")
        const val MONTH_LETTERS = 3

        /** A tagline that only names the night's DJs. */
        val TAGLINE_DJS = Regex("""^mit\s+(DJs?\s+.+)$""", RegexOption.IGNORE_CASE)

        /** The music school's student showcase, a house format that is no concert of a billed act. */
        val VENUE_FORMATS = mapOf("musikschule" to EventType.OTHER.name)

        /**
         * The weekly open session and an open mic: live music with no billed act, so a concert
         * whose title names nobody (#2992).
         */
        val OPEN_STAGE = Regex("""\bsession\b|\bopen\s+mic\b""", RegexOption.IGNORE_CASE)

        /** A cue the tagline denies, which types nothing: `Keine Karaoke Maschine. Eine echte Live-Band` (#3792). */
        val NEGATED_CUE = Regex("""\bkein(?:e|en|er)?\s+[\p{L}-]+""", RegexOption.IGNORE_CASE)

        /**
         * `Party` in a tagline promises a mood, not a DJ night: `Beat Pilots - Authentic 60s sound - Ein Abend voller Nostalgie
         * und Party` is the band's concert (events 15499, 15516). A party says so in its title.
         */
        val TAGLINE_PARTY = Regex("""\bparty\b""", RegexOption.IGNORE_CASE)
    }
}

/**
 * The venue bills `<name> - <tagline>`: `Dan Patlansky - Blues Rock`, `New Breed - Classic Rock -
 * Von Hendrix bis Foo Fighters`. The first space-padded hyphen splits the name from the tagline,
 * which becomes the subtitle; an en dash stays, since `Deer Anna – For the BirdsTour` is one name.
 */
internal fun splitTagline(raw: String): Pair<String, String?> {
    val trimmed = raw.trim()
    val cut = TAGLINE_SEPARATOR.find(trimmed) ?: return trimmed to null
    val head = trimmed.substring(0, cut.range.first).trim()
    val tail = trimmed.substring(cut.range.last + 1).trim()
    return if (head.isEmpty() || tail.isEmpty()) trimmed to null else head to tail
}

/**
 * An exhibition bills its run after the tagline: `Ausstellung: Face it & own it! - Lars Koepke & Vincent
 * J. E. Sieck - 26.11.2026 - 09.01.2027` (#5588). The pair is cut off, and its second date is the end,
 * when the first is [eventDate] and the second is after it; any other title stays whole, with no end.
 */
internal fun splitRun(
    raw: String,
    eventDate: LocalDate
): Pair<String, LocalDate?> {
    val run = TRAILING_RUN.find(raw.trim()) ?: return raw to null
    val start = parseGermanDate(run.groupValues[1])
    val end = parseGermanDate(run.groupValues[2])
    return if (start == eventDate && end != null && end.isAfter(start)) raw.trim().substring(0, run.range.first) to end else raw to null
}

/**
 * The part of a title that names the acts. A one-word format label before them is no act
 * (`Doppelkonzert: Glam Jam + Mission BlueZ`), and a tribute night names the band after
 * "präsentiert von" (`BAD COMPANY- und FREE-Songs präsentiert von FREE COMPANY`), a sing-along
 * after "mit der Band" (`Mitsing Konzert mit der Band The Rollin’ Horses`), a tribute after "Tribute mit"
 * (`Beatles Tribute mit Big Tom and the Bogsnorklers`, event 13448). With the plural verb the acts
 * come first, and their `& Friends` are the evening's guests: `George Kranz & Friends präsentieren das
 * brandneue Album …` bills `George Kranz` (#4980). A trailing `live`, `live und in Farbe` or
 * `and the Band` is billing, not a second act after `und` (#3749, #3792).
 */
internal fun billedActs(title: String): String {
    val acts =
        (PRESENTED_BY.find(title) ?: WITH_THE_BAND.find(title) ?: TRIBUTE_WITH.find(title))
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?: PRESENT_THE_SHOW.find(title)?.let { stripFriendsTail(it.groupValues[1].trim()) }
            ?: title.replaceFirst(FORMAT_LABEL, "").ifBlank { title }
    return acts
        .replaceFirst(LIVE_TAIL, "")
        .replaceFirst(BAND_TAIL, "")
        .ifBlank { acts }
}

/** The shop's event number, the last path segment: `…-am-3-10-2026/e2535642`. */
internal val SHOP_EVENT_ID = Regex("""/e(\d+)$""")

private val TAGLINE_SEPARATOR = Regex("""\s-\s""")
private val TRAILING_RUN = Regex("""\s-\s(\d{1,2}\.\d{1,2}\.\d{4})\s-\s(\d{1,2}\.\d{1,2}\.\d{4})$""")
private val FORMAT_LABEL = Regex("""^\p{L}+:\s+""")
private val LIVE_TAIL = Regex("""\s+live(?:\s+und\s+in\s+farbe)?$""", RegexOption.IGNORE_CASE)
private val PRESENTED_BY = Regex("""\spräsentiert\s+von\s+(.+)$""", RegexOption.IGNORE_CASE)
private val PRESENT_THE_SHOW = Regex("""^(.+?)\s+präsentieren\s+(?!von\b)""", RegexOption.IGNORE_CASE)
private val WITH_THE_BAND = Regex("""\smit\s+der\s+band\s+(.+)$""", RegexOption.IGNORE_CASE)
private val TRIBUTE_WITH = Regex("""\btribute\s+mit\s+(.+)$""", RegexOption.IGNORE_CASE)
private val BAND_TAIL = Regex("""\s+(?:and\s+the|&(?:\s+the)?|und(?:\s+die)?|mit\s+der)\s+band$""", RegexOption.IGNORE_CASE)
