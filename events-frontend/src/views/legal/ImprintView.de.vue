<script lang="ts" setup>
/**
 * Impressum nach § 5 DDG — die **maßgebliche Fassung** (LEGAL.md §6.1).
 *
 * Written in German rather than translated from `ImprintView.en.vue`: the headings a German reader
 * expects — *Angaben gemäß § 5 DDG*, *Haftung für Inhalte*, *Haftung für Links* — are fixed
 * conventional wording, and rendering the English ones literally is what makes an imprint read as a
 * machine-translated foreign document.
 *
 * **Register:** `du`, not `Sie`. The site addresses its readers informally throughout (footer
 * disclaimer, BRANDING.md §Voice), and switching on two pages would read as copied boilerplate —
 * the impression a legal page can least afford. Nothing in § 5 DDG or the DSGVO requires a formal
 * register; Art. 12 (1) DSGVO asks for *klare und einfache Sprache*, and `du` is that.
 *
 * **Change both language versions or neither.** Shared facts come from `@/lib/legal`.
 */
import { useI18n } from 'vue-i18n'

import LegalPage from '@/components/LegalPage.vue'
import ProvisionalNotice from '@/components/ProvisionalNotice.vue'
import { CONTROLLER } from '@/lib/legal'

const { t } = useI18n()
</script>

<template>
  <LegalPage
    intro="Angaben gemäß § 5 DDG (früher § 5 TMG)."
    show-authoritative-version
    show-review-date
    title="Impressum"
  >
    <ProvisionalNotice />

    <section>
      <h2>Diensteanbieter</h2>
      <p>{{ CONTROLLER.name }}</p>
      <p>{{ CONTROLLER.careOf }}</p>
      <p>{{ CONTROLLER.street }}</p>
      <p>{{ CONTROLLER.city }}</p>
      <p>{{ t('legal.country') }}</p>
    </section>

    <section>
      <h2>Kontakt</h2>
      <p>
        E-Mail:
        <a :href="`mailto:${CONTROLLER.email}`">{{ CONTROLLER.email }}</a>
      </p>
      <p>
        Für alles, was die Seite selbst betrifft — falsche Event-Daten, ein Fehler, eine Location,
        die wir aufnehmen sollten — erreicht uns ein Issue auf
        <a href="https://github.com/enorm-labs/event-junkie/issues" rel="noopener" target="_blank">
          GitHub
        </a>
        am schnellsten. Es ist außerdem öffentlich, sodass andere sehen können, dass das Problem
        bereits gemeldet ist.
      </p>
    </section>

    <section>
      <!-- § 18 Abs. 2 MStV: Ein Veranstaltungskalender, der fremde Inhalte auswählt und aufbereitet,
           kann unter den journalistisch-redaktionellen Test fallen, der strenger ist als § 5 DDG.
           Die verantwortliche Person zu nennen kostet eine Zeile und erledigt die Frage. -->
      <h2>Redaktionell verantwortlich</h2>
      <p>Gemäß § 18 Abs. 2 MStV: {{ CONTROLLER.name }}, Anschrift wie oben.</p>
    </section>

    <section>
      <h2>Nicht einschlägig</h2>
      <p>
        Event Junkie wird von einer Privatperson betrieben, nicht von einem Unternehmen: Es gibt
        keinen Handelsregistereintrag, keine Umsatzsteuer-Identifikationsnummer nach § 27a UStG,
        keine Aufsichtsbehörde und keine reglementierte Berufsbezeichnung. Über diese Seite werden
        keine Verbraucherverträge geschlossen, sodass weder die EU-Plattform zur
        Online-Streitbeilegung noch § 36 VSBG einschlägig sind. Zur Teilnahme an einem
        Streitbeilegungsverfahren vor einer Verbraucherschlichtungsstelle sind wir weder
        verpflichtet noch bereit.
      </p>
    </section>

    <section>
      <!-- „Alle Angaben ohne Gewähr" im förmlichen Register (§7.6). Die Fußzeile trägt die
           umgangssprachliche Fassung, die tatsächlich gelesen wird. -->
      <h2>Haftung für Inhalte</h2>
      <p>
        Die Veranstaltungsdaten auf dieser Seite werden automatisiert aus öffentlich zugänglichen
        Quellen zusammengetragen. Alle Angaben erfolgen ohne Gewähr für Richtigkeit, Vollständigkeit
        und Aktualität. Veranstaltungen können verlegt, ausverkauft oder abgesagt werden, nachdem
        wir die Quelle zuletzt gelesen haben. Prüfe Details deshalb immer bei der Location oder beim
        offiziellen Ticketanbieter, bevor du losziehst. Für Entscheidungen, die auf Grundlage der
        hier gezeigten Informationen getroffen werden, wird keine Haftung übernommen.
      </p>
    </section>

    <section>
      <h2>Haftung für Links</h2>
      <p>
        Diese Seite verlinkt auf externe Websites, auf deren Inhalte wir keinen Einfluss haben. Für
        diese Inhalte ist der jeweilige Anbieter verantwortlich. Die verlinkten Seiten wurden zum
        Zeitpunkt der Verlinkung auf Rechtsverstöße geprüft; solche waren nicht erkennbar. Eine
        permanente inhaltliche Kontrolle ohne konkrete Anhaltspunkte für eine Rechtsverletzung ist
        nicht zumutbar. Sobald uns eine Rechtsverletzung bekannt wird, entfernen wir den
        entsprechenden Link umgehend.
      </p>
    </section>

    <section>
      <h2>Urheberrecht</h2>
      <p>
        Der Quellcode dieses Projekts steht unter der Apache License 2.0 — siehe den
        <a
          href="https://github.com/enorm-labs/event-junkie/blob/main/LICENSE"
          rel="noopener"
          target="_blank"
        >
          Lizenztext </a
        >. Veranstaltungsbeschreibungen, Bilder und sonstige Materialien, die von Locations,
        Veranstaltern und Künstlerinnen und Künstlern stammen, bleiben Eigentum der jeweiligen
        Rechteinhaber und werden von dieser Lizenz nicht erfasst. Wer Rechte an hier gezeigtem
        Material hält und dessen Entfernung wünscht, schreibt uns — wir kümmern uns umgehend darum.
      </p>
    </section>
  </LegalPage>
</template>
