package de.norm.events.scraper

/**
 * Whether a venue's text says the night is closed to the public: `Eintritt nur für geladene Gäste` (Kesselhaus, #4422), a
 * `Geschlossene Gesellschaft`, a `Privatveranstaltung`. Nobody can buy a way in, so it is not imported.
 *
 * Pass the ticket or note text, not a bare title: Sartre's play is called `Geschlossene Gesellschaft`.
 */
fun isClosedToPublic(text: String?): Boolean = text != null && CLOSED_TO_PUBLIC.containsMatchIn(text)

private val CLOSED_TO_PUBLIC =
    Regex(
        listOf(
            """\bnur\s+f(?:ü|ue)r\s+(?:geladene|eingeladene)\s+g(?:ä|ae)ste\b""",
            """\bgeschlossene\s+gesellschaft\b""",
            """\bprivat\s*veranstaltung\b""",
            """\bprivate\s+veranstaltung\b"""
        ).joinToString("|"),
        RegexOption.IGNORE_CASE
    )
