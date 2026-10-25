package de.norm.events.scraper

/**
 * An e-mail address, with a `mailto:` in front of it if the scraper kept the link. The domain needs a dot and a
 * top-level label, so an `@venue` handle is no address.
 */
private val EMAIL_ADDRESS = Regex("""(?:mailto:)?[\p{L}\p{N}._%+-]+@[\p{L}\p{N}-]+(?:\.[\p{L}\p{N}-]+)*\.\p{L}{2,}""", RegexOption.IGNORE_CASE)

/** What is left of a line that only introduced the address: `E-Mail:`, `Kontakt –`, `Buchungen per Mail an`. */
private val ADDRESS_LABEL =
    Regex(
        """^[\s\p{P}]*(?:(?:e-?mail|mail|kontakt|contact|booking|buchung(?:en)?|anfragen?)\b[^\n]{0,40})?[\s\p{P}]*$""",
        RegexOption.IGNORE_CASE
    )

/**
 * [text] without the e-mail addresses it holds. A stored address is personal data outside what the hosting
 * contract declares (LEGAL.md §7.3a), and descriptions also go to the translation service. A line that held only
 * an address and its label goes, and the text closes up. Null when nothing is left; a text without an address
 * comes back byte for byte.
 */
fun stripEmailAddresses(text: String): String? {
    if (!EMAIL_ADDRESS.containsMatchIn(text)) return text
    return text
        .lines()
        .mapNotNull { line ->
            if (!EMAIL_ADDRESS.containsMatchIn(line)) return@mapNotNull line
            val rest = line.replace(EMAIL_ADDRESS, "")
            if (ADDRESS_LABEL.matches(rest)) null else rest.replace(Regex("""\s{2,}"""), " ").replace(Regex("""\s+([.,;:)])"""), "$1").trimEnd()
        }.joinToString("\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
        .ifEmpty { null }
}
