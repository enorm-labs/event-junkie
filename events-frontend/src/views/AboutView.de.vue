<script lang="ts" setup>
/**
 * „Über uns" — deutsche Fassung von `AboutView.en.vue`.
 *
 * Written rather than translated. The beta section is brand voice, not neutral prose
 * (BRANDING.md §8), and a literal rendering of *"still finding its feet"* or
 * *"the nights worth showing up for"* reads as a machine translation of an English site — which
 * is the one thing the page is there to disprove.
 */
import { RouterLink } from 'vue-router'
import { useLocalePath } from '@/composables/useLocalePath'

const localePath = useLocalePath()
</script>

<template>
  <main class="mx-auto max-w-3xl space-y-6 p-4 sm:p-8">
    <h1 class="text-3xl font-bold tracking-tight">Über uns</h1>
    <p class="text-muted-foreground">
      Event Junkie ist dein Überblick über das, was in Berlins Locations läuft — ein einziger, immer
      frischer Feed mit Live-Events, von Konzerten und Clubnächten über Festivals und Partys bis zum
      gelegentlichen Kneipenquiz.
    </p>
    <p class="text-muted-foreground">
      Verschaff dir einen Überblick, filtere nach Genre oder Art, um deine Szene zu finden, und
      öffne den Kalender, um deine Woche zu planen. Jedes Event führt weiter zu seiner Location, zum
      Line-up und zu den Künstlerinnen und Künstlern — damit du in die Details gehen kannst und die
      Nächte nicht verpasst, bei denen es sich lohnt aufzutauchen.
    </p>
    <p class="text-muted-foreground">
      Im Fokus steht Musik in jeder Ausprägung — große Bühnen und kleine Hinterzimmer gleichermaßen,
      nicht nur Techno — überall in Berlin.
    </p>

    <section class="space-y-4 pt-4">
      <h2 class="text-2xl font-bold tracking-tight">Was du hier findest</h2>
      <p class="text-muted-foreground">
        Die Regel ist einfach:
        <strong class="font-medium text-foreground"
          >Was in Berlin abends auf einer Bühne stattfindet, gehört hierher.</strong
        >
        Nicht nur Livemusik, und ganz sicher nicht nur Techno. Den größten Teil machen Konzerte und
        Clubnächte aus, dazu Partys, Festivals, Bühnenshows und Kabarett, Lesungen und Spoken Word,
        Filmvorführungen und Open-Air-Kino, Ausstellungseröffnungen und ab und zu ein Kneipenquiz.
      </p>
      <p class="text-muted-foreground">
        Wenn eine Location selbst sagt, was für ein Abend das ist, übernehmen wir das, statt es
        besser wissen zu wollen. Punk, Jazz, Indie, Metal, Klassik-Crossover, Drag und
        Singer-Songwriter-Abende stehen im selben Feed wie die Clubtermine — genau darum geht es.
      </p>
      <p class="text-muted-foreground">
        <strong class="font-medium text-foreground">Ein paar Dinge fehlen mit Absicht.</strong>
        Sport, auch in den Arenen, in denen ebenso Konzerte stattfinden — ein Basketballspiel ist
        nicht, wofür du hergekommen bist, und mit drin begräbt es die Konzerte. Führungen, Workshops
        und Yoga-Stunden: Sachen zum Mitmachen, nicht zum Hingehen und Zuschauen. Messen und
        Kongresse. Und, vorerst, klassische Konzerte und Orchester — nicht, weil sie nicht
        dazugehören, sondern weil ein Orchester mit Dirigentin und Solisten nicht in ein Modell
        passt, das um Headliner und Support herum gebaut ist. Das ist ein „noch nicht" und steht als
        offene Frage fest, statt still unter den Tisch zu fallen.
      </p>
    </section>

    <section class="space-y-4 pt-4">
      <h2 class="text-2xl font-bold tracking-tight">Warum es das gibt</h2>
      <p class="text-muted-foreground">
        Weil ich
        <strong class="font-medium text-foreground"
          >die Event-App bauen wollte, die Berlin verdient.</strong
        >
      </p>
      <p class="text-muted-foreground">
        Berlins Szene ist riesig und völlig verstreut. Was läuft, steht auf Dutzenden Websites von
        Locations und Veranstaltern, jede mit eigenem Layout und eigenen Lücken. Eine so gewöhnliche
        Frage wie
        <em
          >was läuft dieses Wochenende in meiner Nähe, in meinem Genre, das ich mir leisten
          kann?</em
        >
        bedeutet ein Dutzend Browser-Tabs und viel Raten. Die Informationen existieren — sie stehen
        nur nirgends so, dass man sie auf einmal ansehen könnte.
      </p>
      <p class="text-muted-foreground">
        Die vorhandenen Angebote lösen jeweils ein Stück davon.
        <strong class="font-medium text-foreground">Resident Advisor</strong> ist wirklich gut in
        dem, was es macht — aber was es macht, ist elektronische Musik, und ein Abend in einer
        Punkkneipe in Friedrichshain oder in einem Kabarettsaal in Wilmersdorf ist nicht seine Welt.
        <strong class="font-medium text-foreground">Bandsintown und Songkick</strong> folgen
        <em>Künstlerinnen und Künstlern</em>: großartig, wenn du schon weißt, wen du sehen willst,
        keine Hilfe, wenn du wissen willst, was am Donnerstag los ist. Ticketportale listen, was sie
        verkaufen — und lassen damit freien Eintritt, Abendkasse-Abende und die kleinen Räume außen
        vor, die noch nie ein Ticket online verkauft haben.
      </p>
      <p class="text-muted-foreground">
        Die Lücke ist also keine bessere Version von einem davon. Sie ist das, was keines davon sein
        will: <strong class="font-medium text-foreground">ein Feed für alles</strong> — jede Art von
        Location, jedes Genre, umsonst und mit Ticket gleichermaßen, filterbar nach dem, worüber du
        wirklich entscheidest (heute Abend, in der Nähe, mein Genre, unter 15 €), und immer mit dem
        Link zurück auf die Seite der Location für Tickets und das letzte Wort.
      </p>
    </section>

    <section class="space-y-4 pt-4">
      <h2 class="text-2xl font-bold tracking-tight">Und zum Lernen</h2>
      <p class="text-muted-foreground">
        Der andere ehrliche Grund: Ich habe das gebaut, um zu lernen — vor allem über
        <strong class="font-medium text-foreground">KI-gestützte Entwicklung</strong>. Der größte
        Teil des Codes hier stammt von KI-Agenten (hauptsächlich
        <a
          class="text-foreground underline underline-offset-4"
          href="https://claude.com/claude-code"
          rel="noopener"
          target="_blank"
        >
          Claude Code
        </a>
        ), die mit Konventionen und Prompts arbeiten, die im Repository liegen. Die Vision, die
        Produktentscheidungen, die Architektur und die Prioritäten sind meine; die Agenten setzen
        dagegen um, und jede Änderung wird geprüft, bevor sie landet.
      </p>
      <p class="text-muted-foreground">
        Das ist kein Kleingedrucktes, sondern der spannende Teil. Ein echtes Projekt mit echten
        Nutzerinnen und Nutzern und echten Zwängen ist der einzige Weg herauszufinden, worin diese
        Arbeitsweise wirklich gut ist und wo es weiter einen Menschen braucht, der hinschaut. Das
        Ganze ist
        <a
          class="text-foreground underline underline-offset-4"
          href="https://github.com/enorm-labs/event-junkie"
          rel="noopener"
          target="_blank"
        >
          Open Source
        </a>
        , Konventionen und Prompts inklusive, falls du sehen willst, wie es gemacht wurde.
      </p>
    </section>

    <!-- Das Beta-Abzeichen im Header verlinkt hierher. `scroll-mt` hält die Überschrift frei vom
         Header, wenn der Anker angesprungen wird. Die id muss `beta` bleiben: sie ist in beiden
         Sprachfassungen dasselbe Ziel. -->
    <section id="beta" class="scroll-mt-8 space-y-4 pt-4">
      <h2 class="text-2xl font-bold tracking-tight">Warum da beta steht</h2>
      <p class="text-muted-foreground">
        Event Junkie ist noch jung. Es funktioniert, wir nutzen es selbst, und es ist wirklich
        nützlich — aber es sortiert sich noch, und das solltest du wissen, bevor du einen Abend
        darauf aufbaust.
      </p>
      <p class="text-muted-foreground">
        <strong class="font-medium text-foreground">Die Abdeckung ist unvollständig.</strong> Neue
        Locations kommen nach und nach dazu; ein ruhiger Abend auf Event Junkie ist also nicht immer
        ein ruhiger Abend in Berlin.
        <strong class="font-medium text-foreground"
          >Details können falsch oder veraltet sein.</strong
        >
        Events werden automatisch von den Websites der Locations gelesen; wenn eine Show verlegt
        wird, ausverkauft ist oder ausfällt, erfahren wir das beim nächsten Durchlauf, nicht sofort.
        Frag im Zweifel bei der Location nach, bevor du losziehst.
        <strong class="font-medium text-foreground">Dinge ändern sich ohne Ankündigung</strong> —
        Seiten, Filter und die Daten dahinter sind alle noch in Bewegung.
      </p>
      <p class="text-muted-foreground">
        Was beta <em>nicht</em> heißt: Nichts hier ist eine Testphase, für die du später zur Kasse
        gebeten wirst, es werden keine Daten über dich verkauft oder weitergegeben, und nichts
        trackt dich — siehe die
        <RouterLink
          :to="localePath('/legal/privacy')"
          class="text-foreground underline underline-offset-4"
        >
          Datenschutzseite
        </RouterLink>
        , die genau deshalb kurz ist.
      </p>
      <p class="text-muted-foreground">
        Etwas gefunden, das nicht stimmt? Uns Bescheid zu geben ist der schnellste Weg zur Korrektur
        —
        <a
          class="text-foreground underline underline-offset-4"
          href="https://github.com/enorm-labs/event-junkie/issues/new/choose"
          rel="noopener"
          target="_blank"
        >
          Issue eröffnen
        </a>
        . Was sich zuletzt geändert hat, steht auf der
        <a
          class="text-foreground underline underline-offset-4"
          href="https://github.com/enorm-labs/event-junkie/releases"
          rel="noopener"
          target="_blank"
        >
          Releases-Seite
        </a>
        .
      </p>
    </section>
  </main>
</template>
