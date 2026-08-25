package de.norm.events.scraper

import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalTime

/**
 * Unit tests for shared scraping extension functions in `ScrapingExtensions.kt`.
 *
 * These are pure functions with no I/O, so tests use simple Jsoup-parsed
 * HTML snippets — no mocking or Spring context needed.
 */
class ScrapingExtensionsTest {
    /** Helper to parse an HTML snippet into a root [Element]. */
    private fun html(snippet: String): Element = Jsoup.parseBodyFragment(snippet).body()

    @Nested
    inner class TextAt {
        @Test
        fun `returns trimmed text when element matches`() {
            val el = html("""<div><h1 class="title">  Hello World  </h1></div>""")
            el.textAt("h1.title") shouldBe "Hello World"
        }

        @Test
        fun `returns null when no element matches`() {
            val el = html("""<div><p>text</p></div>""")
            el.textAt("h1.missing") shouldBe null
        }

        @Test
        fun `returns null when text is blank`() {
            val el = html("""<div><span class="empty">   </span></div>""")
            el.textAt("span.empty") shouldBe null
        }
    }

    @Nested
    inner class AttrAt {
        @Test
        fun `returns attribute value when element matches`() {
            val el = html("""<div><a class="link" href="https://example.com">click</a></div>""")
            el.attrAt("a.link", "href") shouldBe "https://example.com"
        }

        @Test
        fun `returns null when no element matches`() {
            val el = html("""<div></div>""")
            el.attrAt("a.missing", "href") shouldBe null
        }

        @Test
        fun `returns null when attribute is blank`() {
            val el = html("""<div><a class="link" href="">click</a></div>""")
            el.attrAt("a.link", "href") shouldBe null
        }
    }

    @Nested
    inner class ImgSrcAt {
        @Test
        fun `returns absolute image URL`() {
            val el = html("""<div><img class="photo" src="https://cdn.example.com/img.jpg"></div>""")
            el.imgSrcAt("img.photo") shouldBe "https://cdn.example.com/img.jpg"
        }

        @Test
        fun `returns null for relative image URL`() {
            val el = html("""<div><img class="photo" src="/images/img.jpg"></div>""")
            el.imgSrcAt("img.photo") shouldBe null
        }

        @Test
        fun `returns null when no element matches`() {
            val el = html("""<div></div>""")
            el.imgSrcAt("img.missing") shouldBe null
        }
    }

    @Nested
    inner class HrefAt {
        @Test
        fun `returns absolute href`() {
            val el = html("""<div><a class="ticket" href="https://tickets.example.com/buy">buy</a></div>""")
            el.hrefAt("a.ticket") shouldBe "https://tickets.example.com/buy"
        }

        @Test
        fun `returns null for relative href`() {
            val el = html("""<div><a class="ticket" href="/buy">buy</a></div>""")
            el.hrefAt("a.ticket") shouldBe null
        }

        @Test
        fun `returns null when no element matches`() {
            val el = html("""<div></div>""")
            el.hrefAt("a.missing") shouldBe null
        }
    }

    @Nested
    inner class HasVisibleWebflowFlag {
        @Test
        fun `returns true when flag is visible and text matches`() {
            val el = html("""<div><span class="flag sold-out">Sold-Out</span></div>""")
            el.hasVisibleWebflowFlag(".flag.sold-out", "Sold-Out") shouldBe true
        }

        @Test
        fun `returns false when flag has w-condition-invisible class`() {
            val el = html("""<div><span class="flag sold-out w-condition-invisible">Sold-Out</span></div>""")
            el.hasVisibleWebflowFlag(".flag.sold-out", "Sold-Out") shouldBe false
        }

        @Test
        fun `returns false when text does not match`() {
            val el = html("""<div><span class="flag sold-out">Cancelled</span></div>""")
            el.hasVisibleWebflowFlag(".flag.sold-out", "Sold-Out") shouldBe false
        }

        @Test
        fun `matches text case-insensitively`() {
            val el = html("""<div><span class="flag sold-out">sold-out</span></div>""")
            el.hasVisibleWebflowFlag(".flag.sold-out", "Sold-Out") shouldBe true
        }

        @Test
        fun `returns false when no element matches selector`() {
            val el = html("""<div><span>Sold-Out</span></div>""")
            el.hasVisibleWebflowFlag(".flag.sold-out", "Sold-Out") shouldBe false
        }
    }

