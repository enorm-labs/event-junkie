<script lang="ts" setup>
/**
 * „Über das Projekt“ — deutsche Fassung von `AboutView.en.md`.
 *
 * Written rather than translated. The beta section is brand voice, not neutral prose
 * (BRANDING.md §8), and a literal rendering of *"still finding its feet"* or
 * *"the nights worth showing up for"* reads as a machine translation of an English site — which
 * is the one thing the page is there to disprove.
 *
 * Markdown, like the English version; its header says how the `<section>` blocks work.
 */
import { RouterLink } from 'vue-router'
import ProsePage from '@/components/ProsePage.vue'
import { useLocalePath } from '@/composables/useLocalePath'
import { feedbackMailto } from '@/lib/feedback'
import { CONTROLLER } from '@/lib/legal'

const localePath = useLocalePath()
</script>

<ProsePage>

# Über das Projekt

Event Junkie ist dein Überblick über das, was in Berlins Locations läuft: ein Feed mit Live-Events jeder Art, auf großen Bühnen und in kleinen Hinterzimmern.

Stöbere durch alles und plan im Kalender deine Woche. So findest du die Abende, für die es sich lohnt, rauszugehen.

<section>

## Was du hier findest

Die Regel ist einfach: **Was in Berlin abends auf einer Bühne stattfindet, gehört hierher.** Nicht nur Livemusik, und ganz sicher nicht nur Techno. Den größten Teil machen Konzerte und Clubnächte aus, danach kommen Stand-up und Kabarett. Dazu Festivals, Bühnenshows, Lesungen und Spoken Word, Filmvorführungen und Open-Air-Kino, Ausstellungen von der Eröffnung bis zum letzten Tag und ab und zu ein Kneipenquiz.

Wenn eine Location selbst sagt, was für ein Abend das ist, übernimmt Event Junkie das. Punk, Jazz, Indie, Metal, Klassik-Crossover, Drag und Singer-Songwriter-Abende stehen im selben Feed wie die Clubtermine.

**Ein paar Dinge fehlen mit Absicht.** Sport, auch in den Arenen, in denen sonst Konzerte laufen: Wegen eines Basketballspiels bist du nicht hier, und sobald Sport mit drin ist, gehen die Konzerte darin unter. Führungen, Workshops und Yoga-Stunden, also Sachen zum Mitmachen statt zum Zuschauen. Messen und Kongresse. Kindervorstellungen, etwa das Puppentheater für die Kita am Vormittag. Livestreams, die nur online laufen, denn dafür gibt es keinen Raum, in den du gehen kannst. Und, vorerst, klassische Konzerte und Orchester: Ein Orchester mit Dirigent\*in und Solist\*innen passt nicht in ein Modell, das um Headliner und Support herum gebaut ist. Das ist ein „noch nicht“, und die Frage ist notiert.

</section>

<section>

## Warum es das gibt

Weil ich **die Event-App bauen wollte, die Berlin verdient.**

Angefangen hat das lange vor dem Code. Ich war jahrelang auf Indie-Partys und Konzerten unterwegs und habe dafür jede Woche die Seiten meiner Lieblingsclubs von Hand abgeklappert, eine nach der anderen. Resident Advisor kannte ich damals noch nicht, und ob dort eine Indie-Party in einem kleinen Club überhaupt aufgetaucht wäre, ist eine andere Frage.

Berlins Szene ist riesig und verstreut. Was läuft, steht auf Dutzenden Websites von Locations und Veranstaltern, jede mit eigenem Layout und eigenen Lücken. Eine ganz normale Frage wie _Was läuft dieses Wochenende in meiner Nähe, in meinem Genre, und was kann ich mir leisten?_ heißt: ein Dutzend Browser-Tabs und viel Raten. Die Informationen sind alle da. Sie stehen nur nirgends zusammen.

Die bestehenden Angebote lösen jeweils nur einen Teil davon. **Resident Advisor** ist stark bei elektronischer Musik, aber einen Abend in einer Punkkneipe in Friedrichshain oder in einem Kabarettsaal in Wilmersdorf hat es nicht im Blick. **Bandsintown und Songkick** folgen _Künstler\*innen_. Das hilft, wenn du schon weißt, wen du sehen willst, aber gar nicht, wenn du wissen willst, was am Donnerstag los ist. Ticketportale zeigen, was sie verkaufen. Abende mit freiem Eintritt oder nur Abendkasse fehlen dort, genauso wie die kleinen Läden, die noch nie ein Ticket online verkauft haben.

Keins davon bietet, was eigentlich fehlt: **ein Feed für alles**, für jede Art von Location und jedes Genre, ob umsonst oder mit Ticket. Du filterst nach dem, was für dich sowieso zählt (heute Abend, in der Nähe, dein Genre, unter 15 €). Jeder Eintrag verlinkt auf die Seite der Location: Dort gibt es die Tickets, und dort steht, was am Ende gilt.

Ich habe es mehrmals versucht, aber als Hobbyprojekt neben allem anderen war es für eine Person jedes Mal zu viel Arbeit. Mit KI-Agenten geht es jetzt.

</section>

<section>

## Warum Berlin

