package de.norm.events.scraper

/** Where one line-up slot joins two acts. The venues differ on purpose: at VOID Club an `&` belongs to the name. */
enum class SlotJoins {
    /** The slot bills one act. */
    NONE,

    /** At a `b2b` only. */
    B2B,

    /** At a `b2b` and a ` x `. A slot with a round bracket is one act, as the bracket holds a duo's members. */
    B2B_AND_CROSS,

    /** At a `b2b`, a `feat.`, a ` x ` and a conjunction. A slot with a round bracket is one act, as the bracket holds a duo's members. */
    B2B_AND_CONJUNCTIONS,

    /** At a ` x ` and a conjunction outside brackets; the import boundary splits the `b2b`. */
    CONJUNCTIONS;

    internal fun split(slot: String): List<String> =
        when (this) {
            NONE -> listOf(slot)
            B2B -> slot.split(B2B_SEPARATOR)
            B2B_AND_CROSS -> if ('(' in slot) listOf(slot) else slot.split(B2B_SEPARATOR).flatMap(::splitCrossBilled)
            B2B_AND_CONJUNCTIONS -> if ('(' in slot) listOf(slot) else slot.split(B2B_SEPARATOR).flatMap(::splitGuestsAndConjunctions)
            CONJUNCTIONS -> splitSegmentOnConjunctions(slot)
        }
}

private fun splitGuestsAndConjunctions(part: String): List<String> = splitOnFeaturedGuest(part).flatMap(::splitSegmentOnConjunctions)
