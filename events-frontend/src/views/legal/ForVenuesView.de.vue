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
    intro="Wie Event Junkie eure Veranstaltungsseite liest — und wie ihr da wieder rauskommt."
    show-authoritative-version
    title="Für Locations"
  >
    <section>
      <h2>Kurz gesagt</h2>
      <p>
        Event Junkie sammelt öffentlich angekündigte Veranstaltungen in Berlin und verlinkt jede
        davon zurück auf eure eigene Seite. Wollt ihr das nicht, schreibt uns eine Mail: wir
        schalten die Quelle ab und löschen eure Veranstaltungen. Wir fragen nicht nach einem Grund.
      </p>
    </section>

    <section>
      <h2>Was wir von eurer Seite lesen</h2>
      <p>
        Pro Veranstaltung Titel, Datum, Beginn, Location, Line-up und Art der Veranstaltung, dazu
        die Links auf eure Seite und auf den Vorverkauf. Außerdem den Beschreibungstext und das
        Bild, das ihr selbst veröffentlicht.
      </p>
      <p>
        Das Bild bleibt auf eurem Server. Wir kopieren es nicht, sondern binden es ein — der Browser
        unserer Besucher holt es direkt bei euch.
      </p>
    </section>

    <section>
      <h2>Wie wir lesen</h2>
      <ul>
        <li>Einmal am Tag, eine Übersichtsseite je Quelle, dazu deren Detailseiten.</li>
        <li>Mit mindestens 200 Millisekunden Abstand zwischen zwei Anfragen an denselben Host.</li>
        <li>
          Mit <code>ETag</code> und <code>Last-Modified</code>, damit eine unveränderte Seite uns
          nur ein <code>304</code> kostet.
        </li>
        <li>Mit einem User-Agent, der das Projekt nennt und auf seinen Quellcode verlinkt.</li>
        <li>Ohne wildes Crawlen: Jeder Importer kennt genau eine Seitenstruktur.</li>
        <li>Und nach eurer <code>robots.txt</code>, die wir vor jeder Anfrage prüfen.</li>
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
        <li>Wir schalten die Quelle ab. Der Importer liest sie danach nicht mehr.</li>
        <li>Wir löschen die Veranstaltungen dieser Location aus der Datenbank.</li>
        <li>Wir antworten innerhalb von sieben Tagen und bestätigen euch das.</li>
      </ol>
      <p>
        Wir fragen nicht nach einem Grund und diskutieren die Rechtslage nicht mit euch. Eine
        Location, die raus will, ist raus.
      </p>
      <p>
        <strong>Es muss aber nicht alles sein.</strong> Wenn euch nur die Bilder stören oder nur die
        Beschreibungstexte, nehmen wir genau das heraus und lassen die Veranstaltungen stehen. Die
        Termine bleiben dann auffindbar, das beanstandete Material verschwindet. Schreibt uns, was
        genau ihr nicht angezeigt haben wollt.
      </p>
    </section>

    <section>
      <h2>Eine <code>robots.txt</code> reicht auch</h2>
      <p>
        Eine Regel in eurer <code>robots.txt</code>, die den Zugriff auf die betreffenden Seiten
        verbietet, wirkt genauso und braucht keine Nachricht an uns. Wir lesen die Datei einmal pro
        Host und Tag und prüfen jede Anfrage dagegen. Eine verbotene Adresse holen wir nicht: der
        Lauf schlägt stattdessen fehl.
      </p>
    </section>

    <section>
      <h2>Wenn nur etwas nicht stimmt</h2>
      <p>
        Für eine falsche Uhrzeit, eine verschobene Show oder eine Veranstaltung, die es nicht mehr
        gibt, ist der Weg kürzer. Schreibt uns, oder meldet es öffentlich auf
        <a href="https://github.com/enorm-labs/event-junkie/issues" rel="noopener" target="_blank">
          GitHub </a
        >. Beides ist schneller als unser nächster Durchlauf.
      </p>
    </section>

    <section>
      <h2>Rechte an Texten und Bildern</h2>
      <p>
        Beschreibungen, Bilder und anderes Material von Locations, Veranstaltern und Künstlerinnen
        bleiben Eigentum der jeweiligen Rechteinhaber. Wer Rechte an etwas hält, das hier zu sehen
        ist, und die Entfernung möchte, findet den Weg im
        <RouterLink :to="localePath('/legal/imprint')">Impressum</RouterLink>.
      </p>
    </section>
  </LegalPage>
</template>
