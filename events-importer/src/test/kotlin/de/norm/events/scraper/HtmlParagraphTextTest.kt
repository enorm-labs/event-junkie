package de.norm.events.scraper

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class HtmlParagraphTextTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `keeps paragraphs apart from lines`(
        case: String,
        html: String?,
        expected: String?
    ) {
        withClue(case) { htmlParagraphText(html) shouldBe expected }
    }

    companion object {
        @JvmStatic
        fun cases() =
            listOf(
                Arguments.of("closing p is a paragraph", "<p>One</p><p>Two</p>", "One\n\nTwo"),
                Arguments.of("single br is a line", "<p>One<br />Two</p><p>Three</p>", "One\nTwo\n\nThree"),
                Arguments.of("br after br is a paragraph (Sisyphos shape)", "One<br><br>Two<br>Three", "One\n\nTwo\nThree"),
                Arguments.of("br after a div is a paragraph (Humboldthain 13946)", "<div>One</div><br><div>Two</div><div>Three</div>", "One\n\nTwo\nThree"),
                Arguments.of("blank divs between br collapse to one gap (Humboldthain 13944)", "<div>One</div><br><div></div><br><div>Two</div>", "One\n\nTwo"),
                Arguments.of("heading and list end a paragraph", "<h3>Head</h3><ul><li>a</li><li>b</li></ul>After", "Head\n\na\nb\n\nAfter"),
                Arguments.of("formatting newlines in HTML are lines, not gaps", "<p>One\n\nstill one</p>", "One\nstill one"),
                Arguments.of("a blank line in plain text is a paragraph", "One\nline two\n\nTwo", "One\nline two\n\nTwo"),
                Arguments.of("text opens and ends with no break", "<br><p></p><p>One</p><br><br>", "One"),
                Arguments.of("empty body is null", "<p> </p>", null),
                Arguments.of("null is null", null, null)
            )
    }
}
