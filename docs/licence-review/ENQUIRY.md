# Asking the venues

The mail that turns an `UNCLEAR` into an answer, and the first batch it goes to
([#808](https://github.com/enorm-labs/event-junkie/issues/808)). [README.md](README.md) beside this
file is what the venues published. This is what we ask them.

## The short version

1. **The ask is administrative, not promotional.** § 7 UWG limits unsolicited email to German
   businesses, and the bar covers business to business. A licence enquiry is arguably not
   advertising. A mail that also says "here is your page, come and look" reads as a pitch. That
   reading is the risk. The template below asks two questions and offers nothing.
2. **Two questions, asked separately.** Descriptions and images are different rights and the answers
   differ. `description_licence` and `image_licence` are separate columns for that reason.
3. **The mail states what happens without a reply.** Silence keeps the listing exactly as it is
   today. That is the fail-open rule from [#283](https://github.com/enorm-labs/event-junkie/issues/283),
   and hiding it would make the question dishonest.
4. **No contact address is in this repository.** [LEGAL.md](../LEGAL.md) §7.3a says no email
   addresses are stored anywhere, and that scope is what the Hetzner AVV of 2026-08-19 declares. The
   addresses and the replies stay in the mailbox and in a local file.
5. **Twelve first, then a decision.** The point of a first batch is the yes, no and silence ratio.
   All 86 at once spends the whole audience before we know what the mail achieves.
6. **The batch links the live site.** The mail links the venue's own page on `event-junkie.de`,
   which is public since 2026-09-24. A link that fails makes the ask both weak and
   promotional-looking, which is the one reading § 7 UWG punishes. Check each link before a mail
   goes out. [#808](https://github.com/enorm-labs/event-junkie/issues/808) tracks the sending.

## 1. The German template

Placeholders in `{{ }}`, and `{{slug}}` is the source slug. Nothing else changes between venues. The
mail links `/legal/for-venues`, which is the page that already states the opt-out from our side.

**The mails say "ich", not "wir", and they are signed with a name.** One person runs this project, so
the plural would be a costume. It also helps the § 7 UWG reading. A named private
person asking about their own non-commercial project is harder to read as a company's advertising. The name matches the
imprint the mail links, which is the first thing a careful recipient checks.

<!-- ste-lint: allow German mail text, scanned as English prose it is not -->

```text
Betreff: Nutzung Ihrer Veranstaltungstexte auf event-junkie.de - kurze Rueckfrage

Sehr geehrte Damen und Herren,

ich betreibe event-junkie.de, einen nicht-kommerziellen Veranstaltungskalender
fuer Berlin. Die Seite listet Konzerte und Veranstaltungen und verlinkt fuer
Tickets und Details immer auf die Seite des Veranstaltungsortes.

Ihr Haus ({{Venue}}) ist dort gelistet:
https://event-junkie.de/de/venues/{{slug}}

Zu jeder Veranstaltung zeige ich Titel, Datum, Uhrzeit, den Ort sowie einen
Auszug Ihrer Beschreibung, jeweils mit Link auf Ihre Seite. Wo eine Beschreibung
maschinell uebersetzt wurde, ist das an der Veranstaltung gekennzeichnet.

Ich moechte zwei Dinge klaeren, und zwar getrennt:

1. Beschreibungstexte: Darf ich Ihre Veranstaltungsbeschreibungen weiterhin
   auszugsweise und mit Quellenangabe anzeigen, und maschinell ins Englische
   uebersetzen?

2. Bilder: Darf ich Ihre Veranstaltungsbilder anzeigen? Falls die Rechte bei
   Agenturen oder Fotografinnen und Fotografen liegen, genuegt mir diese
   Auskunft.

Ein Ja, ein Nein oder ein Nein nur fuer einen der beiden Punkte sind mir
gleichermassen willkommen. Ohne Antwort aendert sich nichts: die Seite bleibt so
bestehen, wie sie heute ist.

Sie koennen die Anzeige jederzeit ganz oder teilweise widerrufen, auch spaeter
und ohne Begruendung. Eine formlose Mail an diese Adresse genuegt, und ich
entferne die betreffenden Inhalte am selben Tag.

Ihre Antwort verbleibt in meinem Postfach. Ich speichere Ihre Adresse in keinem
System.

Mit freundlichen Gruessen
Norman Lange

event-junkie.de
Informationen fuer Veranstalter: https://event-junkie.de/de/legal/for-venues
Impressum: https://event-junkie.de/de/legal/imprint
```

**On the wording that matters.** `nicht-kommerziell` is a fact about the project and it is what makes
the ask credible. The sentence about the machine translation is there because the site does that
today. A venue that objects to the translation alone can say so, and
[#805](https://github.com/enorm-labs/event-junkie/issues/805) is the narrower remedy that answer
produces.

## 2. The English variant

For venues that publish in English. Same two questions, same offer of nothing.

<!-- ste-lint: allow mail text, quoted rather than written as documentation -->

```text
Subject: Using your event texts on event-junkie.de - a short question

Hello,

I run event-junkie.de, a non-commercial event calendar for Berlin. The site
lists concerts and events, and always links to the venue's own page for tickets
and details.

Your venue ({{Venue}}) is listed here:
https://event-junkie.de/en/venues/{{slug}}

For each event I show the title, date, time, venue and an excerpt of your
description, each with a link to your page. Where a description was machine
translated, the event says so.

Two questions, and they are separate:

1. Descriptions: may I continue to show excerpts of your event descriptions
   with a link to the source, and machine translate them into German?

2. Images: may I show your event images? If the rights sit with an agency or a
   photographer, telling me that is answer enough.

Yes, no, or no to one of the two are equally welcome. Without an answer nothing
changes, and the listing stays as it is today.

You can withdraw all or part of this at any time, later and without a reason. An
informal mail to this address is enough, and I remove the content the same day.

Your reply stays in my mailbox. I store your address in no system.

Kind regards
Norman Lange

event-junkie.de
Information for venues: https://event-junkie.de/en/legal/for-venues
Imprint: https://event-junkie.de/en/legal/imprint
```

## 3. The two venues that require prior approval

Der Weiße Hase and Kulturhaus Peter Edel both write that the use of texts, parts of texts and images
needs their prior approval. That is why they are `PROHIBITED` rather than `UNCLEAR`. Their own
wording names the material, and it carries none of the statutory carve-out the common boilerplate
has.

**They get a mail of their own, because their answer is the only thing that can change anything.**
For them the enquiry is not "may we keep doing this". It is a request for the approval their terms
ask for.

**What we do for them today, and the mail says it first.** `PROHIBITED` withholds at import rather
than at display ([#807](https://github.com/enorm-labs/event-junkie/issues/807)). Nothing of theirs
is stored: 0 descriptions and 0 image URLs across their 54 events on production. Their listings carry
the title, the date, the time, the venue and a link.

<!-- ste-lint: allow German mail text, scanned as English prose it is not -->

```text
Betreff: Anfrage auf Zustimmung - Ihre Veranstaltungstexte auf event-junkie.de

Sehr geehrte Damen und Herren,

wir betreiben event-junkie.de, einen nicht-kommerziellen Veranstaltungskalender
fuer Berlin. Die Seite listet Konzerte und Veranstaltungen und verlinkt fuer
Tickets und Details immer auf die Seite des Veranstaltungsortes.

Ihr Impressum verlangt fuer die Verwendung von Texten, Textteilen und
Bildmaterial Ihre vorherige Zustimmung. Daran halte ich mich: von Ihrer Seite
ist bei mir kein Beschreibungstext und kein Bild gespeichert. Ihre
Veranstaltungen erscheinen ausschliesslich mit Titel, Datum, Uhrzeit und Ort,
jeweils mit Link auf Ihre Seite:
https://event-junkie.de/de/venues/{{slug}}

Ich moechte Sie fragen, ob Sie diese Zustimmung erteilen moechten. Getrennt
nach den beiden Punkten, die Ihr Impressum nennt:

1. Beschreibungstexte: Duerfte ich Ihre Veranstaltungsbeschreibungen
   auszugsweise und mit Quellenangabe anzeigen, und maschinell ins Englische
   uebersetzen?

2. Bilder: Duerfte ich Ihre Veranstaltungsbilder anzeigen? Falls die Rechte bei
   Agenturen oder Fotografinnen und Fotografen liegen, genuegt mir diese
   Auskunft.

Ein Nein aendert nichts. Es bleibt beim heutigen Zustand, und ich frage nicht
erneut. Eine Zustimmung koennen Sie jederzeit formlos widerrufen, auch spaeter
und ohne Begruendung. Ich entferne die betreffenden Inhalte dann am selben Tag.

Ihre Antwort verbleibt in meinem Postfach. Ich speichere Ihre Adresse in keinem
System.

Mit freundlichen Gruessen
Norman Lange

event-junkie.de
Informationen fuer Veranstalter: https://event-junkie.de/de/legal/for-venues
Impressum: https://event-junkie.de/de/legal/imprint
```

<!-- ste-lint: allow mail text, quoted rather than written as documentation -->

```text
Subject: Request for approval - your event texts on event-junkie.de

Hello,

I run event-junkie.de, a non-commercial event calendar for Berlin. The site
lists concerts and events, and always links to the venue's own page for tickets
and details.

Your imprint requires your prior approval for the use of texts, parts of texts
and images. I follow that: I store no description and no image from your site.
Your events appear with the title, date, time and venue only, each with a link
to your page:
https://event-junkie.de/en/venues/{{slug}}

I would like to ask whether you want to grant that approval, separately for the
two things your imprint names:

1. Descriptions: may I show excerpts of your event descriptions with a link to
   the source, and machine translate them into German?

2. Images: may I show your event images? If the rights sit with an agency or a
   photographer, telling me that is answer enough.

A no changes nothing. Things stay exactly as they are today, and I will not ask
again. Approval can be withdrawn at any time, informally, later and without a
reason. I then remove the content the same day.

Your reply stays in my mailbox. I store your address in no system.

Kind regards
Norman Lange

event-junkie.de
Information for venues: https://event-junkie.de/en/legal/for-venues
Imprint: https://event-junkie.de/en/legal/imprint
```

## 4. The first batch of twelve

Chosen from the production event counts and the review in `RESULTS.tsv`. The mix is the point: a
ratio from ten clubs of one kind says less than a ratio across the kinds we actually list. The last
two are §3's, and they are here so the batch is one send rather than two.

| Venue                 | Events | Why this one                                                                                       |
| --------------------- | ------ | -------------------------------------------------------------------------------------------------- |
| Admiralspalast        | 229    | The largest listing we hold. Its licence page is an operator press area, so a media contact exists |
| Tempodrom             | 155    | Second largest, and a house that runs its own site and its own press work                          |
| SO36                  | 116    | Independent and self-run. The kind of venue most likely to answer a mail at all                    |
| Lido                  | 113    | A mid-size independent club, which is the shape of most of the corpus                              |
| Heimathafen Neukölln  | 112    | A cultural house rather than a club, and the sector most likely to have a press office             |
| Badehaus              | 107    | Small and independent, and the counterweight to the two arenas below                               |
| Uber Arena            | 96     | A corporate arena. The clearest test of whether a commercial operator answers at all               |
| silent green          | 60     | A cultural institution that publishes its own press material                                       |
| Bar jeder Vernunft    | 51     | Variety and theatre, a sector this batch would otherwise miss                                      |
| Cosmic Comedy Club    | 54     | English-language programming, and the one that gets the English variant                            |
| Der Weiße Hase        | 10     | Requires prior approval, so §3's mail. Its answer is the only thing that can change anything       |
| Kulturhaus Peter Edel | 44     | The same, and the larger of the two listings                                                       |

**Check the operator before you send.** Two of these may belong to the same company as another venue
we list. Two mails into one press office read as a campaign rather than as an enquiry. The review
already shows one such case: Admiralspalast's licence page is on `atgentertainment.de`. Confirm the
rest by hand, the way every other venue fact here is confirmed.

**The last two get §3's mail, not §1's.** Everything else about them is the same: one send, one
answer, one row written back.

## 5. Where the contacts and the replies live

**Outside this repository, and outside the application.** A contact list in the system would break
[LEGAL.md](../LEGAL.md) §7.3a and the Hetzner AVV scope with it. Keep a local file with the venue,
the address, the date sent and the answer. `temp/` is the natural place, because `.gitignore`
already covers it.

## 6. Writing an answer back

Only the verdict reaches the database, through the admin API. The reply itself is the evidence, and
it stays in the mailbox.

```sh
# Yes, to both questions.
curl -sS -X PATCH "$IMPORTER/api/admin/event-sources/<slug>" \
  -H 'Content-Type: application/json' \
  -d '{"descriptionLicence":"PERMITTED","imageLicence":"PERMITTED",
       "licenceSourceUrl":"<the reply, or the page it points at>",
       "licenceNote":"Mail reply from <venue>, <date>. Quote the sentence that grants it."}'

# No, to the images alone. Only the field they name.
curl -sS -X PATCH "$IMPORTER/api/admin/event-sources/<slug>" \
  -H 'Content-Type: application/json' \
  -d '{"imageLicence":"PROHIBITED","licenceNote":"Mail reply from <venue>, <date>."}'
```

**A `PROHIBITED` on `description_licence` clears the translation with it.** The translation is
derived from a text we then hold no justification for ([ADR-027](../adr/ADR-027_TRANSLATION_FOLLOWS_THE_DISPLAY_RULE.md)).

**Silence stays `UNCLEAR`.** It is not a refusal and it is not consent. Nothing is written back.

## 7. What the ratio decides

Record the twelve answers, then decide whether the remaining 74 get the same mail. Two outcomes make
that decision for us:

- **Several yes answers.** The mail works, and `SCRAPING_POSITION.md` §3.1 gets stronger with every
  one. Continue.
- **Several no answers.** Asking costs listings that nobody objected to before we asked. That is the
  argument #808 records against itself, and a bad ratio is when it wins.

Silence is the third outcome and it decides nothing. It leaves the position exactly where
`SCRAPING_POSITION.md` §3.1 already puts it.
