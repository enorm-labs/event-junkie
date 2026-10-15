package de.norm.events.dataquality

import de.norm.events.importing.QualityFlagKind
import de.norm.events.scraper.LimitedAspect
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The Pillar 2 gate's arithmetic (#2604), without a database: shares, the median, and the three guards. */
class DataQualityRegressionCheckTest {
    private val today = LocalDate.of(2026, 10, 9)
    private val genre = QualityIssue.MISSING_GENRE.key
    private val flagged = QualityIssue.FLAGGED_AT_IMPORT.key
    private val keptOut = DataQualityRegressionCheck.FLAGGED_AT_IMPORT_KEPT_OUT

    private fun row(
        daysAgo: Long,
        count: Long,
        total: Long = 100,
        source: String = "alpha",
        metric: String = genre
    ) = DataQualitySnapshotEntity(
        snapshotDate = today.minusDays(daysAgo),
        sourceSlug = source,
        metric = metric,
        metricCount = count,
        totalEvents = total
    )

    /** Seven days of history for `alpha`, each at [counts] of 100 events, newest first. */
    private fun week(vararg counts: Long) = counts.mapIndexed { i, c -> row(daysAgo = i + 1L, count = c) }

    @Test
    fun `a share more than ten points over the 7-day median is a regression`() {
        val verdict = DataQualityRegressionCheck.check(listOf(row(0, 31)), week(20, 20, 20, 20, 20, 20, 20)).single()

        verdict.baselinePct shouldBe 20.0
        verdict.todayPct shouldBe 31.0
        verdict.regressed shouldBe true
    }

    @Test
    fun `exactly ten points worse is not a regression`() {
        DataQualityRegressionCheck.check(listOf(row(0, 30)), week(20, 20, 20)).single().regressed shouldBe false
    }

    @Test
    fun `an improvement, however large, is not a regression`() {
        DataQualityRegressionCheck.check(listOf(row(0, 0)), week(90, 90, 90)).single().regressed shouldBe false
    }

    @Test
    fun `one bad day in the window does not move the median`() {
        // Yesterday's 80 % was a regression already; it was fixed today and today is not judged
        // against a baseline that the bad day pulled up.
        val verdict = DataQualityRegressionCheck.check(listOf(row(0, 25)), week(80, 10, 10, 10, 10, 10, 10)).single()

        verdict.baselinePct shouldBe 10.0
        verdict.regressed shouldBe true
    }

    @Test
    fun `the median of an even number of days is the mean of the middle two`() {
        DataQualityRegressionCheck.check(listOf(row(0, 0)), week(10, 20, 30, 40)).single().baselinePct shouldBe 25.0
    }

    @Test
    fun `shares are compared, not counts, so a source that doubled in size is not a regression`() {
        val today = row(0, count = 40, total = 200)

        DataQualityRegressionCheck.check(listOf(today), week(20, 20, 20)).single().regressed shouldBe false
    }

    @Test
    fun `a source below ten events is not judged`() {
        // One event of nine is 11 points; the gate would fire on every quiet week of a small venue.
        val verdict = DataQualityRegressionCheck.check(listOf(row(0, count = 9, total = 9)), week(0, 0, 0)).single()

        verdict.baselinePct shouldBe null
        verdict.regressed shouldBe false
    }

    @Test
    fun `a source with exactly ten events is judged`() {
        DataQualityRegressionCheck.check(listOf(row(0, count = 5, total = 10)), week(0, 0, 0)).single().regressed shouldBe true
    }

    @Test
    fun `a new source with no history is not judged`() {
        val verdict = DataQualityRegressionCheck.check(listOf(row(0, 100)), emptyList()).single()

        verdict.baselinePct shouldBe null
        verdict.regressed shouldBe false
    }

    @Test
    fun `two days of history are not yet a baseline, three are`() {
        DataQualityRegressionCheck.check(listOf(row(0, 100)), week(0, 0)).single().regressed shouldBe false
        DataQualityRegressionCheck.check(listOf(row(0, 100)), week(0, 0, 0)).single().regressed shouldBe true
    }

    @Test
    fun `rows outside the seven days before today are not part of the baseline`() {
        val outside = listOf(row(daysAgo = 8, count = 0), row(daysAgo = 9, count = 0), row(daysAgo = 0, count = 0))
        val inside = week(50, 50, 50)

        // Only the three 50 % days count: today's own row and the two old 0 % days are ignored.
        DataQualityRegressionCheck.check(listOf(row(0, 55)), outside + inside).single().baselinePct shouldBe 50.0
    }

    @Test
    fun `each source and metric is judged against its own history`() {
        val history = week(10, 10, 10) + (1L..3L).map { row(it, count = 50, source = "beta") }
        val today = listOf(row(0, 30), row(0, 30, source = "beta"))

        DataQualityRegressionCheck.check(today, history).map { it.source to it.regressed } shouldBe
            listOf("alpha" to true, "beta" to false)
    }

    @Test
    fun `totalEvents and suspectNonArtistTitles are not judged`() {
        val today =
            listOf(
                row(0, 100, metric = DataQualityMetrics.TOTAL_EVENTS),
                row(0, 100, metric = "suspectNonArtistTitles")
            )

        DataQualityRegressionCheck.check(today, emptyList()).shouldBeEmpty()
    }

    @Test
    fun `a source with zero events today has no share and is skipped`() {
        DataQualityRegressionCheck.check(listOf(row(0, count = 0, total = 0)), week(10, 10, 10)).shouldBeEmpty()
    }

