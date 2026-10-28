<script lang="ts" setup>
/**
 * Datenschutzerklärung nach Art. 13 DSGVO — die **maßgebliche Fassung** (LEGAL.md §6.1).
 *
 * Follows the same twelve mandatory items as `PrivacyView.en.vue` (docs/LEGAL.md §7.2) and cites
 * them in German form — *Art. 6 Abs. 1 lit. f DSGVO*, not *Art. 6 (1) (f) GDPR*. The unit test runs
 * the checklist per language: a dropped element in one version is invisible from the other. It
 * describes no processing that does not happen — no consent, no analytics.
 *
 * **Two claims here are coupled to deployed configuration** (§7.5.1, §7.3a). §2's **14 Tage** is
 * `ZO_COMPACT_DATA_RETENTION_DAYS`, and §5's "no image request reaches a third party" is
 * `images.serving.enabled`. Change either value and this notice becomes false.
 *
 * Register: `du`, for the reasons set out in `ImprintView.de.vue`.
 */
import { useI18n } from 'vue-i18n'

import LegalPage from '@/components/LegalPage.vue'
import ProvisionalNotice from '@/components/ProvisionalNotice.vue'
import { CONTROLLER, SUPERVISORY_AUTHORITY } from '@/lib/legal'

const { t } = useI18n()
</script>

