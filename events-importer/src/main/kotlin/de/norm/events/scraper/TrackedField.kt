package de.norm.events.scraper

/**
 * The fields whose per-run coverage is recorded, and what "has a value" means for each (#472).
 *
 * **An enum rather than reflection**, because coverage is a judgement about *content* and not about
 * nullability. A blank string is absent; an empty artist list is absent; `soldOut = false` is a
 * perfectly good value and not absent at all. Reflection would get the last one wrong in the
 * direction that matters — it would report 100% coverage for every boolean, forever, which is a
 * series that can never say anything.
 *
 * **Deliberately absent: `title`, `eventDate`, `sourceUrl`, `sourceId`.** All four are non-null on
 * [ScrapedEvent] by construction, so their coverage is 100% in every run that produces any event at
 * all. Tracking them would add four series that cannot move and four baselines that can never be
 * crossed — noise that makes the ones that *can* move harder to find.
 *
 * `soldOut` and `free` are absent for the same reason from the other direction: they are booleans
 * with a meaningful `false`, so there is no absence to measure.
 *
 * The `key` is what goes in the database and in the metric tag. **Renaming one starts a new series
 * and silently orphans the old baseline** — so treat these strings as an interface, the way
 * `ImporterMetrics` treats its meter names.
 */
enum class TrackedField(
    val key: String,
    private val present: (ScrapedEvent) -> Boolean
) {
    SUBTITLE("subtitle", { !it.subtitle.isNullOrBlank() }),
    DESCRIPTION("description", { !it.description.isNullOrBlank() }),
    EVENT_TYPE("eventType", { !it.eventType.isNullOrBlank() }),
    DOORS_TIME("doorsTime", { it.doorsTime != null }),
    START_TIME("startTime", { it.startTime != null }),
    IMAGE_URL("imageUrl", { !it.imageUrl.isNullOrBlank() }),
    TICKET_URL("ticketUrl", { !it.ticketUrl.isNullOrBlank() }),
    GENRE("genre", { !it.genre.isNullOrBlank() }),
    PRICE_PRESALE("pricePresale", { it.pricePresale != null }),
    PRICE_BOX_OFFICE("priceBoxOffice", { it.priceBoxOffice != null }),
    PRICE_NOTE("priceNote", { !it.priceNote.isNullOrBlank() }),

    /**
     * Any price signal at all.
     *
     * The three price fields above are tracked individually *and* rolled up here, which is not
     * redundant: a venue that moves from a structured price to a free-form note has not lost
     * anything a visitor cares about, and would otherwise flag twice while nothing was wrong.
     * `priceAny` is the series to alert on; the three below it are what says which selector moved.
     */
    PRICE_ANY("priceAny", { it.pricePresale != null || it.priceBoxOffice != null || !it.priceNote.isNullOrBlank() }),

    /** The lineup. An empty list is absence — see the class KDoc for why that needs saying. */
    ARTISTS("artists", { it.artists.isNotEmpty() }),
    PROMOTERS("promoters", { it.promoters.isNotEmpty() });

    /** How many of [events] carry this field. */
    fun countIn(events: Collection<ScrapedEvent>): Int = events.count(present)
}
