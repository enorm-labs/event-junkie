package de.norm.events.scraper

/**
 * An e-mail address, with a `mailto:` in front of it if the scraper kept the link. The domain needs a dot and a
 * top-level label, so an `@venue` handle is no address. The label is one case run (`de`, `De`, `BERLIN`), so a word
 * the page ran into it stays: `….deWhatsApp:` ends at `de`.
 */
private val EMAIL_ADDRESS =
    Regex("""(?i:mailto:)?[\p{L}\p{N}._%+-]+@[\p{L}\p{N}-]+(?:\.[\p{L}\p{N}-]+)*\.(?:\p{Lu}{2,}|\p{Lu}?\p{Ll}{2,}|\p{Lu}\p{Ll})""")

/**
 * A German phone number: `+49`, `0049`, `0` or `(0`, then digit groups. It never starts after a letter, a digit, `.`, `:` or `/`,
 * so a date, a time, a postcode or a URL path is no number. [isPhoneNumber] counts its digits.
 */
private val PHONE_NUMBER = Regex("""(?<![\p{L}\p{N}.:/,€+])(?:\+49|0049|\(?0)[\d \u00A0/()-]{7,22}\d(?!\d)""")

private const val PHONE_DIGITS_MIN = 9
private const val PHONE_DIGITS_MAX = 14

/** What is left of a line that only introduced the address or number: `E-Mail:`, `Tel.`, `WhatsApp:`, `Buchungen per Mail an`. */
private val CONTACT_LABEL =
    Regex(
        """^[\s\p{P}]*(?:(?:e-?mail|mail|kontakt|contact|booking|buchung(?:en)?|anfragen?|tel|telefon|phone|fon|mobil|handy|whatsapp|reservierung(?:en)?|""" +
            """kartentelefon|call)\b[^\n]{0,40})?[\s\p{P}]*$""",
        RegexOption.IGNORE_CASE
    )

private fun isPhoneNumber(match: MatchResult) = match.value.count(Char::isDigit) in PHONE_DIGITS_MIN..PHONE_DIGITS_MAX

private fun holdsContact(line: String) = EMAIL_ADDRESS.containsMatchIn(line) || PHONE_NUMBER.findAll(line).any(::isPhoneNumber)

/** The mark a cut leaves inside a sentence: the editorial elision, the same in German and English, and left alone by the translator. */
private const val CONTACT_CUT = "[…]"

private val ADJACENT_CUTS = Regex("""\[…](?:[\s/,|;-]*\[…])+""")

private fun withCuts(line: String): String =
    PHONE_NUMBER
        .replace(EMAIL_ADDRESS.replace(line, " $CONTACT_CUT ")) { if (isPhoneNumber(it)) " $CONTACT_CUT " else it.value }
        .replace(ADJACENT_CUTS, CONTACT_CUT)

/**
 * [text] without the e-mail addresses and phone numbers it holds. Both are personal data outside what the hosting
 * contract declares (LEGAL.md §7.3a), and descriptions also go to the translation service. A line that held only
 * a contact and its label goes; inside a sentence the contact becomes [CONTACT_CUT], so the cut reads as one.
 * Null when nothing is left; a text without a contact comes back byte for byte.
 */
fun stripContactDetails(text: String): String? {
    if (!holdsContact(text)) return text
    return text
        .lines()
        .mapNotNull { line ->
            if (!holdsContact(line)) return@mapNotNull line
            val cut = withCuts(line)
            if (CONTACT_LABEL.matches(cut.replace(CONTACT_CUT, ""))) {
                null
            } else {
                val closed = cut.replace(Regex("""\s{2,}"""), " ").replace(Regex("""\s+([.,;:)])"""), "$1").trimEnd()
                if (line.first().isWhitespace()) closed else closed.trimStart()
            }
        }.joinToString("\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
        .ifEmpty { null }
}
