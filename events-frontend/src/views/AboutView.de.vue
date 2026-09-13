<script lang="ts" setup>
/**
 * „Über uns“ — deutsche Fassung von `AboutView.en.vue`.
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
      Event Junkie ist dein Überblick über das, was in Berlins Locations läuft: ein Feed mit
      Live-Events, von Konzerten und Clubnächten über Festivals und Partys bis zum gelegentlichen
      Kneipenquiz.
    </p>
    <p class="text-muted-foreground">
      Verschaff dir einen Überblick, filtere nach Genre oder Art, um deine Szene zu finden, und
      öffne den Kalender, um deine Woche zu planen. Jedes Event führt weiter zu seiner Location, zum
      Line-up und zu den Künstler*innen, damit du in die Details gehen kannst und die Nächte
      erwischst, bei denen es sich lohnt aufzutauchen.
    </p>
    <p class="text-muted-foreground">
      Im Fokus steht Musik in jeder Ausprägung, auf großen Bühnen und in kleinen Hinterzimmern,
      überall in Berlin.
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
        Wenn eine Location selbst sagt, was für ein Abend das ist, übernehmen wir das. Punk, Jazz,
        Indie, Metal, Klassik-Crossover, Drag und Singer-Songwriter-Abende stehen im selben Feed wie
        die Clubtermine.
      </p>
      <p class="text-muted-foreground">
        <strong class="font-medium text-foreground">Ein paar Dinge fehlen mit Absicht.</strong>
        Sport, auch in den Arenen, in denen ebenso Konzerte stattfinden: Ein Basketballspiel ist
        nicht der Grund, warum du hier bist, und sobald Sport mit drin ist, gehen die Konzerte darin
        unter. Führungen, Workshops und Yoga-Stunden, also Sachen zum Mitmachen statt zum Zuschauen.
        Messen und Kongresse. Und, vorerst, klassische Konzerte und Orchester: Ein Orchester mit
        Dirigent*in und Solist*innen passt nicht in ein Modell, das um Headliner und Support herum
        gebaut ist. Das ist ein „noch nicht“ und als offene Frage festgehalten.
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
        Angefangen hat das lange vor dem Code. Ich war jahrelang auf Indie-Partys und Konzerten
        unterwegs und habe dafür jede Woche die Seiten meiner Lieblingsclubs von Hand abgeklappert,
        eine nach der anderen. Resident Advisor kannte ich damals noch nicht, und ob dort eine
        Indie-Party in einem kleinen Club überhaupt aufgetaucht wäre, ist eine andere Frage.
      </p>
      <p class="text-muted-foreground">
        Berlins Szene ist riesig und verstreut. Was läuft, steht auf Dutzenden Websites von
        Locations und Veranstaltern, jede mit eigenem Layout und eigenen Lücken. Eine so gewöhnliche
        Frage wie
        <em
          >was läuft dieses Wochenende in meiner Nähe, in meinem Genre, das ich mir leisten
          kann?</em
        >
        bedeutet ein Dutzend Browser-Tabs und viel Raten. Die Informationen sind alle da. Sie stehen
        nur nirgends an einer Stelle.
      </p>
      <p class="text-muted-foreground">
        Die vorhandenen Angebote lösen jeweils ein Stück davon.
        <strong class="font-medium text-foreground">Resident Advisor</strong> ist sehr gut in
        elektronischer Musik, und ein Abend in einer Punkkneipe in Friedrichshain oder in einem
        Kabarettsaal in Wilmersdorf liegt außerhalb seiner Welt.
        <strong class="font-medium text-foreground">Bandsintown und Songkick</strong> folgen
        <em>Künstler*innen</em>, was hilft, wenn du schon weißt, wen du sehen willst, und gar nicht
        hilft, wenn du wissen willst, was am Donnerstag los ist. Ticketportale listen, was sie
        verkaufen, und lassen damit freien Eintritt, Abendkasse-Abende und die kleinen Räume außen
        vor, die noch nie ein Ticket online verkauft haben.
      </p>
      <p class="text-muted-foreground">
        Die Lücke ist das, was keines davon sein will:
        <strong class="font-medium text-foreground">ein Feed für alles</strong>, jede Art von
        Location und jedes Genre, umsonst und mit Ticket gleichermaßen. Du filterst nach dem,
        worüber du ohnehin entscheidest (heute Abend, in der Nähe, mein Genre, unter 15 €), und
        jeder Eintrag verlinkt zurück auf die Seite der Location für Tickets und das letzte Wort.
      </p>
      <p class="text-muted-foreground">
        Bauen wollte ich das seit Jahren. Angefangen habe ich mehrfach, und als Hobbyprojekt neben
        allem anderen war es jedes Mal schlicht zu viel Arbeit für eine Person. Mit KI-Agenten geht
        es jetzt.
      </p>
    </section>

    <section class="space-y-4 pt-4">
      <h2 class="text-2xl font-bold tracking-tight">Warum Berlin</h2>
      <p class="text-muted-foreground">
        Berlin ist eine der besten Städte auf diesem Planeten. Nicht immer sauber, ziemlich
        verrückt, arm, aber sexy. Vor allem ist es ein Ort, an dem man frei leben und der Mensch
        sein kann, der man ist. Diese Stadt ist divers und bunt, und abends hört das nicht auf,
        sondern fängt erst richtig an.
      </p>
      <p class="text-muted-foreground">
        Die Clubkultur, die daraus entstanden ist, gibt es so kein zweites Mal. Seit 2024 steht die
        <a
          class="text-foreground underline underline-offset-4"
          href="https://www.unesco.de/staette/technokultur-in-berlin/"
          rel="noopener"
          target="_blank"
        >
          Technokultur in Berlin
        </a>
        im bundesweiten Verzeichnis des immateriellen Kulturerbes. Dort stehen keine Gebäude,
        sondern lebendige Praktiken. Lebendig heißt auch: Sie können verschwinden.
      </p>
      <p class="text-muted-foreground">
        Genau das passiert gerade. Die
        <a
          class="text-foreground underline underline-offset-4"
          href="https://www.clubcommission.de/"
          rel="noopener"
          target="_blank"
        >
          Clubcommission</a
        >, der Verband der Berliner Clubkultur, nennt es das
        <a
          class="text-foreground underline underline-offset-4"
          href="https://www.clubcommission.de/pressemitteilung-clubsterben-ist-wieder-an-der-tagesordnung/"
          rel="noopener"
          target="_blank"
        >
          Clubsterben</a
        >: steigende Mieten, wegfallende Räume, Häuser, die nach Jahrzehnten zumachen. Eine
        Location, die niemand mehr findet, ist dabei nicht das größte Problem. Aber sie ist eines,
        an dem ich etwas ändern kann.
      </p>
      <p class="text-muted-foreground">
        Der Weiterbau der A100 ist davon der sichtbarste Fall: Die geplante Verlängerung führt durch
        Treptow, an Clubs und Kulturorten vorbei, die an der Strecke liegen. Dagegen steht
        <a
          class="text-foreground underline underline-offset-4"
          href="https://a100-wegbassen.de/"
          rel="noopener"
          target="_blank"
        >
          A100 Wegbassen</a
        >, ein Bündnis aus Clubs, Anwohnerinitiativen und Klimagruppen, das den Stopp des Weiterbaus
        fordert und dafür Demos und Raves veranstaltet.
      </p>
      <p class="text-muted-foreground">
        Ich möchte etwas zurückgeben. Wenn diese Webseite dazu führt, dass ein paar Leute in einem
        kleinen Laden landen, von dem sie noch nie gehört haben, hat sie ihren Zweck erfüllt. Die
        großen Namen findet man auch ohne mich, die anderen nicht.
      </p>
      <p class="text-muted-foreground">
        Hilft das wirklich? Braucht das jemand? Ich weiß es nicht. Aber ich will es versuchen. Am
        Leben halten wir die Clubkultur ohnehin nur gemeinsam, Abend für Abend, indem wir hingehen.
      </p>
    </section>

    <section class="space-y-4 pt-4">
      <h2 class="text-2xl font-bold tracking-tight">Und zum Lernen</h2>
      <p class="text-muted-foreground">
        Der andere ehrliche Grund: Ich habe das gebaut, um zu lernen, vor allem über
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
        Produktentscheidungen, die Architektur und die Prioritäten sind meine. Die Agenten setzen
        sie um, und ich prüfe jede Änderung, bevor sie landet.
      </p>
      <p class="text-muted-foreground">
        Ich sage das, weil es der spannende Teil ist. An einem echten Projekt, mit echten
        Nutzer*innen und echten Einschränkungen, findet man heraus, worin diese Arbeitsweise gut ist
        und wo es weiter einen Menschen braucht, der hinschaut. Das Ganze ist
        <a
          class="text-foreground underline underline-offset-4"
          href="https://github.com/enorm-labs/event-junkie"
          rel="noopener"
          target="_blank"
        >
          Open Source</a
        >, Konventionen und Prompts inklusive, falls du sehen willst, wie es gemacht wurde.
      </p>
    </section>

    <!-- Das Beta-Abzeichen im Header verlinkt hierher. `scroll-mt` hält die Überschrift frei vom
         Header, wenn der Anker angesprungen wird. Die id muss `beta` bleiben: sie ist in beiden
         Sprachfassungen dasselbe Ziel. -->
    <section id="beta" class="scroll-mt-8 space-y-4 pt-4">
      <h2 class="text-2xl font-bold tracking-tight">Warum da beta steht</h2>
      <p class="text-muted-foreground">
        Event Junkie ist noch jung. Es funktioniert und wir nutzen es selbst jede Woche, aber es
        sortiert sich noch, und das solltest du wissen, bevor du einen Abend darauf aufbaust.
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
        <strong class="font-medium text-foreground">Dinge ändern sich ohne Ankündigung:</strong>
        Seiten, Filter und die Daten dahinter sind alle noch in Bewegung.
      </p>
      <p class="text-muted-foreground">
        Was beta <em>nicht</em> heißt: Nichts hier ist eine Testphase, für die du später zur Kasse
        gebeten wirst, es werden keine Daten über dich verkauft oder weitergegeben, und nichts
        trackt dich. Genau das sagt die Kurzfassung ganz oben auf der
        <RouterLink
          :to="localePath('/legal/privacy')"
          class="text-foreground underline underline-offset-4"
        >
          Datenschutzseite</RouterLink
        >.
      </p>
      <p class="text-muted-foreground">
        Etwas gefunden, das nicht stimmt? Uns Bescheid zu geben ist der schnellste Weg zur
        Korrektur:
        <a
          class="text-foreground underline underline-offset-4"
          href="https://github.com/enorm-labs/event-junkie/issues/new/choose"
          rel="noopener"
          target="_blank"
        >
          Issue eröffnen</a
        >. Was sich zuletzt geändert hat, steht auf der
        <a
          class="text-foreground underline underline-offset-4"
          href="https://github.com/enorm-labs/event-junkie/releases"
          rel="noopener"
          target="_blank"
        >
          Releases-Seite</a
        >.
      </p>
    </section>
  </main>
</template>
