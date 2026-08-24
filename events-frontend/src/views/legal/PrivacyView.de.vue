<script lang="ts" setup>
/**
 * Datenschutzerklärung nach Art. 13 DSGVO — die **maßgebliche Fassung** (LEGAL.md
 * §6.1).
 *
 * Follows the same twelve mandatory items as `PrivacyView.en.vue` (docs/LEGAL.md
 * §7.2), section for section, and cites them in German form — *Art. 6 Abs. 1 lit. f DSGVO*, not
 * *Art. 6 (1) (f) GDPR*. The unit test runs the checklist against both languages separately for
 * exactly that reason: a dropped element in one version is invisible from the other.
 *
 * Like the English version it deliberately does **not** describe processing that does not happen:
 * no cookie table, no consent withdrawal, no analytics section. Retention and the IP question are
 * still open (§7.5.1) and are stated as intent rather than invented.
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
    intro="Welche Daten Event Junkie verarbeitet, wozu — und was du dagegen tun kannst."
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
        ausgeliefert und geschützt werden kann, und dein Browser merkt sich, ob du helle oder dunkle
        Darstellung gewählt hast.
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
        Datenmenge, die zuvor besuchte Seite sowie Angaben zu Browser und Betriebssystem. Je nach
        endgültiger Infrastruktur kann auch deine IP-Adresse protokolliert werden.
      </p>
      <p>
        <strong>Zweck:</strong> Auslieferung der Seite, Aufrechterhaltung von Verfügbarkeit und
        Sicherheit, Abwehr von Angriffen und Missbrauch sowie Fehlerdiagnose.
      </p>
      <p>
        <strong>Rechtsgrundlage:</strong> Art. 6 Abs. 1 lit. f DSGVO. Unser berechtigtes Interesse
        ist der Betrieb einer funktionierenden und sicheren Website — ein Interesse, das
        Erwägungsgrund 49 DSGVO ausdrücklich anerkennt. Wir nutzen diese Daten nicht, um dich zu
        identifizieren oder ein Profil zu bilden, und führen sie mit keinen anderen Daten zusammen.
      </p>
      <p>
        <strong>Speicherdauer:</strong> Logdaten sollen nach sieben Tagen gelöscht werden;
        ausgenommen sind Einträge, die wegen eines konkreten Sicherheitsvorfalls länger aufbewahrt
        werden müssen. Diese Frist wird gemeinsam mit dem Hosting-Setup festgelegt und diese Seite
        entsprechend angepasst — genannt wird hier immer die tatsächlich konfigurierte Frist.
      </p>
      <p>
        <strong>Sicherungskopien:</strong> Die Datenbank wird gesichert. Diese Sicherungen werden im
        Regelfall <strong>30 Tage</strong> aufbewahrt und danach automatisch gelöscht;
        <strong>spätestens nach 35 Tagen</strong> werden sie in jedem Fall gelöscht. Die beiden
        Angaben unterscheiden sich, weil zwei voneinander unabhängige Mechanismen löschen: die
        tägliche Bereinigung auf dem Server, die eine Sicherung so lange behält, wie sie zur
        Wiederherstellung der übrigen benötigt wird, und eine Regel des Speicherdienstes, die
        unabhängig davon greift — auch dann, wenn der Server ausgefallen ist. Diese Frist ist von der
        oben genannten unabhängig: Daten, die im laufenden Betrieb bereits gelöscht wurden, können in
        einer Sicherung noch bis zu 35 Tage fortbestehen. Sicherungen dienen ausschließlich der
        Wiederherstellung nach einem Ausfall — sie werden nicht durchsucht und nicht ausgewertet.
      </p>
    </section>

    <section>
      <h2>3. Lokale Speicherung auf deinem Gerät</h2>
      <p>
        Diese Seite setzt <strong>keine Cookies</strong>. Sie speichert genau einen Wert im lokalen
        Speicher deines Browsers: einen Schlüssel namens <code>theme</code> mit dem Wert
        <code>light</code> oder <code>dark</code>, damit deine Darstellungseinstellung ein Neuladen
        der Seite übersteht.
      </p>
      <p>
        Dieser Wert verlässt dein Gerät nicht, wird weder an uns noch an Dritte übermittelt und ist
        für Dritte nicht auslesbar. Er ist unbedingt erforderlich, um die von dir ausdrücklich
        gewählte Darstellung bereitzustellen, und bedarf deshalb nach § 25 Abs. 2 Nr. 2 TDDDG keiner
        Einwilligung — weshalb du hier auch kein Banner wegklicken musst. Du kannst ihn jederzeit
        über die Einstellungen deines Browsers löschen.
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
        zusammen. Soweit eine auftretende Künstlerin oder ein auftretender Künstler eine natürliche
        Person ist, sind der Name und die Platzierung im Line-up personenbezogene Daten.
      </p>
      <p>
        <strong>Zweck:</strong> Information der Öffentlichkeit über öffentlich angekündigte
        kulturelle Veranstaltungen.
      </p>
      <p>
        <strong>Rechtsgrundlage:</strong> Art. 6 Abs. 1 lit. f DSGVO. Unser berechtigtes Interesse
        ist der Betrieb eines Veranstaltungskalenders. Die betroffenen Informationen wurden von den
        Veranstaltern bereits selbst veröffentlicht, und zwar ausdrücklich zur Bewerbung der
        Veranstaltung; sie beschränken sich auf diesen beruflichen Kontext — wir speichern keine
        Kontaktdaten, keine Privatanschriften und nichts über das Privatleben.
      </p>
      <p>
        <strong>Wenn du Künstlerin oder Künstler bist</strong> und möchtest, dass dein Name entfernt
        oder korrigiert wird, schreib an
        <a :href="`mailto:${CONTROLLER.email}`">{{ CONTROLLER.email }}</a>
        — wir kümmern uns umgehend darum. Eine Begründung brauchst du nicht, und du musst das Thema
        nicht öffentlich ansprechen.
      </p>
    </section>

    <section>
      <h2>5. Wer sonst beteiligt ist</h2>
      <p>
        Wir verkaufen nichts und geben nichts zu Werbezwecken weiter. Daten werden an genau einen
        Dienstleister übermittelt — Auftragsverarbeiter mit einem Vertrag nach Art. 28 DSGVO:
      </p>
      <ul>
        <li>
          <strong>Hosting</strong> — Hetzner Online GmbH, Gunzenhausen, Deutschland. Die Server
          stehen in Deutschland; eine Übermittlung außerhalb der EU findet dabei nicht statt.
        </li>
      </ul>
      <p>
        Es gibt kein Content-Delivery-Netzwerk, keinen Edge-Anbieter und keinen Proxy vor der Seite.
        Deine Anfrage erreicht unsere Server in Deutschland direkt. An der Auslieferung dieser Seite
        ist damit kein Drittland beteiligt, und es gibt keinen Übermittlungsmechanismus, den wir
        offenlegen müssten.
      </p>
      <p>
        Wenn du uns per E-Mail schreibst oder ein Issue auf GitHub eröffnest, werden diese Daten zur
        Bearbeitung deines Anliegens verarbeitet (Art. 6 Abs. 1 lit. f DSGVO, bei Vertragsbezug Art.
        6 Abs. 1 lit. b DSGVO). GitHub wird von einem US-Unternehmen betrieben; ob du dort statt per
        E-Mail schreibst, entscheidest du.
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
        <li>
          eine Einwilligung jederzeit zu widerrufen, soweit die Verarbeitung auf einer Einwilligung
          beruht (Art. 7 Abs. 3 DSGVO)
        </li>
      </ul>
      <p>
        <strong>Löschung und Sicherungskopien.</strong> Eine Löschung nach Art. 17 DSGVO wirkt
        sofort im laufenden Betrieb. Sicherungskopien werden dabei nicht einzeln bearbeitet — eine
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
        formlos — eine E-Mail genügt.
      </p>
      <p>
        <strong>Beschwerderecht.</strong> Du kannst dich bei einer Aufsichtsbehörde beschweren,
        insbesondere im Mitgliedstaat deines Aufenthaltsorts oder deines Arbeitsplatzes. Für uns
        zuständig ist die
        <a :href="SUPERVISORY_AUTHORITY.url" rel="noopener" target="_blank">
          {{ SUPERVISORY_AUTHORITY.name }} </a
        >.
      </p>
    </section>

    <section>
      <h2>7. Bist du verpflichtet, Daten bereitzustellen?</h2>
      <p>
        Du bist weder gesetzlich noch vertraglich verpflichtet, personenbezogene Daten
        bereitzustellen. Technisch lässt sich eine Webseite allerdings nicht ohne die IP-Adresse
        deines Geräts ausliefern — diese Seite zu nutzen, ohne dass sie verarbeitet wird, ist
        deshalb nicht möglich. Alles Weitere ist freiwillig.
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
        Diese Erklärung beschreibt, was das System tatsächlich tut — sie ändert sich also, wenn das
        System sich ändert. Das Prüfdatum oben auf dieser Seite sagt dir, wann sie zuletzt
        abgeglichen wurde.
      </p>
    </section>
  </LegalPage>
</template>
