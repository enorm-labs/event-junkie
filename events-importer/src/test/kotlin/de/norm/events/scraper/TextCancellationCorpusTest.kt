package de.norm.events.scraper

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The [parseTextCancellation] phrases over the text of every saved venue page. Each fixture here was read by hand: every
 * sentence is a real cancellation (#5084). A new fixture with a hit fails this test, so its sentences are read before the
 * count goes in. A hit that is prose, not a notice, is a phrase to narrow.
 */
class TextCancellationCorpusTest {
    @Test
    fun `the cancellation phrases match only the notices read by hand`() {
        val hits =
            File("src/test/resources/scraper")
                .walk()
                .filter { it.isFile }
                .sortedBy { it.path }
                .associate { file -> file.relativeTo(File("src/test/resources/scraper")).path to cancellationSentences(Jsoup.parse(file.readText()).text()) }
                .filterValues { it.isNotEmpty() }
        val report = hits.entries.joinToString("\n") { (file, sentences) -> "$file:\n" + sentences.joinToString("\n") { "  $it" } }

        withClue(report) { hits.mapValues { it.value.size } shouldBe READ_BY_HAND }
    }

    private companion object {
        val READ_BY_HAND =
            mapOf(
                "binuu/binuu-detail-bedo-patron.html" to 1,
                "columbiahalle/columbiahalle-overview-de-pair.html" to 1,
                "columbiahalle/columbiahalle-overview-en-pair.html" to 1,
                "columbiahalle/columbiahalle-overview-teaser.html" to 3,
                "columbiahalle/columbiahalle-overview.html" to 2,
                "columbiatheater/columbiatheater-detail-cancelled.html" to 1,
                "festsaal/festsaal-api-relocations.json" to 1,
                "gretchen/gretchen-overview.html" to 3,
                "metropol/metropol-detail-loi.html" to 2,
                "metropol/metropol-overview.html" to 1,
                "peteredel/peteredel-overview.html" to 4,
                "quasimodo/quasimodo-detail-otis-kane-cancelled.html" to 1,
                "showfenster/showfenster-overview.html" to 1,
                "urbanspree/urbanspree-detail-cancelled.html" to 1,
                "wuehlmaeuse/wuehlmaeuse-shop-last.json" to 1
            )
    }
}
