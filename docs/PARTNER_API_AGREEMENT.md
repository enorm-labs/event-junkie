# Partner API agreement

The contract template for a partner who receives event facts through the partner API. The partner API does not exist yet (#5337). No lawyer checked this template yet.

## The short version

- **German is the contract text.** This page holds the template only. A signed agreement never enters this repository, because it names a person or a company.
- **Fill each `[…]` placeholder per partner.** The fee is the standard price, 29 EUR a month after a free pilot of three months.
- **Before the first agreement is signed:**
    1. A lawyer reviews the template, with `docs/SCRAPING_POSITION.md` §6 (#788). § 8 (privacy) and § 9 (liability) need the most attention.
    2. The venues in Anlage 1 are checked against the opt-outs (`SCRAPING_POSITION.md` §5) and the `PROHIBITED` sources.
- **A change to the licensed fields changes three places together:** § 2 Abs. 1 here, the scope of #5337, and the route's response.
- **A partner signs a PDF by email** (§ 10 Abs. 1). Self-service sign-up waits for #402.

The template is reused for every partner, so a court reads it as standard terms (§§ 305 ff. BGB). That is a reason for the review, not a reason to change the wording per partner.

---

# Vertrag über die Nutzung von Veranstaltungsdaten über die Event-Junkie-Partner-API

zwischen

**Norman Lange**, [Anschrift], betreibt Event Junkie unter https://event-junkie.de
– im Folgenden „Lizenzgeber“ –

und

**[Name / Firma]**, [Anschrift], betreibt [Website]
– im Folgenden „Partner“ –

## § 1 Gegenstand

1. Der Lizenzgeber sammelt, prüft und pflegt Daten zu Veranstaltungen in Berlin. Die Sammlung ist eine Datenbank im Sinne der §§ 87a ff. UrhG.
2. Dieser Vertrag regelt den Zugang des Partners zu ausgewählten Veranstaltungsdaten über eine Partner-Schnittstelle („Partner-API“) und deren Nutzung.
3. Der Partner ist Unternehmer im Sinne des § 14 BGB.

## § 2 Lizenzierte Daten

1. Lizenziert sind ausschließlich folgende Felder:
    - Titel
    - Untertitel, sofern er höchstens 100 Zeichen lang ist. Längere Untertitel liefert die Partner-API nicht.
    - Datum und Beginn (Uhrzeit)
    - Status: findet statt, abgesagt oder verlegt, bei einer Verlegung mit neuem Ort oder neuem Datum, soweit bekannt
    - Name und Adresse der Location
    - Link zur Veranstaltung auf der Website der Location
    - Link zur Veranstaltung auf Event Junkie
2. Nicht lizenziert sind insbesondere Beschreibungstexte, Bilder, Genres, Line-ups, Preise und alle übrigen Daten von Event Junkie.
3. Die Daten umfassen nur die Locations aus Anlage 1. Der Lizenzgeber kann Locations jederzeit ohne Angabe von Gründen hinzufügen oder entfernen. Er informiert den Partner darüber in Textform.

## § 3 Zugang zur Partner-API

1. Der Partner erhält einen persönlichen API-Schlüssel. Er hält ihn geheim und gibt ihn nicht an Dritte weiter. Bei Verdacht auf Missbrauch informiert er den Lizenzgeber unverzüglich.
2. Die technischen Einzelheiten, insbesondere Endpunkt, Datenformat und Abrufgrenzen, stehen in Anlage 2. Der Partner hält die Abrufgrenzen ein.
3. Der Lizenzgeber schuldet keine bestimmte Verfügbarkeit. Er kann die Partner-API ändern. Wesentliche Änderungen kündigt er mindestens [14] Tage vorher in Textform an.
4. Der Lizenzgeber kann den Schlüssel sperren, wenn der Partner gegen diesen Vertrag verstößt oder die Sicherheit der Systeme gefährdet ist.

## § 4 Nutzungsrecht

<!-- ste-lint: allow German contract clause, the legal wording stays whole -->

1. Der Lizenzgeber räumt dem Partner für die Laufzeit dieses Vertrags ein einfaches, nicht übertragbares und nicht unterlizenzierbares Recht ein, die Daten nach § 2 auf der Website [Domain] anzuzeigen.
2. Jede andere Nutzung bedarf der vorherigen Zustimmung des Lizenzgebers in Textform. Das gilt insbesondere für Apps, Newsletter, Feeds, Exporte und andere Websites.
3. Gesetzlich erlaubte Nutzungen bleiben unberührt.

## § 5 Pflichten des Partners

1. **Link zur Location.** Bei jeder angezeigten Veranstaltung verlinkt der Partner die Veranstaltungsseite der Location, soweit die Daten einen solchen Link enthalten. Der Link ist sichtbar und führt direkt zur Veranstaltung.

<!-- ste-lint: allow German contract clause, the legal wording stays whole -->

2. **Quellenangabe.** Der Partner nennt Event Junkie als Quelle und verlinkt Event Junkie. Er wählt eine der beiden Formen aus Anlage 3: einen Link bei jeder Veranstaltung oder eine sichtbare Quellenzeile auf jeder Seite, die Daten nach § 2 anzeigt.
3. **Link-Attribute.** Der Partner kann die Links zu Event Junkie mit `rel="nofollow"` oder `rel="sponsored"` kennzeichnen. Der Lizenzgeber verlangt keinen Link, der Suchmaschinen eine Empfehlung signalisiert.

<!-- ste-lint: allow German contract clause, the legal wording stays whole -->

4. **Keine Weitergabe.** Der Partner gibt die Daten nicht an Dritte weiter, verkauft sie nicht und macht sie nicht als Datensatz zugänglich, etwa über eine eigene Schnittstelle oder einen Download.
5. **Aktualität.** Der Partner ruft die Daten mindestens [einmal täglich] ab. Abgesagte, geänderte oder nicht mehr gelieferte Veranstaltungen ändert oder entfernt er spätestens beim nächsten Abruf.
6. **Keine Verfälschung.** Der Partner ändert die Daten nicht so, dass sie einen falschen Eindruck erwecken. Er erweckt nicht den Eindruck, Event Junkie oder eine Location empfehle sein Angebot.

## § 6 Entfernung auf Verlangen

1. Widerspricht eine Location der Weitergabe ihrer Daten, entfernt der Lizenzgeber sie aus der Partner-API.
2. Der Partner entfernt die Daten dieser Location innerhalb von [3] Werktagen, nachdem der Lizenzgeber ihn in Textform informiert hat. Das gilt auch für Daten, die er bereits gespeichert oder angezeigt hat.
3. Dasselbe gilt, wenn eine betroffene Person der Anzeige ihres Namens widerspricht (§ 8).

## § 7 Vergütung

1. **Testphase.** Die ersten drei Monate ab Vertragsbeginn sind kostenlos („Testphase“).
2. Nach der Testphase zahlt der Partner eine monatliche Vergütung von 29 EUR. Gemäß § 19 UStG wird keine Umsatzsteuer berechnet. Der Lizenzgeber erinnert den Partner mindestens 14 Tage vor Ende der Testphase in Textform daran. Kündigt der Partner bis zum Ende der Testphase, entsteht keine Vergütung.
3. Der Lizenzgeber stellt die Vergütung monatlich im Voraus in Rechnung. Sie ist innerhalb von 14 Tagen nach Rechnungszugang fällig.
4. Die Vergütung gilt für den Umfang aus Anlage 1 und Anlage 2. Eine Änderung der Vergütung kündigt der Lizenzgeber mindestens [ein Monat] vorher in Textform an. Der Partner kann den Vertrag dann zum Zeitpunkt der Änderung kündigen.
5. Endet der Vertrag während eines Monats, erstattet der Lizenzgeber die bereits gezahlte Vergütung anteilig.

## § 8 Datenschutz

1. Titel können Namen von Künstlerinnen und Künstlern enthalten. Das sind personenbezogene Daten.
2. Jede Partei ist für ihre eigene Verarbeitung allein verantwortlich. Der Partner verarbeitet die Daten nicht im Auftrag des Lizenzgebers.
3. Der Partner verwendet die Daten nur für die Anzeige nach § 4. Er verwendet sie nicht für Profile von Personen, für Werbung an diese Personen oder für das Training von KI-Modellen.
4. Der Partner nennt die Herkunft der Daten in seiner Datenschutzerklärung, soweit das gesetzlich erforderlich ist.
5. Die Parteien unterstützen sich gegenseitig bei Anfragen betroffener Personen, soweit diese die übermittelten Daten betreffen.

## § 9 Gewährleistung und Haftung

1. Die Daten stammen überwiegend von den Websites der Locations. Der Lizenzgeber prüft sie sorgfältig, übernimmt aber keine Gewähr für Richtigkeit, Vollständigkeit und Aktualität.
2. Der Lizenzgeber haftet unbeschränkt bei Vorsatz und grober Fahrlässigkeit, bei Verletzung von Leben, Körper oder Gesundheit und nach dem Produkthaftungsgesetz.
3. Bei leichter Fahrlässigkeit haftet der Lizenzgeber nur für die Verletzung wesentlicher Vertragspflichten. Die Haftung ist dann auf den vertragstypischen, vorhersehbaren Schaden begrenzt, höchstens auf die Vergütung der letzten zwölf Monate.
4. Während der Testphase haftet der Lizenzgeber nur nach Absatz 2.
5. Der Partner stellt den Lizenzgeber von Ansprüchen Dritter frei, die auf einem Verstoß des Partners gegen diesen Vertrag beruhen. Das umfasst die angemessenen Kosten der Rechtsverteidigung.

## § 10 Laufzeit und Kündigung

1. Der Vertrag beginnt, sobald beide Parteien ihn unterzeichnet haben. Eine elektronische Unterschrift genügt, auch ein unterschriebenes PDF per E-Mail. Der Vertrag läuft auf unbestimmte Zeit.
2. Jede Partei kann den Vertrag jederzeit mit einer Frist von [14 Tagen] in Textform kündigen.

<!-- ste-lint: allow German contract clause, the legal wording stays whole -->

3. Das Recht zur fristlosen Kündigung aus wichtigem Grund bleibt unberührt. Ein wichtiger Grund liegt insbesondere vor, wenn der Partner trotz Hinweis gegen § 3, § 4, § 5 oder § 6 verstößt.
4. Mit Vertragsende sperrt der Lizenzgeber den API-Schlüssel. Der Partner löscht alle über die Partner-API erhaltenen Daten innerhalb von [7] Tagen und entfernt sie von seiner Website. Auf Verlangen bestätigt er die Löschung in Textform.

## § 11 Schlussbestimmungen

1. Änderungen und Ergänzungen dieses Vertrags bedürfen der Textform. Das gilt auch für die Änderung dieser Klausel.
2. Allgemeine Geschäftsbedingungen des Partners gelten nicht.
3. Es gilt das Recht der Bundesrepublik Deutschland.
4. Gerichtsstand ist Berlin, soweit gesetzlich zulässig.
5. Ist eine Bestimmung unwirksam, bleibt der Vertrag im Übrigen wirksam. Die Parteien ersetzen die unwirksame Bestimmung durch eine wirksame, die ihrem Zweck am nächsten kommt.

## Unterschriften

| Lizenzgeber                 | Partner                   |
| --------------------------- | ------------------------- |
| Berlin, den ____________    | [Ort], den ____________   |
|                             |                           |
| ___________________________ | _________________________ |
| Norman Lange                | [Name]                    |

## Anlage 1 – Locations

| Location | Event-Junkie-Seite |
| -------- | ------------------ |
| […]      | […]                |

## Anlage 2 – Technische Einzelheiten

- Endpunkt: `https://event-junkie.de/api/partner/v1/events` [vorläufig]
- Authentifizierung: HTTP-Header `X-Api-Key`
- Format: JSON, Felder nach § 2 Abs. 1
- Zeitraum: Veranstaltungen ab heute bis [90] Tage im Voraus
- Abrufgrenze: höchstens [24] Abrufe pro Tag

## Anlage 3 – Quellenangabe

**Immer, bei jeder Veranstaltung (§ 5 Abs. 1):**

> [Zur Veranstaltung bei der Location](https://www.example.de/veranstaltung)

**Quellenangabe Event Junkie (§ 5 Abs. 2), eine der beiden Formen:**

- **Form A – bei jeder Veranstaltung:**

    > Quelle: [Event Junkie](https://event-junkie.de/…)

    Der Link führt zur Seite der Veranstaltung auf Event Junkie.

- **Form B – einmal pro Seite:**

    > Veranstaltungsdaten: [Event Junkie](https://event-junkie.de)

    Die Zeile steht sichtbar auf jeder Seite, die Daten nach § 2 anzeigt, in der Nähe der Veranstaltungen. Ein Eintrag nur im Impressum oder in der Fußzeile genügt nicht.

**Link-Attribute:** `rel="nofollow"` oder `rel="sponsored"` sind bei den Links zu Event Junkie erlaubt (§ 5 Abs. 3).
