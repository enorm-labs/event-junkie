package de.norm.events.mail

import org.springframework.web.util.HtmlUtils

/** The two bodies every mail shares: its paragraphs, then the signature with the site's [origin]. */
object MailBody {
    fun text(
        paragraphs: List<String>,
        origin: String
    ): String = paragraphs.joinToString("\n\n") + "\n\nEvent Junkie\n$origin\n"

    /** Every paragraph is escaped, except the one equal to [link]: [linkParagraph] renders that one from the escaped link. */
    fun html(
        paragraphs: List<String>,
        link: String,
        origin: String,
        linkParagraph: (escapedLink: String) -> String
    ): String {
        val escapedLink = escape(link)
        val body =
            paragraphs.joinToString("\n") { paragraph ->
                if (paragraph == link) linkParagraph(escapedLink) else "<p>${escape(paragraph)}</p>"
            }
        return "<!doctype html><html><body style=\"font-family:sans-serif;line-height:1.5\">$body" +
            "<p>Event Junkie<br>${escape(origin)}</p></body></html>"
    }

    fun escape(text: String): String = HtmlUtils.htmlEscape(text)
}
