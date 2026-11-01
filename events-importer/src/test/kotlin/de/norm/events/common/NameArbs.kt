package de.norm.events.common

import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string

/** Generated names for property tests: the characters venue billings actually carry, plus any string at all. */
object NameArbs {
    private val ascii = ('a'..'z') + ('A'..'Z') + ('0'..'9')
    private val separators = " -&/.,!'`´@$:()+x".toList() + listOf('\t', ' ', '’', '–')
    private val latin = "öäüÖÄÜßẞøØæÆðÐþÞłŁđĐıİéÉñåÅœŒėğş".toList()
    private val cyrillic = "абвгдеёжзийклмнопрстуфхцчшщъыьэюяєїіґАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ".toList()
    private val greek = "αβγδεζηθικλμνξοπρσςτυφχψωΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΦΧΨΩάέήίόύώ".toList()

    private fun from(chars: List<Char>): Arb<String> = Arb.list(Arb.element(chars), 0..30).map { it.joinToString("") }

    /** A billing in Latin script: letters of both cases, digits, accents and the punctuation that splits or styles names. */
    val latinName: Arb<String> = from(ascii + latin + separators)

    /** A billing in Cyrillic or Greek, with the separators and digits a venue adds around it. */
    val nonLatinName: Arb<String> = Arb.choice(from(cyrillic + separators + ('0'..'9')), from(greek + separators + ('0'..'9')))

    /** Any of the above, or an arbitrary string from Kotest's default code points. */
    val anyName: Arb<String> = Arb.choice(latinName, nonLatinName, from(ascii + latin + cyrillic + greek + separators), Arb.string(0..30))
}