    @Test
    fun `every QualityIssue but the review queues is checked`() {
        val series = QualityIssue.KEYS - flagged + keptOut
        val today = series.map { row(0, 50, metric = it) }
        val history = series.flatMap { metric -> (1L..3L).map { row(it, 0, metric = metric) } }

        DataQualityRegressionCheck
            .check(today, history)
            .filter { it.regressed }
            .map { it.metric }
            .toSet() shouldBe
            QualityIssue.KEYS.toSet() - DataQualityRegressionCheck.NOT_JUDGED.map { it.key }.toSet()
    }

    @Test
    fun `new acts MusicBrainz does not know at a small venue are not a regression`() {
        // PANDA platforma on 2026-10-14: three unknown acts of 21 events, against seven days at none.
        val issues = DataQualityRegressionCheck.NOT_JUDGED.map { it.key }
        val today = issues.map { row(0, count = 3, total = 21, metric = it) }
        val history = issues.flatMap { metric -> (1L..7L).map { row(it, count = 0, total = 21, metric = metric) } }

        DataQualityRegressionCheck.check(today, history).shouldBeEmpty()
    }

    @Test
    fun `new review flags alone are not a flaggedAtImport regression`() {
        // SO36 on 2026-10-12: 10 % of its events newly flagged UNCERTAIN_PARTY_FEATURE, none kept out.
        val today = listOf(row(0, 10, metric = flagged), row(0, 0, metric = keptOut))
        val history = (1L..7L).flatMap { listOf(row(it, 0, metric = flagged), row(it, 0, metric = keptOut)) }

        val verdict = DataQualityRegressionCheck.check(today, history).single()

        verdict.metric shouldBe flagged
        verdict.todayPct shouldBe 0.0
        verdict.regressed shouldBe false
    }

    @Test
    fun `new kept-out flags are still a flaggedAtImport regression`() {
        val today = listOf(row(0, 12, metric = flagged), row(0, 12, metric = keptOut))
        val history = (1L..7L).flatMap { listOf(row(it, 0, metric = flagged), row(it, 0, metric = keptOut)) }

        val verdict = DataQualityRegressionCheck.check(today, history).single()

        verdict.metric shouldBe flagged
        verdict.todayPct shouldBe 12.0
        verdict.regressed shouldBe true
    }

    @Test
    fun `flaggedAtImport is not judged before its kept-out series has three days`() {
        // The day the series starts, the history holds only the old flaggedAtImport rows.
        val today = listOf(row(0, 50, metric = flagged), row(0, 50, metric = keptOut))
        val history = (1L..7L).map { row(it, 0, metric = flagged) }

        DataQualityRegressionCheck.check(today, history).single().baselinePct shouldBe null
    }

    @Test
    fun `a jump on a metric the source declares as a limitation is not a regression`() {
        // MS Hoppetosse on 2026-10-13: start times at 90 % against a median of 70 %, START_TIME declared.
        val startTime = QualityIssue.MISSING_START_TIME.key
        val today = listOf(row(0, 90, metric = startTime), row(0, 90, source = "beta", metric = startTime))
        val history = (1L..7L).flatMap { listOf(row(it, 70, metric = startTime), row(it, 70, source = "beta", metric = startTime)) }

        val verdicts = DataQualityRegressionCheck.check(today, history, mapOf("alpha" to setOf(LimitedAspect.START_TIME)))

        verdicts.map { Triple(it.source, it.worse, it.regressed) } shouldBe
            listOf(Triple("alpha", true, false), Triple("beta", true, true))
        verdicts.first().limitationAccepted shouldBe true
    }

    @Test
    fun `a declaration of another aspect does not skip the metric`() {
        val declared = mapOf("alpha" to setOf(LimitedAspect.START_TIME, LimitedAspect.PRICE_PRESALE, LimitedAspect.PRICE_NOTE))

        DataQualityRegressionCheck.check(listOf(row(0, 31)), week(20, 20, 20), declared).single().regressed shouldBe true
        DataQualityRegressionCheck
            .check(
                listOf(row(0, 31, metric = QualityIssue.MISSING_PRICE.key)),
                (1L..3L).map { row(it, 20, metric = QualityIssue.MISSING_PRICE.key) },
                declared
            ).single()
            .regressed shouldBe true
    }

    @Test
    fun `flaggedAtImport is judged whatever the source declares`() {
        val today = listOf(row(0, 12, metric = flagged), row(0, 12, metric = keptOut))
        val history = (1L..7L).flatMap { listOf(row(it, 0, metric = flagged), row(it, 0, metric = keptOut)) }

        DataQualityRegressionCheck.check(today, history, mapOf("alpha" to LimitedAspect.entries.toSet())).single().regressed shouldBe true
    }

    @Test
    fun `every QualityIssue is mapped to a limited aspect, always judged or never judged, and only one of them`() {
        val groups =
            listOf(
                DataQualityRegressionCheck.ACCEPTED_ASPECT.keys,
                DataQualityRegressionCheck.ALWAYS_JUDGED,
                DataQualityRegressionCheck.NOT_JUDGED
            )

        groups.sumOf { it.size } shouldBe QualityIssue.entries.size
        groups.flatten().toSet() shouldBe QualityIssue.entries.toSet()
    }

    @Test
    fun `the review kinds in the snapshot query are the forReview kinds of the enum`() {
        QualityFlagKind.REVIEW_KINDS_SQL shouldBe
            QualityFlagKind.entries.filter { it.forReview }.joinToString(", ") { "'${it.name}'" }
    }
}