    @Nested
    inner class ParseTime {
        @Test
        fun `parses valid HH-mm time`() {
            parseTime("19:30") shouldBe LocalTime.of(19, 30)
        }

        @Test
        fun `trims whitespace before parsing`() {
            parseTime("  20:00  ") shouldBe LocalTime.of(20, 0)
        }

        @Test
        fun `returns null for null input`() {
            parseTime(null) shouldBe null
        }

        @Test
        fun `returns null for blank input`() {
            parseTime("   ") shouldBe null
        }

        @Test
        fun `returns null for unparseable text`() {
            parseTime("TBA") shouldBe null
        }

        @Test
        fun `returns null for invalid time values`() {
            parseTime("25:00") shouldBe null
        }
    }

    @Nested
    inner class IsPlaceholderName {
        @Test
        fun `TBA is a placeholder`() {
            isPlaceholderName("TBA") shouldBe true
        }

        @Test
        fun `TBD is a placeholder`() {
            isPlaceholderName("TBD") shouldBe true
        }

        @Test
        fun `N-N- is a placeholder`() {
            isPlaceholderName("N.N.") shouldBe true
        }

        @Test
        fun `t-b-a- with dots is a placeholder`() {
            isPlaceholderName("t.b.a.") shouldBe true
        }

        @Test
        fun `case-insensitive matching`() {
            isPlaceholderName("tba") shouldBe true
        }

        @Test
        fun `trims whitespace`() {
            isPlaceholderName("  TBA  ") shouldBe true
        }

        @Test
        fun `real artist name is not a placeholder`() {
            isPlaceholderName("Aska") shouldBe false
        }
    }

    @Nested
    inner class ResolveUrl {
        @Test
        fun `returns absolute href as-is`() {
            resolveUrl("https://venue.com/events", "https://other.com/bar") shouldBe "https://other.com/bar"
        }

        @Test
        fun `resolves relative path against base URL`() {
            resolveUrl("https://venue.com/events", "/event/foo") shouldBe "https://venue.com/event/foo"
        }

        @Test
        fun `resolves relative path without leading slash`() {
            resolveUrl("https://venue.com/events/", "foo") shouldBe "https://venue.com/events/foo"
        }

        @Test
        fun `handles http prefix in href`() {
            resolveUrl("https://venue.com", "http://insecure.com/page") shouldBe "http://insecure.com/page"
        }
    }

    @Nested
    inner class TextLines {
        @Test
        fun `textLinesAt splits the matched element on br, trimming and dropping blanks`() {
            val article = html("""<div class="event__subtitle">+ Support: Jeff Clarke<br><br>ABGESAGT. Ticket info.</div>""")
            article.textLinesAt(".event__subtitle") shouldBe listOf("+ Support: Jeff Clarke", "ABGESAGT. Ticket info.")
        }

        @Test
        fun `textLinesAt returns an empty list when nothing matches`() {
            html("<div>text</div>").textLinesAt(".missing") shouldBe emptyList()
        }

        @Test
        fun `textLines splits the element's own text on br`() {
            val paragraph = html("<p>Lineup:<br>Alexa Fluor<br>ALIS.</p>").selectFirst("p")!!
            paragraph.textLines() shouldBe listOf("Lineup:", "Alexa Fluor", "ALIS.")
        }

        // Only a direct-child `<br>` breaks a line: text inside a nested inline element belongs to
        // the line it sits on, which is what lets a bolded act name stay attached to its own line.
        @Test
        fun `textLines appends nested inline text to the current line`() {
            val paragraph = html("<p><strong>Support:</strong> Jeff Clarke<br>ALIS.</p>").selectFirst("p")!!
            paragraph.textLines() shouldBe listOf("Support: Jeff Clarke", "ALIS.")
        }
    }
}
