<script lang="ts" setup>
/**
 * Open-Source-Lizenzhinweise — deutsche Fassung von `NoticesView.en.vue`.
 *
 * Die Daten stammen aus `src/assets/notices.json` (erzeugt von `npm run generate:notices`, siehe
 * scripts/generate-notices.mjs). Nach Lizenz gruppiert statt flach aufgelistet: 642 alphabetische
 * Zeilen sind eine Wand, „diese 400 sind MIT“ dagegen eine Aussage, mit der man etwas anfangen
 * kann.
 *
 * Die Aufklapp-Logik und die Koordinaten-Formatierung liegen in `useNotices`, damit beide
 * Sprachfassungen dieselbe Implementierung teilen; unterschiedlich ist nur der Text.
 */
import LegalPage from '@/components/LegalPage.vue'
import { useNotices } from '@/composables/useNotices'

const { componentCount, groups, openGroups, toggle, versionSuffix } = useNotices()
</script>

<template>
  <LegalPage
    :intro="`Event Junkie baut auf ${componentCount} Open-Source-Komponenten auf. Danke an alle, die sie geschrieben haben.`"
    title="Open-Source-Lizenzen"
  >
    <section>
      <h2>Was hier aufgelistet ist</h2>
      <p>
        Sämtliche Abhängigkeiten dieses Projekts — die Laufzeitbibliotheken des Backends und die
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
        <code>NOTICE</code>-Datei wieder — beides liegt den Paketen selbst bei, unter den
        Quell-Links weiter unten.
      </p>
    </section>

    <section>
      <h2>Unser eigener Code</h2>
      <p>
        Der Quellcode von Event Junkie steht unter der Apache License 2.0 und ist vollständig
        verfügbar unter
        <a href="https://github.com/enorm-labs/event-junkie" rel="noopener" target="_blank">
          github.com/enorm-labs/event-junkie </a
        >.
      </p>
    </section>

    <section>
      <h2>Komponenten nach Lizenz</h2>
      <details
        v-for="group in groups"
        :key="group.license"
        class="rounded-lg border border-border p-4"
        @toggle="toggle(group.license, ($event.target as HTMLDetailsElement).open)"
      >
        <summary class="cursor-pointer font-medium text-foreground">
          {{ group.license }}
          <span class="font-normal text-muted-foreground">
            — {{ group.components.length }}
            {{ group.components.length === 1 ? 'Komponente' : 'Komponenten' }}
          </span>
        </summary>

        <p v-if="group.url" class="mt-2 text-sm">
          <a :href="group.url" rel="noopener" target="_blank"
            >Lizenztext {{ group.license }} lesen</a
          >
        </p>

        <!-- Nur im aufgeklappten Zustand gerendert: alle Zeilen vorab zu mounten sind ~640
             Listenelemente und ein sichtbarer Ruckler beim ersten Aufbau dieser Route. -->
        <ul v-if="openGroups.has(group.license)" class="mt-3 text-sm">
          <li v-for="component in group.components" :key="`${component.name}@${component.version}`">
            <a v-if="component.url" :href="component.url" rel="noopener" target="_blank">{{
              component.name
            }}</a>
            <span v-else>{{ component.name }}</span
            ><span class="text-muted-foreground">{{ versionSuffix(component) }}</span>
          </li>
        </ul>
      </details>
    </section>
  </LegalPage>
</template>
