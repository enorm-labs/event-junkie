package de.norm.events.dataquality

import java.time.LocalDate

/**
 * The Pillar 2 gate (#2604): which of today's [QualityIssue] shares got worse than the source's own
 * recent history.
 *
 * **The baseline is the snapshot table, not a stored file.** Each source and metric is compared with
 * the median of its own share over the [BASELINE_DAYS] days before today. Nobody maintains a
 * baseline, and a fix that lowers a share lowers the baseline a week later on its own.
 *
 * **A median, not a mean or yesterday's value.** One bad day in the window does not move a median,
 * so a regression that is fixed the next morning does not also flag the day after the fix as normal.
 *
 * Three guards keep the gate from firing on noise:
 * - [MIN_EVENTS]: a source with five events moves twenty points when one event changes.
 * - [MIN_BASELINE_DAYS]: the median of one or two days is that day, not a baseline. A new source
 *   is therefore not judged in its first three days.
 * - [MAX_WORSENING_POINTS]: a share measured in percentage points, so the gate reads the same for a
 *   source at 2 % and one at 60 %.
 *
 * Every metric in [QualityIssue] counts something bad, so a rise is always the worse direction.
 * `totalEvents` and `suspectNonArtistTitles` are in the table too and are skipped: the first is the
 * denominator, and the second counts names rather than events.
 */
object DataQualityRegressionCheck {
    /** How far back the baseline reaches, today excluded. */
    const val BASELINE_DAYS = 7L

    /** The least number of days with a row that makes a median a baseline. */
    const val MIN_BASELINE_DAYS = 3

    /** The least number of events today for a source to be judged at all. */
    const val MIN_EVENTS = 10L

    /** A rise of more than this many percentage points over the baseline is a regression. */
    const val MAX_WORSENING_POINTS = 10.0

    private const val PERCENT = 100.0
    private val ISSUE_KEYS = QualityIssue.KEYS.toSet()

    /**
     * One judged source and metric.
     *
     * [baselinePct] is `null` when the source had too few days of history or too few events today to
     * be judged; [regressed] is then false.
     */
    data class Verdict(
        val source: String,
        val metric: String,
        val todayPct: Double,
        val baselinePct: Double?,
        val totalEvents: Long
    ) {
        val regressed: Boolean
            get() = baselinePct != null && todayPct - baselinePct > MAX_WORSENING_POINTS
    }

    /** The first day of the baseline window for [today]. The last day is the day before [today]. */
    fun baselineStart(today: LocalDate): LocalDate = today.minusDays(BASELINE_DAYS)

    /**
     * One [Verdict] per [QualityIssue] row in [today], in the order of [today].
     *
     * [history] can hold any rows; only those of the [BASELINE_DAYS] days before the date of each
     * [today] row count. A row with zero events has no share and is ignored on both sides.
     */
    fun check(
        today: List<DataQualitySnapshotEntity>,
        history: List<DataQualitySnapshotEntity>
    ): List<Verdict> {
        val shares =
            history
                .filter { it.metric in ISSUE_KEYS && it.totalEvents > 0 }
                .groupBy { it.sourceSlug to it.metric }

        return today
            .filter { it.metric in ISSUE_KEYS && it.totalEvents > 0 }
            .map { row ->
                val window = baselineStart(row.snapshotDate)..row.snapshotDate.minusDays(1)
                val past =
                    shares[row.sourceSlug to row.metric]
                        .orEmpty()
                        .filter { it.snapshotDate in window }
                        .map { share(it) }
                val judged = row.totalEvents >= MIN_EVENTS && past.size >= MIN_BASELINE_DAYS
                Verdict(
                    source = row.sourceSlug,
                    metric = row.metric,
                    todayPct = share(row),
                    baselinePct = if (judged) median(past) else null,
                    totalEvents = row.totalEvents
                )
            }
    }

    private fun share(row: DataQualitySnapshotEntity): Double = row.metricCount * PERCENT / row.totalEvents

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
