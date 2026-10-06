package de.norm.events.feed

/**
 * The calendar subscription's name: "Event Junkie", then the filters in words as the site builds
 * them (#2772). Display text only: it never filters, and a crafted one can only read oddly.
 */
object CalendarName {
    private const val SITE_NAME = "Event Junkie"

    /** The separator the site joins the labels with; a long name is cut at the last one that fits. */
    const val SEPARATOR = " · "

    /** Characters after [SITE_NAME]; a calendar app shows little more in its list. */
    const val MAX_LENGTH = 120

    fun of(name: String?): String {
        val label = name?.filterNot { it.isISOControl() }?.trim()?.let(::cut)
        return if (label.isNullOrBlank()) SITE_NAME else "$SITE_NAME$SEPARATOR$label"
    }

    /** Cuts at the last whole label within the limit, or where the limit falls when the first label is longer. */
    private fun cut(name: String): String {
        val lastWhole = name.lastIndexOf(SEPARATOR, MAX_LENGTH)
        // Never leave half a surrogate pair at the end.
        val hardEnd = if (name.length > MAX_LENGTH && name[MAX_LENGTH - 1].isHighSurrogate()) MAX_LENGTH - 1 else MAX_LENGTH
        return when {
            name.length <= MAX_LENGTH -> name
            lastWhole > 0 -> name.substring(0, lastWhole)
            else -> name.substring(0, hardEnd).trimEnd()
        }
    }
}