Berlin ist eine der besten Städte der Welt. Nicht immer sauber, ziemlich verrückt, arm, aber sexy. Vor allem ist es ein Ort, an dem man frei leben und der Mensch sein kann, der man ist. Diese Stadt ist divers und bunt, und abends hört das nicht auf, sondern fängt erst richtig an.

Die Clubkultur, die daraus entstanden ist, gibt es so kein zweites Mal. Seit 2024 steht die [Technokultur in Berlin](https://www.unesco.de/staette/technokultur-in-berlin/) im bundesweiten Verzeichnis des immateriellen Kulturerbes. Das Verzeichnis führt keine Gebäude, sondern gelebte Kultur. Und was gelebt wird, kann auch verschwinden.

Genau das passiert gerade. Die [Clubcommission](https://www.clubcommission.de/), der Verband der Berliner Clubkultur, nennt es [Clubsterben](https://www.clubcommission.de/pressemitteilung-clubsterben-ist-wieder-an-der-tagesordnung/): steigende Mieten, wegfallende Räume, Häuser, die nach Jahrzehnten zumachen. Eine Location, die niemand mehr findet, ist dabei nicht das größte Problem. Aber sie ist eines, an dem ich etwas ändern kann.

Am sichtbarsten ist das beim Weiterbau der A100: Die geplante Verlängerung führt durch Treptow, direkt an Clubs und Kulturorten vorbei. Dagegen kämpft [A100 Wegbassen](https://a100-wegbassen.de/), ein Bündnis aus Clubs, Nachbarschaftsinitiativen und Klimagruppen. Es fordert, den Weiterbau zu stoppen, und organisiert dafür Demos und Raves.

Ich möchte etwas zurückgeben. Wenn diese Webseite dazu führt, dass ein paar Leute in einem kleinen Laden landen, von dem sie noch nie gehört haben, hat sie ihren Zweck erfüllt. Die großen Namen findet man auch ohne mich, die anderen nicht.

Hilft das wirklich? Braucht das jemand? Ich weiß es nicht. Aber ich will es versuchen. Am Leben halten wir die Clubkultur ohnehin nur gemeinsam, Abend für Abend, indem wir hingehen.

</section>

<section>

## Und zum Lernen

Der andere ehrliche Grund: Ich habe das gebaut, um zu lernen, vor allem über **KI-gestützte Entwicklung**. Der größte Teil des Codes hier stammt von KI-Agenten (hauptsächlich [Claude Code](https://claude.com/claude-code)). Die Vision, die Produktentscheidungen, die Architektur und die Prioritäten sind meine. Die Agenten setzen sie um, und ich prüfe jede Änderung, bevor sie übernommen wird.

Ich erzähle das, weil genau das der spannende Teil ist. Erst an einem echten Projekt mit Nutzer\*innen und echten Grenzen merkt man, was diese Arbeitsweise gut kann und wo weiter ein Mensch hinschauen muss. Das Ganze ist [öffentlich auf GitHub](https://github.com/enorm-labs/event-junkie), Konventionen und Prompts inklusive, falls du sehen willst, wie es gemacht wurde.

</section>

<!-- Das Beta-Abzeichen im Header verlinkt hierher. `scroll-mt` hält die Überschrift frei vom
     Header, wenn der Anker angesprungen wird. Die id muss `beta` bleiben: sie ist in beiden
     Sprachfassungen dasselbe Ziel. -->
<section id="beta" class="scroll-mt-8">

## Warum da beta steht

Event Junkie ist noch jung. Es funktioniert, und ich nutze es selbst jede Woche. Aber es ist noch nicht fertig, und das solltest du wissen, bevor du deinen Abend danach planst.

**Es fehlen noch Locations.** Neue kommen nach und nach dazu. Wenn bei Event Junkie wenig los ist, heißt das also nicht, dass in Berlin wenig los ist. **Details können falsch oder veraltet sein.** Der Importer liest die Events automatisch von den Websites der Locations. Wird eine Show verlegt, ist sie ausverkauft oder fällt sie aus, erfährt er das erst beim nächsten Durchlauf. Frag im Zweifel bei der Location nach, bevor du losziehst. **Manches ändert sich ohne Ankündigung:** Seiten, Filter und die Daten dahinter sind noch in Bewegung.

Was beta _nicht_ heißt: Das hier ist keine Testphase, nach der du zahlen musst. Es werden keine Daten über dich verkauft oder weitergegeben, und nichts trackt dich. Das steht auch in der Kurzfassung ganz oben auf der <RouterLink :to="localePath('/legal/privacy')">Datenschutzseite</RouterLink>.

Etwas gefunden, das nicht stimmt? Sag mir Bescheid, dann ist es am schnellsten korrigiert. Schreib an <a :href="feedbackMailto('Feedback zu Event Junkie')">{{ CONTROLLER.email }}</a>, ganz ohne Konto, oder [melde den Fehler auf GitHub](https://github.com/enorm-labs/event-junkie/issues/new/choose). Was sich zuletzt geändert hat, steht auf der [Releases-Seite auf GitHub](https://github.com/enorm-labs/event-junkie/releases).

</section>

</ProsePage>
