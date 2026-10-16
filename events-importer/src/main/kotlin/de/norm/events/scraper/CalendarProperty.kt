package de.norm.events.scraper

/** One content line of an iCalendar component: the raw [value] and its parameters, upper-cased by name. */
internal data class CalendarProperty(
    val value: String,
    val params: Map<String, String> = emptyMap()
) {
    /** The [value] as RFC 5545 TEXT: `\n` a line break, `\,` `\;` `\\` the character itself. */
    val text: String get() = TEXT_ESCAPE.replace(value) { if (it.groupValues[1] in "nN") "\n" else it.groupValues[1] }

    private companion object {
        val TEXT_ESCAPE = Regex("""\\([nN,;\\])""")
    }
}

/**
 * The `VEVENT` components of an iCalendar body, each as its properties by name; the first of a
 * repeated name wins. Folded lines are unfolded first (RFC 5545 §3.1).
 */
internal fun calendarEvents(calendar: String): List<Map<String, CalendarProperty>> =
    VEVENT
        .findAll(calendar.replace(FOLD, ""))
        .map { block ->
            block.groupValues[1]
                .lines()
                .mapNotNull(::contentLine)
                .distinctBy { (name, _) -> name }
                .toMap()
        }.toList()

/** A `NAME;PARAM=value:VALUE` line split at the first colon outside a quoted parameter value. */
private fun contentLine(line: String): Pair<String, CalendarProperty>? {
    var quoted = false
    val colon =
        line.indices.firstOrNull { i ->
            if (line[i] == '"') quoted = !quoted
            line[i] == ':' && !quoted
        } ?: return null
    val head = line.substring(0, colon).split(';')
    val params =
        head
            .drop(1)
            .mapNotNull { param -> param.split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> k.uppercase() to v.trim('"') } }
            .toMap()
    return head.first().uppercase() to CalendarProperty(line.substring(colon + 1), params)
}

/** One `VEVENT` component's content lines. */
private val VEVENT = Regex("""BEGIN:VEVENT\r?\n(.*?)END:VEVENT""", RegexOption.DOT_MATCHES_ALL)

/** A line break followed by the space or tab that marks a continuation line. */
private val FOLD = Regex("""\r?\n[ \t]""")
