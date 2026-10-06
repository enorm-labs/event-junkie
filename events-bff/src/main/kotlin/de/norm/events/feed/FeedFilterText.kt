package de.norm.events.feed

import de.norm.events.event.EventFilter
import de.norm.events.event.EventType
import de.norm.events.event.TimeOfDay
import java.math.BigDecimal

/**
 * A feed's filters in words, in the site's vocabulary, for a title and a description (#2765). The
 * dates of an [EventFilter] are the endpoint's own and are not words here. A value the site has no
 * word for, such as a district or an artist slug, stays as the visitor wrote it.
 */
object FeedFilterText {
    /** What a title can name: at most one event type and one venue, and no other filter. */
    data class Nameable(
        val eventType: String?,
        val venueSlug: String?
    )

    /** Plural, lower-case where the language allows, beside the site's singular `eventType.json`. */
    private val EVENT_TYPE_PLURALS =
        mapOf(
            "en" to
                mapOf(
                    EventType.CONCERT to "concerts",
                    EventType.FESTIVAL to "festivals",
                    EventType.PARTY to "parties",
                    EventType.QUIZ to "quizzes",
                    EventType.SHOW to "shows",
                    EventType.COMEDY to "comedy",
                    EventType.SCREENING to "screenings",
                    EventType.EXHIBITION to "exhibitions",
                    EventType.READING to "readings",
                    EventType.OTHER to "other events"
                ),
            "de" to
                mapOf(
                    EventType.CONCERT to "Konzerte",
                    EventType.FESTIVAL to "Festivals",
                    EventType.PARTY to "Partys",
                    EventType.QUIZ to "Quiz",
                    EventType.SHOW to "Shows",
                    EventType.COMEDY to "Comedy",
                    EventType.SCREENING to "Filmvorführungen",
                    EventType.EXHIBITION to "Ausstellungen",
                    EventType.READING to "Lesungen",
                    EventType.OTHER to "sonstige Veranstaltungen"
                )
        )

    private val TIMES_OF_DAY =
        mapOf(
            "en" to mapOf(TimeOfDay.DAYTIME to "daytime", TimeOfDay.EVENING to "evening", TimeOfDay.LATE to "late"),
            "de" to mapOf(TimeOfDay.DAYTIME to "tagsüber", TimeOfDay.EVENING to "abends", TimeOfDay.LATE to "nachts")
        )

    /** The words of a description; a `%s` is where the value goes. */
    private enum class Word {
        EVENT_TYPE,
        VENUE,
        DISTRICT,
        VENUE_TYPE,
        ARTIST,
        PROMOTER,
        GENRE,
        FAMILY,
        PRICE,
        SEARCH,
        TIME_OF_DAY,
        NO_SOLD_OUT,
        FREE_ONLY,
        PRICE_FROM,
        PRICE_UP_TO,
        QUOTED,
        DECIMAL_SEPARATOR
    }

    private val WORDS =
        mapOf(
            "en" to
                mapOf(
                    Word.EVENT_TYPE to "event type",
                    Word.VENUE to "venue",
                    Word.DISTRICT to "district",
                    Word.VENUE_TYPE to "venue type",
                    Word.ARTIST to "artist",
                    Word.PROMOTER to "promoter",
                    Word.GENRE to "genre",
                    Word.FAMILY to "genre family",
                    Word.PRICE to "presale price",
                    Word.SEARCH to "search",
                    Word.TIME_OF_DAY to "time of night",
                    Word.NO_SOLD_OUT to "no sold-out events",
                    Word.FREE_ONLY to "free only",
                    Word.PRICE_FROM to "from %s",
                    Word.PRICE_UP_TO to "up to %s",
                    Word.QUOTED to "“%s”",
                    Word.DECIMAL_SEPARATOR to "."
                ),
            "de" to
                mapOf(
                    Word.EVENT_TYPE to "Veranstaltungsart",
                    Word.VENUE to "Location",
                    Word.DISTRICT to "Bezirk",
                    Word.VENUE_TYPE to "Location-Art",
                    Word.ARTIST to "Künstler",
                    Word.PROMOTER to "Veranstalter",
                    Word.GENRE to "Genre",
                    Word.FAMILY to "Genrefamilie",
                    Word.PRICE to "Vorverkaufspreis",
                    Word.SEARCH to "Suche",
                    Word.TIME_OF_DAY to "Tageszeit",
                    Word.NO_SOLD_OUT to "ohne ausverkaufte",
                    Word.FREE_ONLY to "nur kostenlose",
                    Word.PRICE_FROM to "ab %s",
                    Word.PRICE_UP_TO to "bis %s",
                    Word.QUOTED to "„%s“",
                    Word.DECIMAL_SEPARATOR to ","
                )
        )

