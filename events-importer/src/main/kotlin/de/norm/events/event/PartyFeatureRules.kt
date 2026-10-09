package de.norm.events.event

/** One [feature] found in an event's text, and the [phrase] that states it as the text has it. */
data class PartyFeatureMatch(
    val feature: PartyFeature,
    val phrase: String
)

/**
 * What [PartyFeatureRules.detect] read in one event: the [features] a clear phrase states, and the
 * [uncertain] cues no clear phrase settles, each with its surrounding words for the worklist.
 */
data class PartyFeatureDetection(
    val features: List<PartyFeatureMatch>,
    val uncertain: List<PartyFeatureMatch>
)

/**
 * Keyword rules per language, German and English, that read the [PartyFeature]s off an event's own
 * text (#2631). Cheap and explainable: each feature keeps the phrase that set it.
 *
 * Each feature has three lists. A **clear** phrase sets the feature ("FLINTA* only", "Dresscode:
 * Fetisch"). A **negation** cancels any phrase it overlaps ("kein Dresscode", "no dress code"). A
 * **cue** without a clear phrase ("FLINTA*", "queer artists", "after hour") sets nothing; the
 * importer lists it in the data-quality worklist, where a steward reads the night and decides.
 *
 * The caller passes only stored text: the title, the subtitle and the description as the licence
 * gate kept them. A withheld description is not stored, so nothing here can read it.
 */
object PartyFeatureRules {
    /** Words around a cue for the worklist entry, on each side. */
    private const val CUE_CONTEXT = 40

    /** How far a clause reaches on each side of a clear phrase when the rules look for who it is about. */
    private const val CLAUSE_REACH = 80

    /** The longest phrase kept. A clear phrase is short; this bounds the bounded-gap patterns. */
    private const val MAX_PHRASE = 120

    // Letters and digits, so `FLINTA*` ends at its star and `Geburtstagsparty` has no word start at `tags`.
    private const val B = "(?<![\\p{L}\\p{N}])"
    private const val E = "(?![\\p{L}\\p{N}])"
    private const val FUER = "f(?:ü|ue)r"
    private const val FLINTA = "flinta[*+]*"
    private const val DRESS_CODE = "(?:dress[\\s-]?code|kleiderordnung)"
    private const val NIGHT = "(?:party|partys|parties|rave|raves|night|nights|nacht|clubnacht|club[\\s-]?night|disco|tanzparty|tanznacht|ball)"

    /**
     * "Kein Dresscode", "Dress code: none.", "Dresscode? Be yourself.": cancels both dress-code features. A "no" ends its clause
     * here, so "Dresscode: no sneakers" stays a code.
     */
    private val NO_DRESS_CODE =
        listOf(
            "$B(?:no|kein(?:en|e)?|ohne)\\s+(?:strict\\s+|strengen?\\s+)?$DRESS_CODE",
            "$B$DRESS_CODE\\s*[:=?-]?\\s*(?:none|no|nope|keine?r?|casual|egal|whatever|come\\s+as\\s+you\\s+are|be\\s+yourself|" +
                "(?:komm\\s+)?wie\\s+du\\s+bist|sei\\s+du\\s+selbst)\\s*(?:[.,;!)\\n]|$)"
        )

    /**
     * Who plays or takes part, not who gets in: "open decks", "DJ", "Auflegen", "workshop", "line-up", "B2B", "jam". A FLINTA*
     * phrase in the same clause as one of these is about the decks, not the door ("the space behind the decks as exclusively
     * for FLINTA*"), so it is a cue.
     */
    private val PERFORMER_CLAUSE =
        listOf(
            "$B(?:open[\\s-]?)?decks?$E",
            "${B}dj(?:s|anes?|[\\s-]?ing)?$E",
            "${B}aufleg\\p{L}*",
            "${B}workshops?$E",
            "${B}line[\\s-]?ups?$E",
            "${B}b2b$E",
            "${B}jam(?:s|[\\s-]?sessions?)?$E"
        )

    /** Who gets in: a clause with one of these keeps a clear FLINTA* phrase, even next to "decks" ("FLINTA* only party with open decks"). */
    private val DOOR_CLAUSE =
        listOf("$B(?:einlass|eintritt|entry|entrance|admission|doors?|tür)$E", "$B$NIGHT$E", "${B}events?$E", "${B}veranstaltung(?:en)?$E")

    private class Rule(
        val feature: PartyFeature,
        clear: List<String>,
        cues: List<String> = emptyList(),
        negations: List<String> = emptyList(),
        performerOnly: List<String> = emptyList(),
        door: List<String> = emptyList()
    ) {
        val clear = clear.map(::pattern)
        val cues = cues.map(::pattern)
        val negations = negations.map(::pattern)
        val performerOnly = performerOnly.map(::pattern)
        val door = door.map(::pattern)

        /** A clear phrase whose clause names who performs and not who gets in states a role, not the feature. */
        fun aboutPerformers(clause: String) = performerOnly.any { it.containsMatchIn(clause) } && door.none { it.containsMatchIn(clause) }
    }

