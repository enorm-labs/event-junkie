<script lang="ts" setup>
/**
 * Datenschutzerklärung nach Art. 13 DSGVO — die **maßgebliche Fassung** (LEGAL.md §6.1).
 *
 * Follows the same twelve mandatory items as `PrivacyView.en.vue` (docs/LEGAL.md §7.2) and cites
 * them in German form — *Art. 6 Abs. 1 lit. f DSGVO*, not *Art. 6 (1) (f) GDPR*. The unit test runs
 * the checklist per language: a dropped element in one version is invisible from the other. It
 * describes no processing that does not happen — no cookie table, no consent, no analytics.
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
        Event Junkie setzt keine Cookies, betreibt keine Analyse-Tools, bindet keine Schriften,
        Karten, Social-Media-Widgets oder Werbung von Dritten ein und kennt keine Benutzerkonten.
        Nichts von dem, was du hier tust, wird getrackt oder zu einem Profil verarbeitet. Übrig
        bleibt das unvermeidbare Minimum: Ein Webserver protokolliert Anfragen, damit die Seite
        ausgeliefert und geschützt werden kann, und dein Browser merkt sich, welche Sprache und
        welche Darstellung du gewählt hast.
      </p>
      <p>
        <strong>Auch die Bilder kommen von uns.</strong> Event Junkie lädt sie einmal herunter und
        liefert sie selbst aus, sodass dein Browser beim Ansehen einer Seite keine Anfrage an Dritte
        stellt. Abschnitt 5 sagt, was daraus folgt.
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
        <strong>Rechtsgrundlage:</strong> Art. 6 Abs. 1 lit. f DSGVO. Unser berechtigtes Interesse
        ist der Betrieb einer funktionierenden und sicheren Website. Erwägungsgrund 49 DSGVO erkennt
        dieses Interesse ausdrücklich an. Wir nutzen diese Daten nicht, um dich zu identifizieren
        oder ein Profil zu bilden, und führen sie mit keinen anderen Daten zusammen.
      </p>
      <p>
        <strong>Speicherdauer:</strong> Logdaten werden <strong>14 Tage</strong> aufbewahrt und
        danach automatisch gelöscht. Zwei unabhängige Mechanismen begrenzen sie, und meist greift
        der kürzere: Auf dem Server selbst werden Einträge nach Volumen verdrängt. Dort wird eine
        feste Menge vorgehalten und der jeweils älteste Eintrag überschrieben, sodass eine Zeile oft
        früher verschwindet. Im Log-Speicher wird unabhängig davon alles gelöscht, was älter als 14
        Tage ist. Ausgenommen sind Einträge, die wegen eines konkreten Sicherheitsvorfalls länger
        aufbewahrt werden müssen. Genannt wird hier immer die tatsächlich konfigurierte Frist.
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
        Diese Seite setzt <strong>keine Cookies</strong>. Sie speichert genau zwei Werte im lokalen
        Speicher deines Browsers, und beide halten nur fest, was du selbst eingestellt hast:
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
      </ul>
      <p>
        Diese Werte verlassen dein Gerät nicht, werden weder an uns noch an Dritte übermittelt und
        sind für Dritte nicht auslesbar. Beide sind unbedingt erforderlich, um die von dir
        ausdrücklich gewählte Einstellung bereitzustellen, und bedürfen deshalb nach § 25 Abs. 2 Nr.
        2 TDDDG keiner Einwilligung. Deshalb musst du hier auch kein Banner wegklicken. Du kannst
        sie jederzeit über die Einstellungen deines Browsers löschen.
      </p>
      <p>
        Sollten jemals Cookies eingesetzt werden, dann ausschließlich unbedingt erforderliche: kein
        Tracking, keine Analyse-Tools, keine Social-Media-Plugins, keine Werbe-Tracker.
      </p>
    </section>

    <section>
      <!-- §7.3: Künstlernamen sind personenbezogene Daten, soweit die Künstlerin oder der Künstler
           eine natürliche Person ist. Das zu benennen und einen Weg zur Löschung anzubieten ist die
           wahrscheinlichste Beschwerde, die diese Seite je erreichen wird. -->
      <h2>4. Veranstaltungs-, Künstler- und Locationdaten</h2>
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
        Kurzbeschreibung aus der Wikipedia.
      </p>
      <p>
        <strong>Zweck:</strong> Information der Öffentlichkeit über öffentlich angekündigte
        kulturelle Veranstaltungen.
      </p>
      <p>
        <strong>Rechtsgrundlage:</strong> Art. 6 Abs. 1 lit. f DSGVO. Unser berechtigtes Interesse
        ist der Betrieb eines Veranstaltungskalenders. Die betroffenen Informationen wurden von den
        Veranstaltern bereits selbst veröffentlicht, und zwar ausdrücklich zur Bewerbung der
        Veranstaltung; sie beschränken sich auf diesen beruflichen Kontext. Wir speichern keine
        Kontaktdaten, keine Privatanschriften und nichts über das Privatleben.
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
        <strong>Bilder:</strong> sie stammen aus zwei Quellen. Locations, Veranstalter und
        Künstler*innen veröffentlichen Bilder, zu einzelnen Veranstaltungen und zu sich selbst.
        Fotos von Gebäuden der Locations stammen außerdem aus offenen Archiven wie Wikimedia Commons
        und Flickr, wo Fotograf*innen sie unter einer Lizenz veröffentlicht haben, die das erlaubt.
        Event Junkie lädt beides herunter und speichert eine Kopie auf eigenen Servern bei Hetzner,
        statt sie bei jedem Seitenaufruf erneut beim Anbieter anzufragen. Rechtsgrundlage ist Art. 6
        Abs. 1 lit. f DSGVO. Das berechtigte Interesse ist eine Auslieferung ohne Beteiligung
        Dritter und eine geringere Last für die Server der Locations. Die Kopie wird gelöscht,
        sobald die Location aussteigt oder Rechteinhaber*innen das verlangen, und danach nicht
        erneut geladen. Bei einem Archivfoto sind die Rechteinhaber*innen die Fotograf*innen und
        nicht die Location. Der Bildnachweis unter dem Foto nennt sie.
      </p>
      <p>
        <strong>Übersetzungen:</strong> Beschreibungen werden maschinell übersetzt, damit die Seite
        sie in beiden Sprachen zeigen kann. Dafür wird der Text an einen Dienstleister übermittelt,
        den Abschnitt 5 nennt. Eine maschinell übersetzte Beschreibung ist auf der
        Veranstaltungsseite als solche gekennzeichnet, und der Originaltext bleibt maßgeblich.
      </p>
      <p>
        <strong>Wenn du Künstler*in bist</strong> und möchtest, dass dein Name oder eine
        Beschreibung, die dich nennt, entfernt oder korrigiert wird, schreib an
        <a :href="`mailto:${CONTROLLER.email}`">{{ CONTROLLER.email }}</a
        >. Wir kümmern uns umgehend darum. Eine Begründung brauchst du nicht, und du musst das Thema
        nicht öffentlich ansprechen.
      </p>
    </section>

    <section>
      <h2>5. Wer sonst beteiligt ist</h2>
      <p>
        Wir verkaufen nichts und geben nichts zu Werbezwecken weiter. Daten werden an zwei
        Dienstleister übermittelt, beide Auftragsverarbeiter mit einem Vertrag nach Art. 28 DSGVO:
      </p>
      <ul>
        <li>
          <strong>Hosting:</strong> Hetzner Online GmbH, Gunzenhausen, Deutschland. Die Server
          stehen in Deutschland; eine Übermittlung außerhalb der EU findet dabei nicht statt.
        </li>
        <li>
          <strong>Maschinelle Übersetzung:</strong> Anthropic PBC, San Francisco, USA. Dorthin wird
          der Beschreibungstext einer Veranstaltung übermittelt, damit er übersetzt werden kann.
          Nennt eine Beschreibung Künstler*innen, werden diese Namen mit übermittelt; die Namen des
          Line-ups und der Location werden zusätzlich ausdrücklich mitgesendet, damit die
          Übersetzung sie unverändert lässt. Andere Daten erreichen diesen Dienst nicht: weder
          Logdaten noch irgendetwas über dich.
        </li>
      </ul>
      <p>
        <strong>Übermittlung in die USA.</strong> Anthropic verarbeitet diese Texte ausschließlich
        nach unserer Weisung. Grundlage ist der Data Processing Addendum, der über die Commercial
        Terms of Service Bestandteil des Vertrags ist und die Standardvertragsklauseln der
        EU-Kommission einschließt (Art. 46 Abs. 2 lit. c DSGVO). Derselbe Vertrag schließt aus, dass
        die übermittelten Texte zum Training von Modellen verwendet werden.
      </p>
      <p>
        Es gibt kein Content-Delivery-Netzwerk, keinen Edge-Anbieter und keinen Proxy vor der Seite.
        Deine Anfrage erreicht unsere Server in Deutschland direkt. An der Auslieferung dieser Seite
        ist damit kein Drittland beteiligt. Die Übersetzung oben ist der einzige Weg, auf dem Daten
        die EU verlassen, und sie betrifft Veranstaltungstexte statt deines Besuchs.
      </p>
      <p>
        <strong>Für Bilder gilt das ebenso.</strong> Sie kommen von unseren Servern, weil Event
        Junkie sie einmal herunterlädt und selbst ausliefert (Abschnitt 4). Dein Browser fragt
        deshalb keine Location, keinen Veranstalter und keinen Ticketanbieter an, und keiner von
        ihnen erfährt beim Ansehen einer Seite deine IP-Adresse. Wo wir ein Bild nicht speichern
        dürfen, zeigen wir gar keines, statt es beim Anbieter zu laden.
      </p>
      <p>
        Wenn du uns per E-Mail schreibst oder ein Issue auf GitHub eröffnest, werden diese Daten zur
        Bearbeitung deines Anliegens verarbeitet (Art. 6 Abs. 1 lit. f DSGVO, bei Vertragsbezug Art.
        6 Abs. 1 lit. b DSGVO). GitHub wird von einem US-Unternehmen betrieben; ob du dort statt per
        E-Mail schreibst, entscheidest du.
      </p>
      <p>
        <strong>Speicherdauer:</strong> Wir bewahren eine E-Mail auf, bis dein Anliegen erledigt
        ist, und löschen sie <strong>spätestens ein Jahr danach</strong>. Das Jahr deckt Rückfragen
        zum selben Anliegen ab.
      </p>
    </section>

    <section>
      <h2>6. Deine Rechte</h2>
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
        eingespielt werden, wenden wir die Löschung danach erneut an.
      </p>
      <p>
        <strong>Widerspruchsrecht (Art. 21 DSGVO).</strong> Da wir uns auf berechtigte Interessen
        nach Art. 6 Abs. 1 lit. f DSGVO stützen, hast du das Recht, jederzeit aus Gründen, die sich
        aus deiner besonderen Situation ergeben, gegen die Verarbeitung deiner personenbezogenen
        Daten Widerspruch einzulegen. Legst du Widerspruch ein, verarbeiten wir die betroffenen
        Daten nicht mehr, es sei denn, wir können zwingende schutzwürdige Gründe nachweisen, die
        deine Interessen, Rechte und Freiheiten überwiegen. Ein Widerspruch ist kostenlos und
        formlos. Eine E-Mail genügt.
      </p>
      <p>
        <strong>Beschwerderecht.</strong> Du kannst dich bei einer Aufsichtsbehörde beschweren,
        insbesondere im Mitgliedstaat deines gewöhnlichen Aufenthaltsorts, deines Arbeitsplatzes
        oder des Orts des mutmaßlichen Verstoßes. Für uns zuständig ist die
        <a :href="SUPERVISORY_AUTHORITY.url" rel="noopener" target="_blank">
          {{ SUPERVISORY_AUTHORITY.name }}</a
        >.
      </p>
    </section>

    <section>
      <h2>7. Bist du verpflichtet, Daten bereitzustellen?</h2>
      <p>
        Du bist weder gesetzlich noch vertraglich verpflichtet, personenbezogene Daten
        bereitzustellen. Technisch lässt sich eine Webseite allerdings nicht ohne die IP-Adresse
        deines Geräts ausliefern. Diese Seite zu nutzen, ohne dass sie verarbeitet wird, ist deshalb
        nicht möglich. Alles Weitere ist freiwillig.
      </p>
    </section>

    <section>
      <h2>8. Automatisierte Entscheidungsfindung</h2>
      <p>
        Findet nicht statt. Wir setzen keine automatisierte Entscheidungsfindung und kein Profiling
        im Sinne von Art. 22 DSGVO ein.
      </p>
    </section>

    <section>
      <h2>9. Änderungen dieser Erklärung</h2>
      <p>
        Diese Erklärung beschreibt, was das System tatsächlich tut. Sie ändert sich also, wenn das
        System sich ändert. Das Prüfdatum oben auf dieser Seite sagt dir, wann sie zuletzt
        abgeglichen wurde.
      </p>
    </section>
  </LegalPage>
</template>
