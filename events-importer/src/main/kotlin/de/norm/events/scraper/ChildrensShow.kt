package de.norm.events.scraper

/**
 * Whether a venue bills a night as a show for small children: `für Kinder`, a `Familien-` show, a `Kinder…` format, or
 * an age range for them (`0-18 Monate`, `ab 4 Jahre`). EVENT_SCOPE.md §3.5 keeps these out (#2832).
 *
 * A band named after children is no format and stays (`Muttis Kinder`, `Kinderzimmer Productions`, `Lichterkinder`), and
 * so does a show for teenagers (`ab 13 Jahre`). A venue calls this only where its own page uses these words.
 */
fun billsChildrensShow(
    title: String,
    subtitle: String?
): Boolean = CHILDRENS_SHOW.containsMatchIn("$title ${subtitle.orEmpty()}")

private val CHILDRENS_SHOW =
    Regex(
        listOf(
            """f(?:ü|ue)r\s+(?:kinder|kids|die\s+kleinen)\b""",
            """\bfamilien(?:konzert|vorstellung|-\w+|\w*show)""",
            """\bkinder(?:philharmonie|theater|konzert|disco|disko|-\w+)""",
            """\(\s*\d+\s*-\s*\d+\s*(?:monate|jahre)\s*\)""",
            """\bab\s+(?:[1-9]|1[0-2])\s+jahren?\b""",
            """\bkidz\b"""
        ).joinToString("|"),
        RegexOption.IGNORE_CASE
    )
