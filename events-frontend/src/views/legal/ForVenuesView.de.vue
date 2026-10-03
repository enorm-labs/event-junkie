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
 */
import { RouterLink } from 'vue-router'

import LegalPage from '@/components/LegalPage.vue'
import { useLocalePath } from '@/composables/useLocalePath'
import { CONTROLLER } from '@/lib/legal'

const localePath = useLocalePath()
</script>

<template>
  <LegalPage
    intro="Wie Event Junkie eure Veranstaltungsseite liest, und wie ihr da wieder rauskommt."
    show-authoritative-version
    title="Für Locations"
  >
    <section>
      <h2>Kurz gesagt</h2>
      <p>
        Event Junkie sammelt öffentlich angekündigte Veranstaltungen in Berlin und verlinkt jede
        davon zurück auf eure eigene Seite. Wollt ihr das nicht, schreibt mir eine Mail. Ich schalte
        die Quelle ab und lösche eure Veranstaltungen. Ich frage nicht nach einem Grund.
      </p>
    </section>

    <section>
      <h2>Was der Importer von eurer Seite liest</h2>
      <p>
        Pro Veranstaltung Titel, Datum, Beginn, Location, Line-up und Art der Veranstaltung, dazu
        die Links auf eure Seite und auf den Vorverkauf. Außerdem den Beschreibungstext und das
        Bild, das ihr selbst veröffentlicht.
      </p>
      <p>
        Das Bild lädt Event Junkie herunter und speichert eine Kopie, damit euer Server es nicht bei
        jedem Seitenaufruf erneut ausliefern muss. Die Kopie liegt auf den Servern von Event Junkie,
        nicht bei einem Dritten, und sie wird gelöscht, wenn ihr aussteigt.
      </p>
    </section>

    <section>
      <h2>Wie der Importer liest</h2>
      <ul>
        <li>
          Einmal am Tag die Übersichtsseite je Quelle, ihre Folgeseiten, wo die Liste weitergeht,
          und die Detailseiten, auf die sie verlinken.
        </li>
        <li>Mit mindestens 200 Millisekunden Abstand zwischen zwei Anfragen an denselben Host.</li>
        <li>
          Mit <code>ETag</code> und <code>Last-Modified</code>, damit euch eine unveränderte
          Übersichtsseite nur ein <code>304</code> kostet.
        </li>
        <li>Mit einem User-Agent, der das Projekt nennt und auf seinen Quellcode verlinkt.</li>
        <li>Ohne wildes Crawlen: Jeder Importer kennt genau eine Seitenstruktur.</li>
        <li>Und nach eurer <code>robots.txt</code>, die er vor jeder Anfrage prüft.</li>
      </ul>
    </section>

    <section>
      <h2>Wenn ihr nicht dabei sein wollt</h2>
      <ol>
        <li>
          Ihr schreibt an
          <a :href="`mailto:${CONTROLLER.email}`">{{ CONTROLLER.email }}</a>
          und nennt die Location.
        </li>
        <li>Ich schalte die Quelle ab. Der Importer liest sie danach nicht mehr.</li>
        <li>Ich lösche die Veranstaltungen dieser Location aus der Datenbank.</li>
        <li>Ich lösche die gespeicherten Kopien eurer Bilder von den Servern von Event Junkie.</li>
        <li>Ich antworte innerhalb von sieben Tagen und bestätige euch das.</li>
      </ol>
      <p>
        Ich frage nicht nach einem Grund und diskutiere die Rechtslage nicht mit euch. Eine
        Location, die raus will, ist raus.
      </p>
      <p>
        <strong>Es muss aber nicht alles sein.</strong> Wenn euch nur die Bilder stören oder nur die
        Beschreibungstexte, nehme ich genau das heraus und lasse die Veranstaltungen stehen. Ich
        lösche das beanstandete Material aus der Datenbank, bei Bildern auch die gespeicherten
        Kopien, und der Importer übernimmt es nicht erneut. Die Termine bleiben auffindbar. Schreibt
        mir, was genau ihr nicht gespeichert haben wollt.
      </p>
    </section>

    <section>
      <h2>Eine <code>robots.txt</code> reicht auch</h2>
      <p>
        Eine Regel in eurer <code>robots.txt</code>, die den Zugriff auf die betreffenden Seiten
        verbietet, wirkt genauso und braucht keine Nachricht an mich. Der Importer liest die Datei
        einmal pro Host und Tag und prüft jede Anfrage dagegen. Eine verbotene Adresse ruft er nicht
        ab. Stattdessen schlägt der Lauf fehl.
      </p>
    </section>

    <section>
      <h2>Wenn nur etwas nicht stimmt</h2>
      <p>
        Für eine falsche Uhrzeit, eine verschobene Show oder eine Veranstaltung, die es nicht mehr
        gibt, ist der Weg kürzer. Korrigiert es auf eurer eigenen Seite: Der Importer liest jede
        Location einmal am Tag, und der nächste Durchlauf übernimmt die Änderung. Steht es danach
        bei Event Junkie immer noch falsch, schreibt mir oder meldet es öffentlich auf
        <a href="https://github.com/enorm-labs/event-junkie/issues" rel="noopener" target="_blank">
          GitHub</a
        >.
      </p>
    </section>

    <section>
      <h2>Für Veranstalter und Künstler*innen</h2>
      <p>
        Der Importer liest Locations, keine Veranstalter. Ein Veranstalter oder eine Künstlerin
        steht hier, weil eine Location sie genannt hat. Es gibt also keine Quelle, die sich
        abschalten ließe, aber eine eigene Seite, und für die gilt dasselbe wie für eine Location:
        Schreibt an
        <a :href="`mailto:${CONTROLLER.email}`">{{ CONTROLLER.email }}</a>
        und nennt den Namen. Ich korrigiere oder entferne den Namen, den Link zur Website, das Logo
        oder die Beschreibung, oder ich nehme die Seite ganz heraus. Die Beschreibungstexte sind für
        diese Seite geschrieben, nicht kopiert, außer bei Bands, Orchestern und Chören: Deren
        Kurzbeschreibung stammt aus der Wikipedia und ist dort nachgewiesen. Ich frage nicht nach
        einem Grund und antworte innerhalb von sieben Tagen. Die Veranstaltungen selbst bleiben bei
        ihrer Location gelistet.
      </p>
    </section>

    <section>
      <h2>Rechte an Texten und Bildern</h2>
      <p>
        Beschreibungen, Bilder und anderes Material von Locations, Veranstaltern und Künstler*innen
        bleiben Eigentum der jeweiligen Rechteinhaber*innen. Wer Rechte an etwas hält, das hier zu
        sehen ist, und die Entfernung möchte, findet den Weg im
        <RouterLink :to="localePath('/legal/imprint')">Impressum</RouterLink>. Bei einem Bild lösche
        ich dabei auch die Kopie, die auf den Servern von Event Junkie liegt.
      </p>
    </section>
  </LegalPage>
</template>
