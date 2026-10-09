<script lang="ts" setup>
/**
 * Der Opt-out-Weg für Locations — die **maßgebliche Fassung** (LEGAL.md §6.1).
 *
 * Der Weg selbst steht in `docs/SCRAPING_POSITION.md` §5. Diese Seite ist seine Veröffentlichung:
 * eine Zusage, die eine Location nicht findet, ist keine Zusage (#789).
 *
 * **Register:** `ihr` — der Plural des `du`, in dem der Rest der Seite spricht. Hier steht ein
 * Team hinter einer Location und keine einzelne Leserin.
 *
 * Was „Wie wir lesen“ behauptet, ist `SCRAPING_POSITION.md` §2 in Kurzform. Ändert sich das
 * Verhalten des Importers, ändert sich dieser Abschnitt mit — sonst beschreibt die Seite ein
 * System, das es nicht mehr gibt.
 *
 * **Beide Sprachfassungen ändern oder keine.**
 *
 * Markdown, beim Build zu einer Komponente übersetzt (vite.config.ts, #471). Eine
 * `<section>`-Zeile, eine Leerzeile, das Markdown, eine Leerzeile und `</section>`: ohne die
 * Leerzeilen wird der Text darin nicht als Markdown gelesen.
 */
import { RouterLink } from 'vue-router'

import LegalPage from '@/components/LegalPage.vue'
import { useLocalePath } from '@/composables/useLocalePath'
import { CONTROLLER } from '@/lib/legal'

const localePath = useLocalePath()
</script>

<LegalPage intro="Wie Event Junkie eure Veranstaltungsseite liest, und wie ihr da wieder rauskommt." show-authoritative-version title="Für Locations">

<section>

## Kurz gesagt

Event Junkie sammelt öffentlich angekündigte Veranstaltungen in Berlin und verlinkt jede davon zurück auf eure eigene Seite. Wollt ihr das nicht, schreibt mir eine Mail. Ich schalte die Quelle ab und lösche eure Veranstaltungen. Ich frage nicht nach einem Grund.

</section>

<section>

## Was der Importer von eurer Seite liest

Pro Veranstaltung Titel, Datum, Beginn, Location, Line-up und Art der Veranstaltung, dazu die Links auf eure Seite und auf den Vorverkauf. Außerdem den Beschreibungstext und das Bild, das ihr selbst veröffentlicht.

Das Bild lädt Event Junkie herunter und speichert eine Kopie, damit euer Server es nicht bei jedem Seitenaufruf erneut ausliefern muss. Die Kopie liegt auf den Servern von Event Junkie, nicht bei einem Dritten, und sie wird gelöscht, wenn ihr aussteigt.

</section>

<section>

## Wie der Importer liest

- Einmal am Tag die Übersichtsseite je Quelle, ihre Folgeseiten, wo die Liste weitergeht, und die Detailseiten, auf die sie verlinken.
- Mit mindestens 200 Millisekunden Abstand zwischen zwei Anfragen an denselben Host.
- Mit `ETag` und `Last-Modified`, damit euch eine unveränderte Übersichtsseite nur ein `304` kostet.
- Mit einem User-Agent, der das Projekt nennt und auf seinen Quellcode verlinkt.
- Ohne wildes Crawlen: Jeder Importer kennt genau eine Seitenstruktur.
- Und nach eurer `robots.txt`, die er vor jeder Anfrage prüft.

</section>

<section>

## Wenn ihr nicht dabei sein wollt

1. Ihr schreibt an <a :href="'mailto:' + CONTROLLER.email">{{ CONTROLLER.email }}</a> und nennt die Location.
2. Ich schalte die Quelle ab. Der Importer liest sie danach nicht mehr.
3. Ich lösche die Veranstaltungen dieser Location aus der Datenbank.
4. Ich lösche die gespeicherten Kopien eurer Bilder von den Servern von Event Junkie.
5. Ich antworte innerhalb von sieben Tagen und bestätige euch das.

Ich frage nicht nach einem Grund und diskutiere die Rechtslage nicht mit euch. Eine Location, die raus will, ist raus.

**Es muss aber nicht alles sein.** Wenn euch nur die Bilder stören oder nur die Beschreibungstexte, nehme ich genau das heraus und lasse die Veranstaltungen stehen. Ich lösche das beanstandete Material aus der Datenbank, bei Bildern auch die gespeicherten Kopien, und der Importer übernimmt es nicht erneut. Die Termine bleiben auffindbar. Schreibt mir, was genau ihr nicht gespeichert haben wollt.

</section>

<section>

## Eine `robots.txt` reicht auch

Eine Regel in eurer `robots.txt`, die den Zugriff auf die betreffenden Seiten verbietet, wirkt genauso und braucht keine Nachricht an mich. Der Importer liest die Datei einmal pro Host und Tag und prüft jede Anfrage dagegen. Eine verbotene Adresse ruft er nicht ab. Stattdessen schlägt der Lauf fehl.

</section>

<section>

## Wenn nur etwas nicht stimmt

Für eine falsche Uhrzeit, eine verschobene Show oder eine Veranstaltung, die es nicht mehr gibt, ist der Weg kürzer. Korrigiert es auf eurer eigenen Seite: Der Importer liest jede Location einmal am Tag, und der nächste Durchlauf übernimmt die Änderung. Steht es danach bei Event Junkie immer noch falsch, schreibt mir oder meldet es öffentlich auf [GitHub](https://github.com/enorm-labs/event-junkie/issues).

</section>

<section>

## Für Veranstalter und Künstler\*innen

Der Importer liest Locations, keine Veranstalter. Ein Veranstalter oder eine Künstlerin steht hier, weil eine Location sie genannt hat. Es gibt also keine Quelle, die sich abschalten ließe, aber eine eigene Seite, und für die gilt dasselbe wie für eine Location: Schreibt an <a :href="'mailto:' + CONTROLLER.email">{{ CONTROLLER.email }}</a> und nennt den Namen. Ich korrigiere oder entferne den Namen, den Link zur Website, das Logo oder die Beschreibung, oder ich nehme die Seite ganz heraus. Die Beschreibungstexte sind für diese Seite geschrieben, nicht kopiert, außer bei Bands, Orchestern und Chören: Deren Kurzbeschreibung stammt aus der Wikipedia und ist dort nachgewiesen. Ich frage nicht nach einem Grund und antworte innerhalb von sieben Tagen. Die Veranstaltungen selbst bleiben bei ihrer Location gelistet.

</section>

<section>

## Rechte an Texten und Bildern

Beschreibungen, Bilder und anderes Material von Locations, Veranstaltern und Künstler\*innen bleiben Eigentum der jeweiligen Rechteinhaber\*innen. Wer Rechte an etwas hält, das hier zu sehen ist, und die Entfernung möchte, findet den Weg im <RouterLink :to="localePath('/legal/imprint')">Impressum</RouterLink>. Bei einem Bild lösche ich dabei auch die Kopie, die auf den Servern von Event Junkie liegt.

</section>

</LegalPage>
