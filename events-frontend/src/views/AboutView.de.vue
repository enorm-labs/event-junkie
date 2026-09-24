<script lang="ts" setup>
/**
 * „Über das Projekt“ — deutsche Fassung von `AboutView.en.vue`.
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
    <h1 class="text-3xl font-bold tracking-tight">Über das Projekt</h1>
    <p class="text-muted-foreground">
      Event Junkie ist dein Überblick über das, was in Berlins Locations läuft: ein Feed mit
      Live-Events, von Konzerten und Clubnächten über Festivals und Partys bis zum gelegentlichen
      Kneipenquiz.
    </p>
    <p class="text-muted-foreground">
      Stöbere durch alles, filtere nach Genre oder Art, bis du deine Szene gefunden hast, und plan
      im Kalender deine Woche. Von jedem Event kommst du weiter zur Location, zum Line-up und zu den
      Künstler*innen. So findest du die Abende, für die es sich lohnt, rauszugehen.
    </p>
    <p class="text-muted-foreground">
      Es geht um Musik jeder Art, auf großen Bühnen und in kleinen Hinterzimmern, überall in Berlin.
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
        Sport, auch in den Arenen, in denen sonst Konzerte laufen: Wegen eines Basketballspiels bist
        du nicht hier, und sobald Sport mit drin ist, gehen die Konzerte darin unter. Führungen,
        Workshops und Yoga-Stunden, also Sachen zum Mitmachen statt zum Zuschauen. Messen und
        Kongresse. Und, vorerst, klassische Konzerte und Orchester: Ein Orchester mit Dirigent*in
        und Solist*innen passt nicht in ein Modell, das um Headliner und Support herum gebaut ist.
        Das ist ein „noch nicht“, und die Frage ist notiert.
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
        Locations und Veranstaltern, jede mit eigenem Layout und eigenen Lücken. Eine ganz normale
        Frage wie
        <em
          >Was läuft dieses Wochenende in meiner Nähe, in meinem Genre, und was kann ich mir
          leisten?</em
        >
        heißt: ein Dutzend Browser-Tabs und viel Raten. Die Informationen sind alle da. Sie stehen
        nur nirgends zusammen.
      </p>
      <p class="text-muted-foreground">
        Die bestehenden Angebote lösen jeweils nur einen Teil davon.
        <strong class="font-medium text-foreground">Resident Advisor</strong> ist stark bei
        elektronischer Musik, aber einen Abend in einer Punkkneipe in Friedrichshain oder in einem
        Kabarettsaal in Wilmersdorf hat es nicht im Blick.
        <strong class="font-medium text-foreground">Bandsintown und Songkick</strong> folgen
        <em>Künstler*innen</em>. Das hilft, wenn du schon weißt, wen du sehen willst, aber gar
        nicht, wenn du wissen willst, was am Donnerstag los ist. Ticketportale zeigen, was sie
        verkaufen. Abende mit freiem Eintritt oder nur Abendkasse fehlen dort, genauso wie die
        kleinen Läden, die noch nie ein Ticket online verkauft haben.
      </p>
      <p class="text-muted-foreground">
        Was fehlt, will keins davon sein:
        <strong class="font-medium text-foreground">ein Feed für alles</strong>, für jede Art von
        Location und jedes Genre, ob umsonst oder mit Ticket. Du filterst nach dem, was für dich
        sowieso zählt (heute Abend, in der Nähe, dein Genre, unter 15 €). Jeder Eintrag verlinkt auf
        die Seite der Location: Dort gibt es die Tickets, und dort steht, was am Ende gilt.
      </p>
      <p class="text-muted-foreground">
        Ich wollte das schon seit Jahren bauen. Angefangen habe ich mehrmals, aber als Hobbyprojekt
        neben allem anderen war es für eine Person jedes Mal zu viel Arbeit. Mit KI-Agenten geht es
        jetzt.
      </p>
    </section>

    <section class="space-y-4 pt-4">
      <h2 class="text-2xl font-bold tracking-tight">Warum Berlin</h2>
      <p class="text-muted-foreground">
        Berlin ist eine der besten Städte der Welt. Nicht immer sauber, ziemlich verrückt, arm, aber
        sexy. Vor allem ist es ein Ort, an dem man frei leben und der Mensch sein kann, der man ist.
        Diese Stadt ist divers und bunt, und abends hört das nicht auf, sondern fängt erst richtig
        an.
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
        im bundesweiten Verzeichnis des immateriellen Kulturerbes. Das Verzeichnis führt keine
        Gebäude, sondern gelebte Kultur. Und was gelebt wird, kann auch verschwinden.
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
        >, der Verband der Berliner Clubkultur, nennt es
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
        Am sichtbarsten ist das beim Weiterbau der A100: Die geplante Verlängerung führt durch
        Treptow, direkt an Clubs und Kulturorten vorbei. Dagegen kämpft
        <a
          class="text-foreground underline underline-offset-4"
          href="https://a100-wegbassen.de/"
          rel="noopener"
          target="_blank"
        >
          A100 Wegbassen</a
        >, ein Bündnis aus Clubs, Nachbarschaftsinitiativen und Klimagruppen. Es fordert, den
        Weiterbau zu stoppen, und organisiert dafür Demos und Raves.
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
        ). Sie arbeiten nach Konventionen und Prompts, die im Repository liegen. Die Vision, die
        Produktentscheidungen, die Architektur und die Prioritäten sind meine. Die Agenten setzen
        sie um, und ich prüfe jede Änderung, bevor sie übernommen wird.
      </p>
      <p class="text-muted-foreground">
        Ich erzähle das, weil genau das der spannende Teil ist. Erst an einem echten Projekt mit
        Nutzer*innen und echten Grenzen merkt man, was diese Arbeitsweise gut kann und wo weiter ein
        Mensch hinschauen muss. Das Ganze ist
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
        Event Junkie ist noch jung. Es funktioniert, und wir nutzen es selbst jede Woche. Aber es
        ist noch nicht fertig, und das solltest du wissen, bevor du deinen Abend danach planst.
      </p>
      <p class="text-muted-foreground">
        <strong class="font-medium text-foreground">Es fehlen noch Locations.</strong> Neue kommen
        nach und nach dazu. Wenn bei Event Junkie wenig los ist, heißt das also nicht, dass in
        Berlin wenig los ist.
        <strong class="font-medium text-foreground"
          >Details können falsch oder veraltet sein.</strong
        >
        Wir lesen die Events automatisch von den Websites der Locations. Wird eine Show verlegt, ist
        sie ausverkauft oder fällt sie aus, erfahren wir das erst beim nächsten Durchlauf. Frag im
        Zweifel bei der Location nach, bevor du losziehst.
        <strong class="font-medium text-foreground">Manches ändert sich ohne Ankündigung:</strong>
        Seiten, Filter und die Daten dahinter sind noch in Bewegung.
      </p>
      <p class="text-muted-foreground">
        Was beta <em>nicht</em> heißt: Das hier ist keine Testphase, nach der du zahlen musst. Es
        werden keine Daten über dich verkauft oder weitergegeben, und nichts trackt dich. Das steht
        auch in der Kurzfassung ganz oben auf der
        <RouterLink
          :to="localePath('/legal/privacy')"
          class="text-foreground underline underline-offset-4"
        >
          Datenschutzseite</RouterLink
        >.
      </p>
      <p class="text-muted-foreground">
        Etwas gefunden, das nicht stimmt? Sag uns Bescheid, dann ist es am schnellsten korrigiert:
        <a
          class="text-foreground underline underline-offset-4"
          href="https://github.com/enorm-labs/event-junkie/issues/new/choose"
          rel="noopener"
          target="_blank"
        >
          Fehler auf GitHub melden</a
        >. Was sich zuletzt geändert hat, steht auf der
        <a
          class="text-foreground underline underline-offset-4"
          href="https://github.com/enorm-labs/event-junkie/releases"
          rel="noopener"
          target="_blank"
        >
          Releases-Seite auf GitHub</a
        >.
      </p>
    </section>
  </main>
</template>
