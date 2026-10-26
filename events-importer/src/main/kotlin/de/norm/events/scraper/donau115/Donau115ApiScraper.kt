package de.norm.events.scraper.donau115

import de.norm.events.common.BERLIN
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.SCRAPER_JSON
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.dropPastEvents
import de.norm.events.scraper.hasFreeEntryPhrase
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure parser for Donau115's Firebase `events.json`: an object of entries keyed by push id, one entry per act, with
 * `bandName`, an ISO `date`, a `description` and an `images` list. Entries that share a date are one night's bill, as
 * the site shows them, so they become one event keyed by the date, the acts in key order. An entry whose `live` is not
 * `true` is unpublished. A time appears only in the prose or in a title's parenthesis; the night otherwise takes the
 * header's opening time. `contactEmail` is the booker's address, never shown on the site, and is not read.
 */
class Donau115ApiScraper(
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        json: String,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val root = SCRAPER_JSON.readTree(json)
        if (!root.isObject) {
            logger.warn { "Donau115 events payload is not an object" }
            return emptyList()
        }
        val acts = root.properties().map { it.value }.filter { it.path("live").asBoolean(false) }
        logger.info { "Found ${acts.size} published Donau115 act(s)" }
        val (dated, undated) = acts.map { it.stringOrNull("date")?.let(::parseIsoDate) to it }.partition { it.first != null }
        if (undated.isNotEmpty()) logger.warn { "Skipping ${undated.size} Donau115 act(s) without a readable date" }
        val nights = dated.groupBy({ checkNotNull(it.first) }, { it.second })
        val events = nights.entries.mapSkippingFailures(logger, { "Donau115 night ${it.key}" }) { (date, bill) -> night(date, bill, sourceUrl) }
        return events.dropPastEvents(clock) { dropped -> logger.info { "Dropped $dropped past Donau115 night(s)" } }
    }

    private fun night(
        date: LocalDate,
        bill: List<JsonNode>,
        sourceUrl: String
    ): ScrapedEvent? {
        val acts = bill.mapNotNull { act -> act.stringOrNull("bandName")?.let { Act(it, act.stringOrNull("description"), act) } }
        if (acts.isEmpty()) {
            logger.warn { "Donau115 night $date names no act, skipping" }
            return null
        }
        val texts = acts.flatMap { listOfNotNull(it.rawName, it.description) }
        val openStage = acts.all { OPEN_STAGE.containsMatchIn(it.name) }
        val description =
            if (acts.size == 1) {
                acts.single().description
            } else {
                acts.joinToString("\n\n") { listOfNotNull(it.name, it.description).joinToString("\n") }
            }
        return ScrapedEvent(
            title = acts.joinToString(" + ") { it.name },
            description = description,
            eventType = if (openStage) EventType.OTHER.name else EventType.CONCERT.name,
            eventDate = date,
            doorsTime = firstTime(DOORS, texts) ?: HOUSE_OPENING,
            startTime = firstTime(START, texts),
            imageUrl =
                acts.firstNotNullOfOrNull {
                    it.node
                        .path("images")
                        .firstOrNull()
                        ?.asString("")
                        .blankToNull()
                },
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.DONAU115.sourceIdPrefix}$date",
            free = acts.any { hasFreeEntryPhrase(it.description) },
            artists = acts.filterNot { SERIES.containsMatchIn(it.name) }.flatMap { artistsOf(it.name) }
        )
    }

    /** `A | B | C` and `A/ B/ C` bill a group by its members, and a quoted work, `#11` or `SOLO` is not part of an act's name. */
    private fun artistsOf(name: String): List<ScrapedArtist> =
        headlinersFromTitle(name.replace(MEMBER_SEPARATOR, " + ").replace(SERIES_NUMBER, "")).mapNotNull { artist ->
            artist.name
                .replace(QUOTED_WORK, "")
                .replace(SOLO_TAIL, "")
                .blankToNull()
                ?.let { artist.copy(name = it) }
        }

    private fun firstTime(
        label: Regex,
        texts: List<String>
    ): LocalTime? =
        texts.firstNotNullOfOrNull { text ->
            label.find(text)?.let { parseTime("${it.groupValues[1].padStart(2, '0')}:${it.groupValues[2]}") }
        }

    private class Act(
        val rawName: String,
        val description: String?,
        val node: JsonNode
    ) {
        val name: String = cleanEventTitle(rawName.replace(TIMED_PARENTHESIS, ""))
    }

    private companion object {
        /** The header prints `19:30–??:??` above every night. */
        val HOUSE_OPENING: LocalTime = LocalTime.of(19, 30)

        const val CLOCK = """\s*:?\s*(\d{1,2})[:.](\d{2})"""
        val DOORS = Regex("""\b(?:doors|einlass)$CLOCK""", RegexOption.IGNORE_CASE)
        val START = Regex("""\b(?:music starts|start|show|beginn|opening set|set 1)$CLOCK""", RegexOption.IGNORE_CASE)

        /** `Two-Song Tuesday (MUSIC STARTS 20:00)`: the time is read, then cut from the title. */
        val TIMED_PARENTHESIS = Regex("""\s*\([^)]*\d{1,2}[:.]\d{2}[^)]*\)""")

        val OPEN_STAGE = Regex("""\b(?:two-song tuesday|open stage|open mic)\b""", RegexOption.IGNORE_CASE)
        val SERIES = Regex("""\b(?:two-song tuesday|open stage|open mic|jam)\b""", RegexOption.IGNORE_CASE)

        val MEMBER_SEPARATOR = Regex("""\s+\|\s+|\s*/\s+|\s+/\s*""")
        val SERIES_NUMBER = Regex("""\s+#\d+\b""")
        val QUOTED_WORK = Regex("""\s*[“"„][^“”"„]+[”"“]""")
        val SOLO_TAIL = Regex("""\s+solo$""", RegexOption.IGNORE_CASE)
    }
}
