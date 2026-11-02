package de.norm.events.musicbrainz

import de.norm.events.artist.MusicBrainzMatch
import de.norm.events.common.NameArbs
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.shuffle
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.text.Normalizer

/** Properties of the ADR-031 match rule for any name and candidate list: [MusicBrainzMatcherTest] pins the cases the spike saw. */
class MusicBrainzMatcherPropertyTest {
    @Test
    fun `folding a folded name changes nothing`() =
        runTest {
            checkAll(NAME) { name ->
                val folded = MusicBrainzMatcher.fold(name)
                MusicBrainzMatcher.fold(folded) shouldBe folded
            }
        }

    @Test
    fun `folding ignores letter case, accents and spacing`() =
        runTest {
            checkAll(NAME) { name ->
                val folded = MusicBrainzMatcher.fold(name)
                // The Turkish dotless ı uppercases to I, which lowercases to i: no case fold reads it back, Python's included.
                if ('ı' !in name) {
                    MusicBrainzMatcher.fold(name.uppercase()) shouldBe folded
                    MusicBrainzMatcher.fold(name.lowercase()) shouldBe folded
                }
                MusicBrainzMatcher.fold(withoutAccents(name)) shouldBe folded
                MusicBrainzMatcher.fold("  ${name.replace(" ", " \t ")}  ") shouldBe folded
            }
        }

    @Test
    fun `and, und and the ampersand fold alike between two names`() =
        runTest {
            checkAll(WORDS, WORDS) { first, second ->
                val ampersand = MusicBrainzMatcher.fold("$first & $second")
                MusicBrainzMatcher.fold("$first and $second") shouldBe ampersand
                MusicBrainzMatcher.fold("$first UND $second") shouldBe ampersand
            }
        }

    @Test
    fun `the verdict does not depend on the order of the candidates`() =
        runTest {
            checkAll(QUERY) { (name, candidates) ->
                val verdict = MusicBrainzMatcher.decide(name, candidates)
                checkAll(5, Arb.shuffle(candidates)) { shuffled -> MusicBrainzMatcher.decide(name, shuffled) shouldBe verdict }
            }
        }

    @Test
    fun `the verdict agrees with how each candidate's names match`() =
        runTest {
            checkAll(QUERY) { (name, candidates) ->
                val matches = candidates.map { MusicBrainzMatcher.nameMatch(name, it) }
                val byName = candidates.filterIndexed { index, _ -> matches[index] == CandidateNameMatch.NAME }
                val verdict = MusicBrainzMatcher.decide(name, candidates)
                when (verdict.match) {
                    MusicBrainzMatch.EXACT -> byName.map { it.id } shouldContainId verdict.musicbrainzId
                    MusicBrainzMatch.NONE -> matches.all { it == CandidateNameMatch.NONE } shouldBe true
                    else -> (byName.size > 1 || CandidateNameMatch.OTHER_NAME in matches) shouldBe true
                }
                if (byName.size == 1) verdict shouldBe MusicBrainzVerdict(MusicBrainzMatch.EXACT, byName.single().id)
            }
        }

    @Test
    fun `a head is the start of the name, never blank and never digits alone`() =
        runTest {
            checkAll(Arb.choice(NAME, Arb.bind(NAME, Arb.element(" - ", " – ", ": ", " — "), NAME) { a, sep, b -> "$a$sep$b" })) { name ->
                MusicBrainzMatcher.headOf(name)?.let { head ->
                    name.trimStart() shouldStartWith head
                    head.isBlank() shouldBe false
                    head.all(Char::isDigit) shouldBe false
                }
            }
        }

    private infix fun List<String>.shouldContainId(id: String?) = (id in this) shouldBe true

    private companion object {
        private fun withoutAccents(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")

        /** One to three letter words, so no word reads as a conjunction. */
        val WORDS: Arb<String> =
            Arb
                .list(Arb.list(Arb.element(('a'..'z').toList() + "öäüéñøç".toList()), 2..8).map { it.joinToString("") }, 1..3)
                .map { words -> words.filterNot { it in setOf("and", "und") }.ifEmpty { listOf("xy") }.joinToString(" ") }

        /** Act names as billings write them, with the conjunctions and trailing punctuation fold reads, or any name at all. */
        val NAME: Arb<String> =
            Arb.choice(
                Arb.bind(WORDS, Arb.element(" & ", " and ", " und ", " ", " - "), WORDS, Arb.element("", ".", "!", ",", " ")) { a, joiner, b, tail ->
                    "$a$joiner$b$tail"
                },
                NameArbs.anyName
            )

        private val ALIASES: Arb<List<MusicBrainzAlias>> = Arb.list(NAME.map(::MusicBrainzAlias), 0..2)

        val CANDIDATES: Arb<List<MusicBrainzCandidate>> =
            Arb.list(
                Arb.bind(Arb.int(0..5), NAME, NAME.orNull(0.5), Arb.element("DE", "GB", "US").orNull(0.3), ALIASES) { id, name, sortName, country, aliases ->
                    MusicBrainzCandidate(id = "mbid-$id", name = name, sortName = sortName, country = country, aliases = aliases)
                },
                0..5
            )

        /**
         * A name and a candidate list in any order that, besides unrelated candidates, may hold ones whose primary name is the
         * name as MusicBrainz might spell it (another case, accents dropped), in Germany or not, and ones that carry it only as
         * the sort name or an alias.
         */
        val QUERY: Arb<Pair<String, List<MusicBrainzCandidate>>> =
            Arb.bind(NAME, CANDIDATES, Arb.list(Arb.int(0..5), 0..3), Arb.list(Arb.int(0..5), 0..2)) { name, others, byName, byAlias ->
                val spellings = listOf(name, name.uppercase(), withoutAccents(name))
                val named =
                    byName.mapIndexed { index, id ->
                        MusicBrainzCandidate(id = "mbid-$id", name = spellings[index % spellings.size], country = if (index % 2 == 0) "DE" else null)
                    }
                val aliased =
                    byAlias.mapIndexed { index, id ->
                        MusicBrainzCandidate(
                            id = "mbid-$id",
                            name = "$name Orchestra",
                            sortName = name.takeIf { index == 0 },
                            aliases = listOf(MusicBrainzAlias(name))
                        )
                    }
                name to (others + named + aliased).shuffled(kotlin.random.Random(name.hashCode()))
            }
    }
}
