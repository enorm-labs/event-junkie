<script lang="ts" setup>
/**
 * Open-Source-Lizenzhinweise — deutsche Fassung von `NoticesView.en.vue`.
 *
 * Die Daten stammen aus `src/assets/notices.json` (erzeugt von `npm run generate:notices`, siehe
 * scripts/generate-notices.mjs). Nach Lizenz gruppiert statt flach aufgelistet: Hunderte
 * alphabetische Zeilen sind eine Wand, „das hier sind die MIT-Komponenten“ dagegen eine Aussage,
 * mit der man etwas anfangen kann.
 *
 * Die Lizenzliste liegt in `NoticesLicenceGroups`, ihr Verhalten in `useNotices`, damit beide
 * Sprachfassungen dieselbe Implementierung teilen; unterschiedlich ist nur der Text.
 */
import LegalPage from '@/components/LegalPage.vue'
import NoticesLicenceGroups from '@/components/NoticesLicenceGroups.vue'
import { useNotices } from '@/composables/useNotices'

const { componentCount } = useNotices()
</script>

<template>
  <LegalPage
    :intro="`Event Junkie baut auf ${componentCount} Open-Source-Komponenten auf. Danke an alle, die sie geschrieben haben.`"
    title="Open-Source-Lizenzen"
  >
    <section>
      <h2>Was hier aufgelistet ist</h2>
      <p>
        Sämtliche Abhängigkeiten dieses Projekts: die Laufzeitbibliotheken des Backends und die
        Produktionspakete des Frontends, jeweils samt ihrer eigenen transitiven Abhängigkeiten. Die
        Liste wird aus dem Build erzeugt und nicht von Hand gepflegt; sie bleibt also korrekt, wenn
        sich Abhängigkeiten ändern.
      </p>
      <p>
        Zwei Einschränkungen, damit die Liste nicht für mehr gehalten wird, als sie ist. Sie
        erfasst,
        <em>wovon</em> das Projekt abhängt, und das ist mehr als das, was an deinen Browser
        ausgeliefert wird: Beim Bundling fällt ein guter Teil davon weg. Und sie nennt die Lizenz
        jeder Komponente, gibt aber weder den vollständigen Lizenztext noch eine mitgelieferte
        <code>NOTICE</code>-Datei wieder. Beides liegt den Paketen selbst bei, unter den Quell-Links
        weiter unten.
      </p>
    </section>

    <section>
      <h2>Der eigene Code von Event Junkie</h2>
      <p>
        Der Quellcode von Event Junkie steht unter der Business Source License 1.1 und ist
        vollständig verfügbar unter
        <a href="https://github.com/enorm-labs/event-junkie" rel="noopener" target="_blank">
          github.com/enorm-labs/event-junkie</a
        >.
      </p>
    </section>

    <section>
      <h2>Wetterdaten</h2>
      <p>
        Die Vorhersage bei Open-Air-Veranstaltungen stammt aus den offenen Daten des Deutschen
        Wetterdienstes: der MOSMIX-Vorhersage für die Station Berlin-Tempelhof. Unser Server ruft
        sie ab, dein Browser verbindet sich also nie mit dem DWD.
      </p>
      <p>
        Datenbasis:
        <a
          href="https://www.dwd.de/DE/service/copyright/copyright_node.html"
          rel="noopener"
          target="_blank"
          >Deutscher Wetterdienst</a
        >
      </p>
    </section>

    <section>
      <h2>Daten</h2>
      <p data-testid="stop-data-attribution">
        Haltestellendaten:
        <a
          href="https://unternehmen.vbb.de/digitale-services/datensaetze/"
          rel="noopener"
          target="_blank"
          >VBB Verkehrsverbund Berlin-Brandenburg GmbH</a
        >,
        <a href="https://creativecommons.org/licenses/by/4.0/deed.de" rel="noopener" target="_blank"
          >CC BY 4.0</a
        >, Auszug. Daraus stammen die Haltestellen auf den Seiten der Locations und Veranstaltungen.
      </p>
    </section>

    <section>
      <h2>Komponenten nach Lizenz</h2>
      <NoticesLicenceGroups />
    </section>
  </LegalPage>
</template>