    private fun pattern(source: String) = Regex("(?iu)$source")

    private val RULES =
        listOf(
            Rule(
                PartyFeature.FLINTA_ONLY,
                clear =
                    listOf(
                        "$B$FLINTA[\\s-]*(?:only|exclusive|exklusiv)$E",
                        "$B(?:only|exclusively|nur|ausschließlich|ausschliesslich)\\s+(?:$FUER\\s+|for\\s+)?$FLINTA",
                        "$B(?:no|keine?)\\s+cis[\\s-]*(?:men|männer|maenner|males?|dudes)$E"
                    ),
                cues = listOf("$B$FLINTA"),
                performerOnly = PERFORMER_CLAUSE,
                door = DOOR_CLAUSE
            ),
            Rule(
                PartyFeature.QUEER,
                clear =
                    listOf(
                        "${B}queer(?:e|en|er|es)?[\\s-]+$NIGHT$E",
                        "$B(?:gay|lesbian|lesbische?n?|schwule?n?|lgbt[qia+*]*)[\\s-]+$NIGHT$E",
                        "$B$NIGHT\\s+(?:$FUER|for)\\s+(?:queers|queer\\s+(?:people|folks?|community)|(?:die\\s+)?queere\\s+(?:menschen|community|szene))$E"
                    ),
                cues = listOf("${B}queer", "${B}lgbt")
            ),
            Rule(
                PartyFeature.SEX_POSITIVE,
                clear = listOf("${B}sex[\\s-]*positiv(?:e|er|es|en|ity|ität)?$E"),
                cues = listOf("${B}dark[\\s-]?room", "${B}play[\\s-]?(?:room|area)$E", "${B}spielwiese$E")
            ),
            Rule(
                PartyFeature.FETISH_DRESS_CODE,
                clear =
                    listOf(
                        "$B(?:fetish|fetisch)[\\s-]*(?:$DRESS_CODE|pflicht|only|required|mandatory|gear\\s+(?:only|required|mandatory))",
                        "$B$DRESS_CODE\\s*[:=-]?[^.!?\\n]{0,40}?$B(?:fetish|fetisch|latex|leather|leder|rubber|gummi|lack|pvc|bdsm)"
                    ),
                cues = listOf("$B(?:fetish|fetisch)"),
                negations = NO_DRESS_CODE
            ),
            Rule(
                PartyFeature.DRESS_CODE,
                clear = listOf("$B$DRESS_CODE"),
                cues =
                    listOf(
                        "${B}dress(?:ed)?\\s+to\\s+impress$E",
                        "${B}dress\\s+up$E",
                        "$B(?:costumes?|kostüme?|verkleidung)\\s+(?:welcome|encouraged|appreciated|erwünscht|gern\\s+gesehen)$E"
                    ),
                negations = NO_DRESS_CODE
            ),
            Rule(
                PartyFeature.NO_PHOTO_POLICY,
                clear =
                    listOf(
                        "$B(?:no|keine?)\\s+(?:photos?|pictures|pics|photography|fotos?|bilder)$E",
                        "$B(?:photos?|pictures|photography|filming|taking\\s+pictures|fotos?|fotografieren|filmen|fotografie)\\s+" +
                            "(?:is\\s+|are\\s+|sind\\s+|ist\\s+)?(?:strictly\\s+|streng(?:stens)?\\s+)?" +
                            "(?:not\\s+allowed|prohibited|forbidden|banned|verboten|untersagt|nicht\\s+erlaubt|nicht\\s+gestattet)$E",
                        "$B(?:foto|fotografier|photo|kamera|handy)verbot$E",
                        "${B}no[\\s-]?photo(?:s|graphy)?[\\s-]+policy$E",
                        "$B(?:stickers?|aufkleber)\\s+(?:on|over|auf|über)\\s+(?:your\\s+|eure\\s+|deine\\s+|die\\s+|the\\s+)?" +
                            "(?:phones?|handys?|cameras?|kameras?)",
                        "$B(?:kameras?|cameras?|handy[\\s-]?kameras?|phone\\s+cameras?)\\s+(?:werden\\s+|will\\s+be\\s+|get\\s+|are\\s+)?" +
                            "(?:abgeklebt|taped|covered|stickered)$E"
                    ),
                cues = listOf("$B(?:no|keine?)\\s+(?:phones?|handys?|cameras?|kameras?)$E", "$B(?:phone|handy)[\\s-]?(?:free|frei)$E")
            ),
            Rule(
                PartyFeature.OPEN_END,
                clear = listOf("${B}open[\\s-]?end$E", "${B}ende\\s*[:=]?\\s*offen$E"),
                cues =
                    listOf(
                        "${B}after[\\s-]?hours?$E",
                        "$B(?:till|until|bis)\\s+(?:sunrise|dawn|sonnenaufgang|morgengrauen|the\\s+morning|in\\s+den\\s+(?:morgen|tag|vormittag))$E",
                        "$B(?:24|36|48|72)[\\s-]*(?:h|hours?|stunden)$E"
                    )
            ),
            Rule(
                PartyFeature.DAY_PARTY,
                clear =
                    listOf(
                        "${B}day(?:time)?[\\s-]?(?:party|parties|rave|raves|dance)$E",
                        "${B}tag(?:es)?[\\s-]?(?:party|rave|tanz)$E",
                        "$B(?:party|rave|tanzen|dancing)\\s+(?:am|bei|by|in)\\s+(?:the\\s+)?(?:tag|tage|day|daylight|daytime|tageslicht)$E"
                    ),
                cues =
                    listOf(
                        "$B(?:daytime|tagsüber|daylight|tageslicht)$E",
                        "$B(?:sonntags?|sunday)[\\s-]?(?:party|rave|tanz|afternoon|nachmittag)",
                        "$B(?:sonntag|samstag|sunday|saturday)s?\\s+(?:ab|from)\\s+(?:1[0-6]|[6-9])(?:[:.]00)?\\s*(?:uhr|h|am|pm)?$E"
                    )
            )
        )