<template>
  <LegalPage
    intro="Welche Daten Event Junkie verarbeitet, wozu, und was du dagegen tun kannst."
    show-authoritative-version
    show-review-date
    title="Datenschutz"
  >
    <ProvisionalNotice />

    <section>
      <h2>Kurz gesagt</h2>
      <p>
        Event Junkie betreibt keine Analyse-Tools und bindet keine Schriften, Karten,
        Social-Media-Widgets oder Werbung von Dritten ein. Cookies setzt es nur, wenn du dich
        anmeldest, und ein Konto brauchst du für nichts auf dieser Seite. Nichts von dem, was du
        hier tust, wird getrackt oder zu einem Profil verarbeitet. Übrig bleibt das unvermeidbare
        Minimum: Ein Webserver protokolliert Anfragen, damit die Seite ausgeliefert und geschützt
        werden kann, und dein Browser merkt sich, welche Sprache, welches Farbschema und welche
        Ansicht du gewählt hast. Fragst du eine Karte, was in deiner Nähe läuft, bleibt dein
        Standort in deinem Browser. Legst du ein Konto an, speichere ich deine E-Mail-Adresse, bis
        du es löschst (Abschnitt 6). Schaltest du Benachrichtigungen ein, laufen sie verschlüsselt
        über den Push-Dienst deines Browser-Herstellers (Abschnitt 6).
      </p>
      <p>
        <strong>Auch die Bilder kommen von Event Junkie.</strong> Event Junkie lädt sie einmal
        herunter und liefert sie selbst aus, sodass dein Browser beim Ansehen einer Seite keine
        Anfrage an Dritte stellt. Abschnitt 5 sagt, was daraus folgt.
      </p>
    </section>

    <section>
      <h2>1. Verantwortlicher</h2>
      <p>Verantwortlicher für die Datenverarbeitung auf dieser Seite ist:</p>
      <p>{{ CONTROLLER.name }}</p>
      <p>{{ CONTROLLER.careOf }}</p>
      <p>{{ CONTROLLER.street }}</p>
      <p>{{ CONTROLLER.city }}</p>
      <p>{{ t('legal.country') }}</p>
      <p>
        E-Mail:
        <a :href="`mailto:${CONTROLLER.email}`">{{ CONTROLLER.email }}</a>
      </p>
      <p>
        Ein Datenschutzbeauftragter ist nicht bestellt. Weder Art. 37 DSGVO noch § 38 BDSG verlangen
        das hier: Die Seite wird von einer Einzelperson betrieben, Daten werden nicht umfangreich
        verarbeitet, und eine systematische Überwachung findet nicht statt. Nutze für alles, was
        deine Daten betrifft, die oben genannte Adresse.
      </p>
    </section>

    <section>
      <h2>2. Server-Logfiles</h2>
      <p>
        Eine Webseite auszuliefern bedeutet zwangsläufig, dass dein Browser eine Anfrage sendet und
        die beteiligten Server sie protokollieren. Dabei können verarbeitet werden: Datum und
        Uhrzeit der Anfrage, die angeforderte Seite oder Datei, der HTTP-Status und die übertragene
        Datenmenge, die zuvor besuchte Seite sowie Angaben zu Browser und Betriebssystem. Deine
        IP-Adresse gehört nicht dazu: Der Webserver verwendet ein Log-Format, das sie auslässt,
        sodass gar keine Adresse geschrieben wird.
      </p>
      <p>
        <strong>Zweck:</strong> Auslieferung der Seite, Aufrechterhaltung von Verfügbarkeit und
        Sicherheit, Abwehr von Angriffen und Missbrauch sowie Fehlerdiagnose.
      </p>
      <p>
        <strong>Rechtsgrundlage:</strong> Art. 6 Abs. 1 lit. f DSGVO. Mein berechtigtes Interesse
        ist der Betrieb einer funktionierenden und sicheren Website. Erwägungsgrund 49 DSGVO erkennt
        dieses Interesse ausdrücklich an. Ich nutze diese Daten nicht, um dich zu identifizieren
        oder ein Profil zu bilden, und führe sie mit keinen anderen Daten zusammen.
      </p>
      <p>
        <strong>Speicherdauer:</strong> Logdaten werden <strong>14 Tage</strong> aufbewahrt und
        danach automatisch gelöscht. Zwei unabhängige Mechanismen begrenzen sie, und meist greift
        der, der früher löscht: Auf dem Server selbst werden Einträge nach Volumen verdrängt. Dort
        wird eine feste Menge vorgehalten und der jeweils älteste Eintrag überschrieben, sodass eine
        Zeile oft früher verschwindet. Im Log-Speicher wird unabhängig davon alles gelöscht, was
        älter als 14 Tage ist. Ausgenommen sind Einträge, die wegen eines konkreten
        Sicherheitsvorfalls länger aufbewahrt werden müssen. Genannt wird hier immer die tatsächlich
        konfigurierte Frist.
      </p>
      <p>
        <strong>Sicherungskopien:</strong> Die Datenbank wird gesichert. Diese Sicherungen werden im
        Regelfall <strong>30 Tage</strong> aufbewahrt und danach automatisch gelöscht;
        <strong>spätestens nach 35 Tagen</strong> werden sie in jedem Fall gelöscht. Die beiden
        Angaben unterscheiden sich, weil zwei voneinander unabhängige Mechanismen löschen: die
        tägliche Bereinigung auf dem Server, die eine Sicherung so lange behält, wie sie zur
        Wiederherstellung der übrigen benötigt wird, und eine Regel des Speicherdienstes, die
        unabhängig davon greift, auch dann, wenn der Server ausgefallen ist. Diese Frist ist von der
        oben genannten unabhängig: Daten, die im laufenden Betrieb bereits gelöscht wurden, können
        in einer Sicherung noch bis zu 35 Tage fortbestehen. Sicherungen dienen ausschließlich der
        Wiederherstellung nach einem Ausfall. Sie werden nicht durchsucht und nicht ausgewertet.
      </p>
    </section>

    <section>
      <h2>3. Lokale Speicherung auf deinem Gerät</h2>
      <p>
        Ohne Anmeldung setzt diese Seite <strong>keine Cookies</strong>. Sie speichert genau drei
        Werte im lokalen Speicher deines Browsers, und alle drei halten nur fest, was du selbst
        eingestellt hast:
      </p>
      <ul>
        <li>
          <code>theme</code> mit dem Wert <code>light</code> oder <code>dark</code>, damit deine
          Darstellungseinstellung ein Neuladen der Seite übersteht
        </li>
        <li>
          <code>locale</code> mit dem Wert <code>de</code> oder <code>en</code>, damit du beim
          nächsten Besuch in der zuletzt gewählten Sprache landest
        </li>
        <li>
          <code>view</code> mit dem Wert <code>poster</code> oder <code>compact</code>, damit die
          Veranstaltungsliste so erscheint, wie du sie zuletzt eingestellt hast: mit Bildern oder
          kompakt
        </li>
      </ul>
      <p>
        Diese Werte verlassen dein Gerät nicht, werden weder an Event Junkie noch an Dritte
        übermittelt und sind für Dritte nicht auslesbar. Alle drei sind unbedingt erforderlich, um
        die von dir ausdrücklich gewählte Einstellung bereitzustellen, und bedürfen deshalb nach §
        25 Abs. 2 Nr. 2 TDDDG keiner Einwilligung. Deshalb musst du hier auch kein Banner
        wegklicken. Du kannst sie jederzeit über die Einstellungen deines Browsers löschen.
      </p>
      <!-- LEGAL.md §7.4: the two cookies exist only on the sign-in and account pages. -->
      <h3>Cookies, wenn du dich anmeldest</h3>
      <p>
        Öffnest du die Anmelde- oder die Kontoseite, setzt Event Junkie zwei Cookies, und nur diese:
      </p>
      <ul>
        <li>
          <code>XSRF-TOKEN</code>, sobald du eine dieser Seiten öffnest. Es enthält einen
          Zufallswert und verhindert, dass eine fremde Seite in deinem Namen einen Anmeldelink
          anfordert oder dein Konto ändert. Es läuft nach 30 Tagen ab.
        </li>
        <li>
          <code>__Host-ej_session</code>, sobald du dich mit dem Link oder einem Passkey anmeldest.
          Es enthält eine zufällige Kennung deiner Sitzung und hält dich angemeldet. Es läuft 30
          Tage nach der letzten Nutzung ab, und beim Abmelden wird es gelöscht.
        </li>
      </ul>
      <p>
        Beide sind unbedingt erforderlich für die Anmeldung, die du selbst anforderst, und bedürfen
        deshalb nach § 25 Abs. 2 Nr. 2 TDDDG keiner Einwilligung. Sie dienen nicht dem Tracking und
        gehen an niemanden außer Event Junkie.
      </p>
      <!-- LEGAL.md §7.4b: the push worker exists only in a browser that turned notifications on (ADR-055). -->
      <h3>Benachrichtigungen, wenn du sie einschaltest</h3>
      <p>
        Erst wenn du auf deiner Kontoseite <strong>Benachrichtigungen einschalten</strong> drückst,
        fragt dein Browser, ob diese Seite dir Benachrichtigungen schicken darf, und du
        entscheidest. Erlaubst du es, installiert Event Junkie ein kleines Skript in deinem Browser,
        einen Service Worker, und dein Browser speichert ein Abonnement für diese Seite. Das Skript
        zeigt eine eintreffende Benachrichtigung und öffnet ihren Link, wenn du darauf klickst.
        Sonst tut es nichts: Es speichert nichts und ändert nicht, wie eine Seite lädt. Beides ist
        für die Benachrichtigungen, die du anforderst, unbedingt erforderlich, nach § 25 Abs. 2 Nr.
        2 TDDDG also ohne Einwilligung. Schaltest du Benachrichtigungen aus, ist beides wieder weg.
      </p>
      <!-- LEGAL.md §7.4a: the position is used in the browser and nowhere else. -->
      <h3>Dein Standort für „In der Nähe“</h3>
      <p>
        Die Eventkarte kann zeigen, was in deiner Nähe läuft, und die Location-Karte, welche
        Locations in deiner Nähe sind. Erst wenn du
        <strong>Meinen Standort verwenden</strong> wählst, fragt dein Browser, ob er deine Position
        teilen soll, und du entscheidest. Erlaubst du es, wird die Position
        <strong>nur in deinem Browser</strong> verwendet, um die Entfernung zu jeder Location zu
        berechnen. Sie wird weder an Event Junkie noch an Dritte übermittelt, erscheint in keiner
        Adresse und keinem Protokoll und wird nicht gespeichert: Sie ist weg, sobald du die Seite
        neu lädst oder den Tab schließt. Auf der Eventkarte kannst du stattdessen auch einen Punkt
        oder eine Location wählen, mit demselben Ergebnis.
      </p>
      <p>
        Weil die Position dein Gerät nie verlässt, habe ich keine Kopie davon, und es gibt nichts,
        was ich löschen oder herausgeben könnte.
      </p>
    </section>

    <section>
      <!-- §7.3: Künstlernamen sind personenbezogene Daten, soweit die Künstlerin oder der Künstler
           eine natürliche Person ist. Das zu benennen und einen Weg zur Löschung anzubieten ist die
           wahrscheinlichste Beschwerde, die diese Seite je erreichen wird. -->
      <h2>4. Daten zu Veranstaltungen, Künstler*innen und Locations</h2>
      <p>
        Event Junkie sammelt öffentlich zugängliche Informationen über Veranstaltungen in Berlin von
        den Websites der Locations, Veranstalter und Ticketanbieter und führt sie an einer Stelle
        zusammen. Soweit auftretende Künstler*innen natürliche Personen sind, sind ihre Namen und
        ihre Platzierung im Line-up personenbezogene Daten.
      </p>
      <p>
        <strong>Herkunft dieser Daten.</strong> Sie stammen nicht von dir, sondern aus den oben
        genannten öffentlich zugänglichen Quellen. Für sie gilt deshalb Art. 14 DSGVO. Jede genannte
        Person einzeln zu benachrichtigen wäre mit unverhältnismäßigem Aufwand verbunden; nach Art.
        14 Abs. 5 lit. b DSGVO tritt an dessen Stelle, dass diese Informationen hier öffentlich
        bereitstehen.
      </p>
      <p>
        Künstlerprofile werden mit MusicBrainz abgeglichen, einer offenen Musikdatenbank. Wo sie
        übereinstimmen, stammen die offiziellen Links und das Bild von dort und aus Wikidata.
        Übermittelt wird nur der Künstlername. Für Bands, Orchester und Chöre stammt die
        Kurzbeschreibung aus der Wikipedia. Aus Wikidata stammt auch die Angabe, ob jemand als
        Comedian oder Kabarettist auftritt. Danach wird ein Abend, dem die Spielstätte keine
        Kategorie gibt, als Comedy geführt. Kennt MusicBrainz einen Namen nicht, fragt der Importer
        Discogs (Zink Media, LLC, USA) und speichert bei einem Treffer nur den Link zur
        Discogs-Seite; auch dabei wird nur der Künstlername übermittelt. Diese Website nutzt die
        Discogs-API, ist aber nicht mit Discogs verbunden und wird von Discogs weder gesponsert noch
        empfohlen. „Discogs“ ist eine Marke der Zink Media, LLC.
      </p>
      <p>
        Die Line-ups und Spielzeiten der Wochenenden im Sisyphos stammen von sisy.fan, einem
        inoffiziellen, von Fans betriebenen Timetable, dessen Entwickler Event Junkie die Nutzung
        erlaubt. Der Importer ruft nur die Seite ab; Daten über dich werden dabei nicht übermittelt.
      </p>
      <p>
        Bei einigen Locations ohne eigene Website stammen die Veranstaltungen aus den öffentlichen
        Beiträgen ihres Instagram-Geschäftskontos. Der Importer liest sie über die offizielle
        Schnittstelle von Meta (Meta Platforms Ireland Ltd.) mit dem Konto von Event Junkie. Er
        liest den Text, den Link, das Datum und die Bilder eines Beitrags. Ein Sprachmodell entwirft
        daraus Datum, Titel und Line-up (Abschnitt 5), und ich prüfe jeden Entwurf, bevor eine
        Veranstaltung erscheint. Der Text des Beitrags und der Text, der aus seinen Bildern gelesen
        wird, werden nur bis zu dieser Prüfung gespeichert, höchstens 14 Tage, und nie auf dieser
        Seite gezeigt. Die Bilder werden nicht gespeichert. Die Veranstaltung verlinkt den Beitrag.
        Daten über dich werden dabei nicht übermittelt.
      </p>
      <p>
        <strong>Zweck:</strong> Information der Öffentlichkeit über öffentlich angekündigte
        kulturelle Veranstaltungen.
      </p>
      <p>
        <strong>Rechtsgrundlage:</strong> Art. 6 Abs. 1 lit. f DSGVO. Mein berechtigtes Interesse
        ist der Betrieb eines Veranstaltungskalenders. Die betroffenen Informationen wurden von den
        Veranstaltern bereits selbst veröffentlicht, und zwar ausdrücklich zur Bewerbung der
        Veranstaltung; sie beschränken sich auf diesen beruflichen Kontext. Event Junkie speichert
        keine Kontaktdaten, keine Privatanschriften und nichts über das Privatleben.
      </p>
      <p>
        <strong>Speicherdauer:</strong> Veranstaltungsdaten werden nicht nach einer festen Frist
        gelöscht. Vergangene Veranstaltungen bleiben als Archiv erhalten, solange der Kalender
        betrieben wird; eine automatische Löschung nach Alter findet nicht statt. Das Kriterium ist
        also der Betrieb des Kalenders und nicht der Zeitablauf. Dein Name wird davon unabhängig
        entfernt, sobald du der Verarbeitung nach Art. 21 DSGVO widersprichst. Dafür genügt die
        E-Mail im nächsten Absatz.
      </p>
      <p>
        <strong>Bilder:</strong> Es gibt zwei Quellen. Locations, Veranstalter und Künstler*innen
        veröffentlichen Bilder, zu einzelnen Veranstaltungen und zu sich selbst. Fotos von Gebäuden
        der Locations stammen außerdem aus offenen Archiven wie Wikimedia Commons und Flickr, wo
        Fotograf*innen sie unter einer Lizenz veröffentlicht haben, die das erlaubt. Event Junkie
        lädt beides herunter und speichert eine Kopie auf eigenen Servern bei Hetzner, statt sie bei
        jedem Seitenaufruf erneut beim Anbieter anzufragen. Rechtsgrundlage ist Art. 6 Abs. 1 lit. f
        DSGVO. Das berechtigte Interesse ist eine Auslieferung ohne Beteiligung Dritter und eine
        geringere Last für die Server der Locations. Die Kopie wird gelöscht, sobald die Location
        aussteigt oder Rechteinhaber*innen das verlangen, und danach nicht erneut geladen. Bei einem
        Archivfoto sind die Rechteinhaber*innen die Fotograf*innen und nicht die Location. Der
        Bildnachweis unter dem Foto nennt sie.
      </p>
      <p>
        <strong>Übersetzungen:</strong> Beschreibungen werden maschinell übersetzt, damit die Seite
        sie in beiden Sprachen zeigen kann. Dafür wird der Text an einen Dienstleister übermittelt,
        den Abschnitt 5 nennt. Eine maschinell übersetzte Beschreibung ist auf der
        Veranstaltungsseite als solche gekennzeichnet, und der Originaltext bleibt maßgeblich.
      </p>
      <p>
        <strong>Prüfung der Veranstaltungsdaten:</strong> Titel, Untertitel und Beschreibung einer
        Veranstaltung werden maschinell geprüft, um Fehler beim Import zu finden. Ein Beispiel ist
        eine falsche Veranstaltungsart oder ein Titel, der als Künstler*in gespeichert wurde. Das
        Ergebnis ist nur ein Vorschlag. Ob er übernommen wird, entscheide ich. Dafür werden diese
        Texte an denselben Dienstleister übermittelt wie für die Übersetzung (Abschnitt 5).
        Rechtsgrundlage ist Art. 6 Abs. 1 lit. f DSGVO. Das berechtigte Interesse sind richtige
        Angaben zu den Veranstaltungen.
      </p>
      <p>
        <strong>Wenn du Künstler*in bist</strong> und möchtest, dass dein Name oder eine
        Beschreibung, die dich nennt, entfernt oder korrigiert wird, schreib an
        <a :href="`mailto:${CONTROLLER.email}`">{{ CONTROLLER.email }}</a
        >. Ich kümmere mich umgehend darum. Eine Begründung brauchst du nicht, und du musst das
        Thema nicht öffentlich ansprechen.
      </p>
    </section>

    <section>
      <h2>5. Wer sonst beteiligt ist</h2>
      <p>
        Ich verkaufe nichts und gebe nichts zu Werbezwecken weiter. Daten werden an drei
        Dienstleister übermittelt, alle drei Auftragsverarbeiter mit einem Vertrag nach Art. 28
        DSGVO:
      </p>
      <ul>
        <li>
          <strong>Hosting:</strong> Hetzner Online GmbH, Gunzenhausen, Deutschland. Die Server
          stehen in Deutschland; eine Übermittlung außerhalb der EU findet dabei nicht statt.
        </li>
        <li>
          <strong>Versand von E-Mails:</strong> Scaleway SAS, Paris, Frankreich. Forderst du einen
          Anmeldelink an, verschickt Scaleway die Mail an deine Adresse. Dafür erhält Scaleway deine
          E-Mail-Adresse und den Text der Mail. Die Server stehen in der EU; eine Übermittlung
          außerhalb der EU findet dabei nicht statt.
        </li>
        <li>
          <strong>Maschinelle Übersetzung und Prüfung der Veranstaltungsdaten:</strong> Anthropic
          PBC, San Francisco, USA. Dorthin wird der Beschreibungstext einer Veranstaltung
          übermittelt, damit er übersetzt werden kann. Für die Prüfung (Abschnitt 4) gehen
          zusätzlich Titel und Untertitel mit. Nennen diese Texte Künstler*innen, werden diese Namen
          mit übermittelt; die Namen des Line-ups und der Location werden zusätzlich ausdrücklich
          mitgesendet, damit die Übersetzung sie unverändert lässt. Für Veranstaltungen aus
          Instagram-Beiträgen (Abschnitt 4) gehen Text und Bilder des Beitrags dorthin, mit dem
          Namen der Location und dem Datum des Beitrags. Nennt ein Beitrag Personen, gehen ihre
          Namen mit, und ein Bild kann eine Person zeigen. Andere Daten erreichen diesen Dienst
          nicht: weder Logdaten noch irgendetwas über dich.
        </li>
      </ul>
      <p>
        <strong>Übermittlung in die USA.</strong> Anthropic verarbeitet diese Texte und Bilder
        ausschließlich nach meiner Weisung. Grundlage ist das Data Processing Addendum, das über die
        Commercial Terms of Service Bestandteil des Vertrags ist und die Standardvertragsklauseln
        der EU-Kommission einschließt (Art. 46 Abs. 2 lit. c DSGVO). Derselbe Vertrag schließt aus,
        dass die übermittelten Texte und Bilder zum Training von Modellen verwendet werden.
      </p>
      <p>
        Es gibt kein Content-Delivery-Netzwerk, keinen Edge-Anbieter und keinen Proxy vor der Seite.
        Deine Anfrage erreicht die Server von Event Junkie in Deutschland direkt. An der
        Auslieferung dieser Seite ist damit kein Drittland beteiligt. Übersetzung, Prüfung und
        Entwürfe oben sind der einzige Weg, auf dem Daten die EU verlassen, und sie betreffen
        Veranstaltungstexte und Flyer, nicht deinen Besuch. Die einzige Ausnahme sind
        Benachrichtigungen, wenn du sie einschaltest: Abschnitt 6 sagt, was beim Push-Dienst deines
        Browsers ankommt.
      </p>
      <p>
        <strong>Für Bilder gilt das ebenso.</strong> Sie kommen von den Servern von Event Junkie,
        weil Event Junkie sie einmal herunterlädt und selbst ausliefert (Abschnitt 4). Dein Browser
        fragt deshalb keine Location, keinen Veranstalter und keinen Ticketanbieter an, und keiner
        von ihnen erfährt beim Ansehen einer Seite deine IP-Adresse. Wo Event Junkie ein Bild nicht
        speichern darf, zeigt es gar keines, statt es beim Anbieter zu laden.
      </p>
      <p>
        Wenn du mir per E-Mail schreibst oder ein Issue auf GitHub eröffnest, werden diese Daten zur
        Bearbeitung deines Anliegens verarbeitet (Art. 6 Abs. 1 lit. f DSGVO, bei Vertragsbezug Art.
        6 Abs. 1 lit. b DSGVO). GitHub wird von einem US-Unternehmen betrieben; ob du dort statt per
        E-Mail schreibst, entscheidest du.
      </p>
      <p>
        <strong>Speicherdauer:</strong> Ich bewahre eine E-Mail auf, bis dein Anliegen erledigt ist,
        und lösche sie <strong>spätestens ein Jahr danach</strong>. Das Jahr deckt Rückfragen zum
        selben Anliegen ab.
      </p>
    </section>

    <!-- LEGAL.md §7.1: an account is optional, and the account page is its deletion route. -->
    <section id="account">
      <h2>6. Dein Konto</h2>
      <p>
        Ein Konto ist freiwillig. Du meldest dich ohne Passwort an: Du gibst deine E-Mail-Adresse
        ein, und Event Junkie schickt dir von <code>noreply@notify.event-junkie.de</code> einen
        Link. Er gilt einmal und 15 Minuten lang. Benutzt du ihn zum ersten Mal, entsteht dein
        Konto.
      </p>
      <p>
        <strong>Was gespeichert wird:</strong> deine E-Mail-Adresse, die Sprache, in der du dich
        angemeldet hast, wann du das Konto angelegt und wann du dich zuletzt angemeldet hast. Zu
        jeder Anmeldung kommt eine Sitzung mit Beginn, letzter Nutzung und Ablauf. Vom Link und von
        der Sitzung speichere ich nur einen Hashwert, nicht den Wert selbst. Forderst du einen Link
        an, speichere ich ihn mit der Adresse, der Sprache und dem Zeitpunkt, auch wenn du ihn nie
        benutzt.
      </p>
      <p>
        <strong>Wem du folgst:</strong> Folgst du einem Act, einer Location oder einem Veranstalter,
        speichere ich das mit dem Zeitpunkt. Daraus entsteht deine Seite „Gefolgt“. Dort siehst du
        die Liste und kannst jedem Eintrag wieder entfolgen. Nur du siehst sie. Verschwindet ein
        Eintrag aus dem Kalender, lösche ich auch das Folgen.
      </p>
      <p>
        <strong>Gespeicherte Suchen:</strong> Speicherst du eine Suche, speichere ich ihre Filter,
        den Namen, den du ihr gibst, und den Zeitpunkt. Die Seite schlägt einen Namen aus den
        Filtern vor, und du kannst ihn ändern. Deine Seite „Gefolgt“ zeigt deine gespeicherten
        Suchen. Dort kannst du jede umbenennen oder löschen. Nur du siehst sie.
      </p>
      <p>
        <strong>Deine Pläne:</strong> Markierst du ein Event mit „Interessiert“ oder „Dabei“,
        speichere ich das mit dem Zeitpunkt. Daraus entstehen deine Seite „Gefolgt“ und dein
        Kalender, und die Eventkarten zeigen es dir. Nur du siehst es. Niemand sonst sieht, wer
        hingehen will, und kein Event zeigt eine Zahl. Vergangene Events bleiben als dein Verlauf,
        bis du sie zurücknimmst oder das Konto löschst. Verschwindet ein Event aus dem Kalender,
        lösche ich auch deinen Plan dafür.
      </p>
      <p>
        <strong>Was neu ist:</strong> Bekommt etwas, dem du folgst, einen neuen Termin, speichere
        ich dazu einen Hinweis zu deinem Konto: welche Veranstaltung, von welchem Act, welcher
        Location oder welchem Veranstalter er stammt, wann ich ihn angelegt habe und wann du ihn
        geöffnet hast. Die Glocke im Kopf der Seite zeigt die neuesten 20 und zählt die, die du noch
        nicht geöffnet hast. Nur du siehst sie. Ich lösche jeden Hinweis nach
        <strong>30 Tagen</strong>, gelesen oder nicht, und früher, wenn seine Veranstaltung aus dem
        Kalender verschwindet.
      </p>
      <p>
        <strong>Benachrichtigungen:</strong> Schaltest du Benachrichtigungen in einem Browser ein,
        speichere ich dessen Abonnement: eine Adresse beim Push-Dienst des Browsers und zwei
        Schlüssel, dazu den Zeitpunkt des Einschaltens und der letzten Benachrichtigung. Die Glocke
        an einem Act, einer Location oder einem Veranstalter, dem du folgst, legt fest, worum es
        geht: einen neuen Termin. Eine Benachrichtigung enthält einen Titel, eine Zeile und einen
        Link, sonst nichts, und sie ist verschlüsselt, sodass nur dein Browser sie lesen kann. Das
        Abonnement wird gelöscht, wenn du Benachrichtigungen ausschaltest, wenn der Push-Dienst es
        als erloschen meldet, und mit deinem Konto.
      </p>
      <p>
        <strong>Der Push-Dienst:</strong> Eine Benachrichtigung läuft über den Push-Dienst des
        Herstellers deines Browsers: Google bei Chrome und Android, Mozilla bei Firefox, Apple bei
        Safari und Microsoft bei Edge. Den Dienst wählt dein Browser, nicht Event Junkie. Er erhält
        die Adresse des Abonnements und den Zeitpunkt jeder Benachrichtigung, kann ihren Inhalt aber
        nicht lesen. Alle vier sind US-Unternehmen, diese Daten erreichen also ein Drittland.
      </p>
      <p>
        <strong>Passkeys:</strong> Fügst du auf deiner Kontoseite einen Passkey hinzu, erzeugt dein
        Gerät ein Schlüsselpaar. Der private Schlüssel bleibt auf deinem Gerät oder in deinem
        Passwortmanager, der ihn mit deinen anderen Geräten abgleichen kann. Dein Gerät entsperrt
        ihn mit Fingerabdruck, Gesicht oder PIN, und nichts davon erreicht Event Junkie. Ich
        speichere den öffentlichen Schlüssel, eine Kennung des Passkeys, einen Zähler, den Namen,
        den du ihm gibst, und wann du ihn hinzugefügt und zuletzt genutzt hast. Von welchem
        Hersteller dein Gerät oder Passwortmanager ist, speichere ich nicht. Dein Gerät bewahrt den
        Passkey mit einer zufälligen Kennung deines Kontos auf, und mit deiner E-Mail-Adresse, damit
        es dir zeigen kann, zu welchem Konto er gehört. Auf deiner Kontoseite kannst du jeden
        Passkey umbenennen oder löschen. Wenn du dein Konto löschst, werden auch alle Passkeys
        gelöscht. Ein hier gelöschter Passkey bleibt auf deinem Gerät, bis du ihn dort entfernst,
        aber du kannst dich mit ihm nicht mehr anmelden.
      </p>
      <p>
        <strong>Zweck und Rechtsgrundlage:</strong> Das Konto ist ein Dienst, den du selbst
        anforderst. Rechtsgrundlage ist Art. 6 Abs. 1 lit. b DSGVO.
      </p>
      <p>
        <strong>Schutz vor Missbrauch:</strong> An eine Adresse gehen höchstens drei Links pro
        Stunde. Damit niemand von einem Anschluss aus beliebig viele Mails auslöst, zählt der Server
        außerdem die Anfragen pro IP-Adresse, höchstens 20 pro Stunde, und die Anmeldungen mit
        Passkey pro IP-Adresse, höchstens 30 pro Stunde. Diese Zählungen hält er nur im
        Arbeitsspeicher. Sie werden nirgends gespeichert und sind nach spätestens einer Stunde
        verschwunden. Rechtsgrundlage ist Art. 6 Abs. 1 lit. f DSGVO, mein berechtigtes Interesse,
        den Mailversand und die Anmeldung vor Missbrauch zu schützen.
      </p>
      <p>
        <strong>Speicherdauer:</strong> Dein Konto bleibt, bis du es löschst. Nutzt du es
        <strong>24 Monate</strong> lang nicht, lösche ich es; einen Monat vorher bekommst du eine
        Mail. Als Nutzung zählt jede Anmeldung und jeder Besuch, bei dem du angemeldet bist. Eine
        Sitzung endet 30 Tage nach ihrer letzten Nutzung oder wenn du dich abmeldest. Ein Link
        verfällt nach 15 Minuten. Abgelaufene Links und Sitzungen lösche ich täglich, Hinweise unter
        der Glocke nach 30 Tagen.
      </p>
      <p>
        <strong>Selbst erledigen:</strong> Auf deiner Kontoseite kannst du alle gespeicherten Daten
        als Datei herunterladen (Art. 15 und 20 DSGVO), dich auf allen Geräten abmelden und das
        Konto löschen. Das Löschen wirkt sofort und nimmt alle Sitzungen mit. Für Sicherungskopien
        gilt Abschnitt 7.
      </p>
    </section>

    <section>
      <h2>7. Deine Rechte</h2>
      <p>Nach der DSGVO hast du das Recht,</p>
      <ul>
        <li>Auskunft über die zu deiner Person gespeicherten Daten zu erhalten (Art. 15 DSGVO)</li>
        <li>unrichtige Daten berichtigen zu lassen (Art. 16 DSGVO)</li>
        <li>Daten löschen zu lassen (Art. 17 DSGVO)</li>
        <li>die Verarbeitung einschränken zu lassen (Art. 18 DSGVO)</li>
        <li>deine Daten in einem übertragbaren Format zu erhalten (Art. 20 DSGVO)</li>
      </ul>
      <p>
        <strong>Löschung und Sicherungskopien.</strong> Eine Löschung nach Art. 17 DSGVO wirkt
        sofort im laufenden Betrieb. Sicherungskopien werden dabei nicht einzeln bearbeitet. Eine
        einzelne Person aus einer Sicherung herauszulösen würde deren Zweck zerstören und wäre
        technisch nur unter Inkaufnahme neuer Risiken möglich. Die Daten laufen daher mit der
        Sicherung selbst aus, also spätestens nach 35 Tagen. Muss eine Sicherung zwischenzeitlich
        eingespielt werden, wende ich die Löschung danach erneut an.
      </p>
      <p>
        <strong>Widerspruchsrecht (Art. 21 DSGVO).</strong> Da ich mich auf berechtigte Interessen
        nach Art. 6 Abs. 1 lit. f DSGVO stütze, hast du das Recht, jederzeit aus Gründen, die sich
        aus deiner besonderen Situation ergeben, gegen die Verarbeitung deiner personenbezogenen
        Daten Widerspruch einzulegen. Legst du Widerspruch ein, verarbeite ich die betroffenen Daten
        nicht mehr, es sei denn, ich kann zwingende schutzwürdige Gründe nachweisen, die deine
        Interessen, Rechte und Freiheiten überwiegen. Ein Widerspruch ist kostenlos und formlos.
        Eine E-Mail genügt.
      </p>
      <p>
        <strong>Beschwerderecht.</strong> Du kannst dich bei einer Aufsichtsbehörde beschweren,
        insbesondere im Mitgliedstaat deines gewöhnlichen Aufenthaltsorts, deines Arbeitsplatzes
        oder des Orts des mutmaßlichen Verstoßes. Für mich zuständig ist die
        <a :href="SUPERVISORY_AUTHORITY.url" rel="noopener" target="_blank">
          {{ SUPERVISORY_AUTHORITY.name }}</a
        >.
      </p>
    </section>

    <section>
      <h2>8. Bist du verpflichtet, Daten bereitzustellen?</h2>
      <p>
        Du bist weder gesetzlich noch vertraglich verpflichtet, personenbezogene Daten
        bereitzustellen. Technisch lässt sich eine Webseite allerdings nicht ohne die IP-Adresse
        deines Geräts ausliefern. Diese Seite zu nutzen, ohne dass sie verarbeitet wird, ist deshalb
        nicht möglich. Alles Weitere ist freiwillig. Für ein Konto brauchst du eine E-Mail-Adresse;
        ohne sie kann ich dir keinen Anmeldelink schicken.
      </p>
    </section>

    <section>
      <h2>9. Automatisierte Entscheidungsfindung</h2>
      <p>
        Findet nicht statt. Ich setze keine automatisierte Entscheidungsfindung und kein Profiling
        im Sinne von Art. 22 DSGVO ein.
      </p>
    </section>

    <section>
      <h2>10. Änderungen dieser Erklärung</h2>
      <p>
        Diese Erklärung beschreibt, was das System tatsächlich tut. Sie ändert sich also, wenn das
        System sich ändert. Das Prüfdatum oben auf dieser Seite sagt dir, wann sie zuletzt
        abgeglichen wurde.
      </p>
    </section>
  </LegalPage>
</template>