    /** [type], an upper-case [EventType] name, as a plural in [locale]; null for a type the site does not know. */
    fun eventTypePlural(
        locale: String,
        type: String
    ): String? = EventType.entries.find { it.name == type }?.let { EVENT_TYPE_PLURALS.getValue(locale).getValue(it) }

    /** The type and venue of [filter] when they are all it holds, else null: two types, or any other filter. */
    fun nameable(filter: EventFilter): Nameable? {
        val others =
            filter.copy(from = null, to = null, runningFrom = null, on = null, eventTypes = emptyList(), venueSlug = null)
        return if (filter.eventTypes.size <= 1 && others == EventFilter()) Nameable(filter.eventTypes.singleOrNull(), filter.venueSlug) else null
    }

    /**
     * Every filter of [filter] in [locale], one entry each: `event type: concerts, parties`. [venueName]
     * replaces the venue slug when the venue exists.
     */
    fun describe(
        locale: String,
        filter: EventFilter,
        venueName: String?
    ): List<String> {
        val words = WORDS.getValue(locale)::getValue

        fun named(
            label: String,
            values: List<String>
        ) = values.takeIf { it.isNotEmpty() }?.let { "$label: ${it.joinToString(", ")}" }
        return listOfNotNull(
            named(words(Word.EVENT_TYPE), filter.eventTypes.map { eventTypePlural(locale, it) ?: it }),
            named(words(Word.VENUE), listOfNotNull(filter.venueSlug?.let { venueName ?: it })),
            named(words(Word.DISTRICT), filter.districts),
            named(words(Word.VENUE_TYPE), filter.venueTypes),
            named(words(Word.ARTIST), listOfNotNull(filter.artistSlug)),
            named(words(Word.PROMOTER), listOfNotNull(filter.promoterSlug)),
            named(words(Word.GENRE), listOfNotNull(filter.genreSlug)),
            named(words(Word.FAMILY), filter.familySlugs),
            named(words(Word.PRICE), listOfNotNull(price(words, filter.minPrice, filter.maxPrice))),
            named(words(Word.SEARCH), listOfNotNull(filter.query?.let { words(Word.QUOTED).replace("%s", it) })),
            named(
                words(Word.TIME_OF_DAY),
                filter.timesOfDay.map { slug ->
                    TimeOfDay.entries.find { it.slug == slug }?.let { TIMES_OF_DAY.getValue(locale)[it] } ?: slug
                }
            ),
            words(Word.NO_SOLD_OUT).takeIf { filter.excludeSoldOut },
            words(Word.FREE_ONLY).takeIf { filter.onlyFree }
        )
    }

    private fun price(
        words: (Word) -> String,
        min: BigDecimal?,
        max: BigDecimal?
    ): String? {
        fun euros(amount: BigDecimal) = "${amount.stripTrailingZeros().toPlainString().replace(".", words(Word.DECIMAL_SEPARATOR))} €"
        return when {
            min != null && max != null -> "${euros(min).removeSuffix(" €")}–${euros(max)}"
            min != null -> words(Word.PRICE_FROM).replace("%s", euros(min))
            max != null -> words(Word.PRICE_UP_TO).replace("%s", euros(max))
            else -> null
        }
    }
}
