package de.norm.events.event

/**
 * A field of an event that a hand edit pins, so the next import keeps the stored value (ADR-042).
 * [key] is its name in `event.pinned_fields` and in the admin API: the [EventRequest] property, or a join table.
 * A kept column keeps its derived columns too, so a kept date keeps its end and `event_end_after_start` holds.
 */
enum class PinnedField(
    val key: String,
    private val valueOf: ((EventEntity) -> Any?)? = null,
    private val keep: ((EventEntity, EventEntity) -> EventEntity)? = null
) {
    VENUE_ID("venueId", { it.venueId }, { e, s -> e.copy(venueId = s.venueId) }),
    TITLE("title", { it.title }, { e, s -> e.copy(title = s.title) }),
    SUBTITLE("subtitle", { it.subtitle }, { e, s -> e.copy(subtitle = s.subtitle) }),
    DESCRIPTION("description", { it.description }, { e, s -> e.withDescriptionOf(s) }),
    EVENT_TYPE("eventType", { it.eventType }, { e, s -> e.copy(eventType = s.eventType, typeIsFallback = s.typeIsFallback) }),
    STATUS("status", { it.status }, { e, s -> e.copy(status = s.status, relocatedTo = s.relocatedTo) }),
    EVENT_DATE("eventDate", { it.eventDate }, { e, s -> e.copy(eventDate = s.eventDate, endDate = s.endDate, endTime = s.endTime) }),
    DOORS_TIME("doorsTime", { it.doorsTime }, { e, s -> e.copy(doorsTime = s.doorsTime) }),
    START_TIME("startTime", { it.startTime }, { e, s -> e.copy(startTime = s.startTime) }),
    IMAGE_URL("imageUrl", { it.imageUrl }, { e, s -> e.copy(imageUrl = s.imageUrl, imageWithheld = s.imageWithheld) }),
    SOURCE_URL("sourceUrl", { it.sourceUrl }, { e, s -> e.copy(sourceUrl = s.sourceUrl) }),
    TICKET_URL("ticketUrl", { it.ticketUrl }, { e, s -> e.copy(ticketUrl = s.ticketUrl) }),
    FACEBOOK_EVENT_URL("facebookEventUrl", { it.facebookEventUrl }, { e, s -> e.copy(facebookEventUrl = s.facebookEventUrl) }),
    GENRE("genre", { it.genre }, { e, s -> e.copy(genre = s.genre) }),
    PRICE_PRESALE("pricePresale", { it.pricePresale }, { e, s -> e.copy(pricePresale = s.pricePresale) }),
    PRICE_BOX_OFFICE("priceBoxOffice", { it.priceBoxOffice }, { e, s -> e.copy(priceBoxOffice = s.priceBoxOffice) }),
    PRICE_CURRENCY("priceCurrency", { it.priceCurrency }, { e, s -> e.copy(priceCurrency = s.priceCurrency) }),
    PRICE_NOTE("priceNote", { it.priceNote }, { e, s -> e.copy(priceNote = s.priceNote) }),
    SOLD_OUT("soldOut", { it.soldOut }, { e, s -> e.copy(soldOut = s.soldOut) }),
    FREE("free", { it.free }, { e, s -> e.copy(free = s.free) }),

    /** The `event_artist` rows: who plays, in which role, order and room. */
    LINEUP("lineup"),

    /** The `event_promoter` rows. */
    PROMOTERS("promoters"),

    /** The `event_genre_tag` rows, which an edit of [GENRE] rewrites. */
    GENRES("genres");

    /** Whether this field is a column of `event`, rather than a join table. */
    val isColumn: Boolean get() = valueOf != null

    /** Whether [edited] holds another value for this column than [stored]. Always false for a join table. */
    fun differs(
        edited: EventEntity,
        stored: EventEntity
    ): Boolean = valueOf != null && valueOf(edited) != valueOf(stored)

    /** Whether [row] holds no value for this column, the gap an enrichment source may fill (ADR-043). Always false for a join table. */
    fun isEmptyOn(row: EventEntity): Boolean = valueOf != null && valueOf(row) == null

    /** [row] with this column, and what is derived from it, taken from [from]. A join table changes nothing. */
    fun takeFrom(
        row: EventEntity,
        from: EventEntity
    ): EventEntity = keep?.invoke(row, from) ?: row

    companion object {
        /**
         * The fields an enrichment source may fill (ADR-043 rule 4): the facts and the three join
         * tables. The date, the title and the status are the main source's, and the flags have no
         * empty value. The description and the image stay out: they are works, and their licence is
         * the main source's to grant (#283), so the venue's page stays their only origin.
         */
        val ENRICHABLE: Set<PinnedField> =
            setOf(
                SUBTITLE,
                DOORS_TIME,
                START_TIME,
                TICKET_URL,
                GENRE,
                PRICE_PRESALE,
                PRICE_BOX_OFFICE,
                PRICE_NOTE,
                LINEUP,
                PROMOTERS,
                GENRES
            )

        /** The columns `event.slug` is built from. A row that pins any of them keeps its slug. */
        private val SLUG_INPUTS = setOf(VENUE_ID, TITLE, EVENT_DATE)

        /** The field named [key], or null for a name no field has. */
        fun fromKey(key: String): PinnedField? = entries.firstOrNull { it.key == key }

        /** The fields [keys] names. A key that names no field is skipped, so an old pin cannot fail an import. */
        fun of(keys: Collection<String>): Set<PinnedField> = keys.mapNotNullTo(mutableSetOf(), ::fromKey)

        /** Every key, for the message that refuses an unknown one. */
        val keys: List<String> get() = entries.map { it.key }

        /**
         * [built] with each column pinned on [stored] taken from [stored], and the pins carried over.
         * [built] is the row the source would write, and it holds no pins of its own.
         */
        fun keepPinned(
            built: EventEntity,
            stored: EventEntity?
        ): EventEntity {
            if (stored == null || stored.pinnedFields.isEmpty()) return built
            val pins = of(stored.pinnedFields)
            val kept = pins.fold(built.copy(pinnedFields = stored.pinnedFields)) { row, field -> field.keep?.invoke(row, stored) ?: row }
            return if (pins.any { it in SLUG_INPUTS }) kept.copy(slug = stored.slug) else kept
        }
    }
}

/** This row with [stored]'s description and everything derived from it: the language, the withheld flag, the translation. */
private fun EventEntity.withDescriptionOf(stored: EventEntity): EventEntity =
    copy(
        description = stored.description,
        descriptionLanguage = stored.descriptionLanguage,
        descriptionLanguageConfidence = stored.descriptionLanguageConfidence,
        descriptionWithheld = stored.descriptionWithheld,
        descriptionAlt = stored.descriptionAlt,
        descriptionAltLanguage = stored.descriptionAltLanguage,
        descriptionAltOrigin = stored.descriptionAltOrigin,
        descriptionAltEngine = stored.descriptionAltEngine,
        descriptionAltSourceHash = stored.descriptionAltSourceHash,
        descriptionAltRefusedHash = stored.descriptionAltRefusedHash
    )

/** Thrown when a pin is named by a key no [PinnedField] has. */
class UnknownPinnedFieldException(
    field: String
) : RuntimeException("Unknown pinned field '$field'. Pinnable: ${PinnedField.keys.joinToString(", ")}")