    /** The features [texts] state, and the cues they leave open. A blank or null text is skipped. */
    fun detect(vararg texts: String?): PartyFeatureDetection {
        val text = texts.filterNot { it.isNullOrBlank() }.joinToString("\n")
        if (text.isEmpty()) return PartyFeatureDetection(emptyList(), emptyList())
        val features = mutableListOf<PartyFeatureMatch>()
        val uncertain = mutableListOf<PartyFeatureMatch>()
        RULES.forEach { rule ->
            val negated = rule.negations.flatMap { it.findAll(text).map { match -> match.range }.toList() }
            val free = { match: MatchResult -> negated.none { it.overlaps(match.range) } }
            val clearMatches = rule.clear.flatMap { regex -> regex.findAll(text).filter(free).toList() }
            val (performers, door) = clearMatches.partition { rule.aboutPerformers(clause(text, it.range)) }
            val clear = door.firstOrNull()
            if (clear != null) {
                features += PartyFeatureMatch(rule.feature, clear.value.squeezed().take(MAX_PHRASE))
            } else {
                (performers.firstOrNull() ?: rule.cues.firstNotNullOfOrNull { regex -> regex.findAll(text).firstOrNull(free) })
                    ?.let { uncertain += PartyFeatureMatch(rule.feature, context(text, it.range)) }
            }
        }
        // A fetish code is the more specific fact, so it settles the plain dress code either way.
        if (features.any { it.feature == PartyFeature.FETISH_DRESS_CODE }) {
            features.removeAll { it.feature == PartyFeature.DRESS_CODE }
            uncertain.removeAll { it.feature == PartyFeature.DRESS_CODE }
        }
        return PartyFeatureDetection(features.sortedBy { it.feature.ordinal }, uncertain.sortedBy { it.feature.ordinal })
    }

    /** The sentence around [range], up to [CLAUSE_REACH] characters on each side: a stop, "!", "?" or a line break ends it. */
    private fun clause(
        text: String,
        range: IntRange
    ): String {
        val stops = ".!?\n"
        val floor = (range.first - CLAUSE_REACH).coerceAtLeast(0)
        val start = (range.first - 1 downTo floor).firstOrNull { text[it] in stops }?.plus(1) ?: floor
        val ceiling = (range.last + 1 + CLAUSE_REACH).coerceAtMost(text.length)
        val end = (range.last + 1 until ceiling).firstOrNull { text[it] in stops } ?: ceiling
        return text.substring(start, end)
    }

    private fun IntRange.overlaps(other: IntRange) = first <= other.last && other.first <= last

    /** The cue with up to [CUE_CONTEXT] characters on each side in whole words, an ellipsis where the text goes on. */
    private fun context(
        text: String,
        range: IntRange
    ): String {
        var start = (range.first - CUE_CONTEXT).coerceAtLeast(0)
        if (start > 0 && !text[start - 1].isWhitespace()) {
            start = (start until range.first).firstOrNull { text[it].isWhitespace() }?.plus(1) ?: range.first
        }
        var end = (range.last + 1 + CUE_CONTEXT).coerceAtMost(text.length)
        if (end < text.length && !text[end].isWhitespace()) {
            end = (end - 1 downTo range.last + 1).firstOrNull { text[it].isWhitespace() } ?: (range.last + 1)
        }
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + text.substring(start, end).squeezed() + suffix
    }

    private fun String.squeezed() = trim().replace(Regex("\\s+"), " ")
}
