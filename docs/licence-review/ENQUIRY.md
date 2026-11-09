# Asking the venues

The mail that turns an `UNCLEAR` into an answer, and the first batch it goes to
([#808](https://github.com/enorm-labs/event-junkie/issues/808)). [README.md](README.md) beside this
file is what the venues published. This is what we ask them.

## The short version

1. **The ask is administrative, not promotional.** § 7 UWG limits unsolicited email to German
   businesses, and the bar covers business to business. A licence enquiry is arguably not
   advertising. A mail that also says "here is your page, come and look" reads as a pitch. That
   reading is the risk. The template below asks three questions and offers nothing.
2. **Three questions, asked separately.** Descriptions, their machine translation and images are
   different rights, and the answers can differ. `description_licence`, `translation_licence` and
   `image_licence` are separate columns for that reason.
3. **The mail states what happens without a reply.** Silence keeps the listing exactly as it is
   today. That is the fail-open rule from [#283](https://github.com/enorm-labs/event-junkie/issues/283),
   and hiding it would make the question dishonest.
4. **No contact address is in this repository.** [LEGAL.md](../LEGAL.md) §7.3a lists mail to the
   role mailboxes as communication data, and the Hetzner AVV covers it. It lists no contact list. The
   addresses and the replies stay in the mailbox and in a local file.
5. **Eighteen mails first, then a decision.** The point of a first batch is the yes, no and silence
   ratio. Every source at once spends the whole audience before we know what the mail achieves.
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

**The mail says what the for-venues page says.** The site shows the whole description, not an
excerpt. It stores a copy of each image on its own servers. A removal is confirmed within seven days.
A mail that promises more or less than the page it links contradicts that page.

**Send the mails with umlauts.** A German mail written with "ae" and "ue" reads as a form letter
from a machine. Every mail client reads UTF-8.

<!-- ste-lint: allow German mail text, scanned as English prose it is not -->

```text
Betreff: Nutzung Ihrer Veranstaltungstexte auf event-junkie.de – kurze Rückfrage

Sehr geehrte Damen und Herren,

ich betreibe event-junkie.de, einen nicht-kommerziellen Veranstaltungskalender
für Berlin. Die Seite listet Konzerte und Veranstaltungen und verlinkt für
Tickets und Details immer auf die Seite des Veranstaltungsortes.

Ihr Haus ({{Venue}}) ist dort gelistet:
https://event-junkie.de/de/venues/{{slug}}

Zu jeder Veranstaltung zeige ich Titel, Datum, Uhrzeit und Ort, Ihren
Beschreibungstext und Ihr Veranstaltungsbild, jeweils mit Link auf Ihre Seite.
Wo eine Beschreibung maschinell übersetzt wurde, ist das an der Veranstaltung
gekennzeichnet.

Ich möchte drei Dinge klären, jedes für sich:

1. Beschreibungstexte: Darf ich Ihre Veranstaltungsbeschreibungen weiterhin
   mit Quellenangabe anzeigen?

2. Übersetzung: Darf ich diese Beschreibungen maschinell ins Englische
   übersetzen? Die Übersetzung ist als solche gekennzeichnet.

3. Bilder: Darf ich Ihre Veranstaltungsbilder anzeigen? Dafür speichere ich
   eine Kopie auf den Servern von event-junkie.de, damit Ihr Server sie nicht
   bei jedem Aufruf ausliefern muss. Falls die Rechte bei Agenturen oder
   Fotografinnen und Fotografen liegen, genügt mir diese Auskunft.

Ein Ja oder ein Nein, auch nur zu einzelnen Punkten, ist mir gleichermaßen
willkommen. Ohne Antwort ändert sich nichts: die Seite bleibt so bestehen, wie
sie heute ist.

Sie können die Anzeige jederzeit ganz oder teilweise widerrufen, auch später
und ohne Begründung. Eine formlose Mail an diese Adresse genügt. Ich entferne
die betreffenden Inhalte und bestätige Ihnen das innerhalb von sieben Tagen.

Ihre Adresse und Ihre Antwort bleiben in meinem Postfach. In die Datenbank der
Seite gelangt nur das Ergebnis, nicht Ihre Adresse.

Mit freundlichen Grüßen
Norman Lange

event-junkie.de
Informationen für Veranstalter: https://event-junkie.de/de/legal/for-venues
Impressum: https://event-junkie.de/de/legal/imprint
Datenschutz: https://event-junkie.de/de/legal/privacy
```

**On the wording that matters.** `nicht-kommerziell` is a fact about the project and it is what makes
the ask credible. A yes given to a non-commercial calendar is read as limited to one. If the project
becomes commercial, ask again before relying on it. The translation question is its own point because
the site translates today, and a venue may accept the text but not the translation.
[#805](https://github.com/enorm-labs/event-junkie/issues/805) is the narrower remedy that answer
produces.

## 2. The English variant

For venues that publish in English. Same three questions, same offer of nothing.

<!-- ste-lint: allow mail text, quoted rather than written as documentation -->

```text
Subject: Using your event texts on event-junkie.de – a short question

Hello,

I run event-junkie.de, a non-commercial event calendar for Berlin. The site
lists concerts and events, and always links to the venue's own page for tickets
and details.

Your venue ({{Venue}}) is listed here:
https://event-junkie.de/en/venues/{{slug}}

For each event I show the title, date, time and venue, your description and
your event image, each with a link to your page. Where a description was
machine translated, the event says so.

Three questions, each on its own:

1. Descriptions: may I continue to show your event descriptions with a link
   to the source?

2. Translation: may I machine translate these descriptions into German? The
   translation is marked as one.

3. Images: may I show your event images? To do that I keep a copy on the
   event-junkie.de servers, so that your server does not deliver them on every
   page view. If the rights sit with an agency or a photographer, telling me
   that is answer enough.

Yes or no, to all of them or to single points, are equally welcome. Without an
answer nothing changes, and the listing stays as it is today.

You can withdraw all or part of this at any time, later and without a reason.
An informal mail to this address is enough. I remove the content and confirm it
within seven days.

Your address and your reply stay in my mailbox. Only the verdict reaches the
site's database, never your address.

Kind regards
Norman Lange

event-junkie.de
Information for venues: https://event-junkie.de/en/legal/for-venues
Imprint: https://event-junkie.de/en/legal/imprint
Privacy: https://event-junkie.de/en/legal/privacy
```

## 3. The nine sources with a prohibition

`RESULTS.tsv` records nine sources as `PROHIBITED`. Eight prohibit descriptions and images. ZK/U
prohibits only its images. Each one's own wording restricts the material beyond copyright law, and
none carries the statutory carve-out the common boilerplate has. That is why they are `PROHIBITED`
rather than `UNCLEAR`.

| Source                                  | Descriptions | Images     | Where      | What it says                                                                                         |
| --------------------------------------- | ------------ | ---------- | ---------- | ---------------------------------------------------------------------------------------------------- |
| Der Weiße Hase                          | PROHIBITED   | PROHIBITED | Imprint    | The use of texts, parts of texts and images needs prior approval                                     |
| Kulturhaus Peter Edel                   | PROHIBITED   | PROHIBITED | Imprint    | The same clause                                                                                      |
| PANDA platforma                         | PROHIBITED   | PROHIBITED | Imprint    | Any use of its texts, images or other data needs express written approval                            |
| Speakeazy                               | PROHIBITED   | PROHIBITED | Disclaimer | The use of its graphics and texts needs express approval                                             |
| Surprise Club                           | PROHIBITED   | PROHIBITED | Imprint    | Any reproduction of the site or of parts of it is not permitted, in any medium                       |
| Renaissance-Theater                     | PROHIBITED   | PROHIBITED | Imprint    | Its graphics, photos and contributions are for use outside the site only with its written permission |
| Volksbühne Berlin and Volksbühne Prater | PROHIBITED   | PROHIBITED | Imprint    | Texts, graphics and images need prior consent to copy, distribute or store in databases              |
| ZK/U                                    | UNCLEAR      | PROHIBITED | Press page | The visual material of the website is for press coverage about ZK/U only                             |

**The two Volksbühne sources get one mail.** They share one site, one imprint and one operator. The
mail names both houses and links both venue pages, one line each.

**They get a mail of their own, because their answer is the only thing that can change anything.**
For them the enquiry is not "may we keep doing this". It is a request for the approval their terms
ask for.

**What we do for them today, and the mail says it first.** `PROHIBITED` withholds at import rather
than at display ([#807](https://github.com/enorm-labs/event-junkie/issues/807)). It withholds each
column on its own. Nothing of theirs is stored in a prohibited column: 0 descriptions and 0 image
URLs across the first two venues' 54 events on production. The other venues' events are stored the
same way. Their listings carry the title, the date, the time, the venue and a link. ZK/U's listings
also carry the description, because its texts are `UNCLEAR`.

**For PANDA platforma, change one sentence of the mail to quote its own terms.** Its imprint names
"Texte, Bilder oder andere Daten" and asks for written approval, not for prior approval of "Textteile
und Bildmaterial". The German mail then says "Ihr Impressum verlangt für die Nutzung von Texten und
Bildern Ihre ausdrückliche und schriftliche Zustimmung." The English mail says "Your imprint requires
your express written approval for the use of texts and images." The rest of the mail stays as it is.

**For Speakeazy, change the same sentence.** Its disclaimer names "Grafiken" and "Texte" and asks
for express approval. The German mail then says "Ihr Disclaimer verlangt für die Verwendung von
Texten und Grafiken Ihre ausdrückliche Zustimmung." The English mail says "Your disclaimer requires
your express approval for the use of texts and graphics."

**For Renaissance-Theater, change the same sentence.** Its imprint asks for written permission. The
German mail then says "Ihr Impressum erlaubt die Nutzung Ihrer Beiträge, Grafiken und Fotos
außerhalb Ihrer Seite nur mit Ihrer schriftlichen Genehmigung." The English mail says "Your imprint
allows the use of your contributions, graphics and photos outside your site only with your written
permission."

**For the Volksbühne, change the same sentence.** Its imprint asks for prior consent. The German
mail then says "Ihr Impressum verlangt Ihre vorherige Zustimmung, bevor Texte, Grafiken und Bilder
vervielfältigt, verbreitet oder in Datenbanken gespeichert werden." The English mail says "Your
imprint requires your prior consent before texts, graphics and images are copied, distributed or
stored in databases."

**For Surprise Club, change two sentences.** Its imprint asks for no approval. It forbids the
reproduction outright, and it does not name texts and images.

1. The German mail says "Ihr Impressum untersagt jede Reproduktion oder Wiedergabe Ihrer Seite oder
   von Teilen davon." The English mail says "Your imprint forbids any reproduction of your site or of
   parts of it."
2. The German question becomes "ob Sie dafür eine Ausnahme zulassen möchten", then "Getrennt nach
   Texten, Übersetzung und Bildern". The English question becomes "whether you want to make an
   exception", then "separately for texts, translation and images".

**ZK/U gets §1's mail, not this one.** Its texts are `UNCLEAR`, so §1's three questions fit it. Add
one paragraph after the venue link. The German mail says "Ihre Presseseite erlaubt die Nutzung der
Bilder Ihrer Website nur für Presseberichte über das ZK/U. Daran halte ich mich: von Ihrer Seite
ist bei mir kein Bild gespeichert." The English mail says "Your press page allows the use of the
images on your website only for press coverage about ZK/U. I follow that: I store no image from your
site." Question 3 then asks for an exception to that rule.

<!-- ste-lint: allow German mail text, scanned as English prose it is not -->

```text
Betreff: Anfrage auf Zustimmung – Ihre Veranstaltungstexte auf event-junkie.de

Sehr geehrte Damen und Herren,

ich betreibe event-junkie.de, einen nicht-kommerziellen Veranstaltungskalender
für Berlin. Die Seite listet Konzerte und Veranstaltungen und verlinkt für
Tickets und Details immer auf die Seite des Veranstaltungsortes.

Ihr Impressum verlangt für die Verwendung von Texten, Textteilen und
Bildmaterial Ihre vorherige Zustimmung. Daran halte ich mich: von Ihrer Seite
ist bei mir kein Beschreibungstext und kein Bild gespeichert. Ihre
Veranstaltungen erscheinen ausschließlich mit Titel, Datum, Uhrzeit und Ort,
jeweils mit Link auf Ihre Seite:
https://event-junkie.de/de/venues/{{slug}}

Ich möchte Sie fragen, ob Sie diese Zustimmung erteilen möchten, getrennt nach
drei Punkten:

1. Beschreibungstexte: Dürfte ich Ihre Veranstaltungsbeschreibungen mit
   Quellenangabe anzeigen?

2. Übersetzung: Dürfte ich diese Beschreibungen maschinell ins Englische
   übersetzen? Die Übersetzung wäre als solche gekennzeichnet.

3. Bilder: Dürfte ich Ihre Veranstaltungsbilder anzeigen? Dafür würde ich eine
   Kopie auf den Servern von event-junkie.de speichern, damit Ihr Server sie
   nicht bei jedem Aufruf ausliefern muss. Falls die Rechte bei Agenturen oder
   Fotografinnen und Fotografen liegen, genügt mir diese Auskunft.

Ein Nein ändert nichts. Es bleibt beim heutigen Zustand, und ich frage nicht
erneut. Eine Zustimmung können Sie jederzeit formlos widerrufen, auch später
und ohne Begründung. Ich entferne die betreffenden Inhalte dann und bestätige
Ihnen das innerhalb von sieben Tagen.

Ihre Adresse und Ihre Antwort bleiben in meinem Postfach. In die Datenbank der
Seite gelangt nur das Ergebnis, nicht Ihre Adresse.

Mit freundlichen Grüßen
Norman Lange

event-junkie.de
Informationen für Veranstalter: https://event-junkie.de/de/legal/for-venues
Impressum: https://event-junkie.de/de/legal/imprint
Datenschutz: https://event-junkie.de/de/legal/privacy
```

<!-- ste-lint: allow mail text, quoted rather than written as documentation -->

```text
Subject: Request for approval – your event texts on event-junkie.de

Hello,

I run event-junkie.de, a non-commercial event calendar for Berlin. The site
lists concerts and events, and always links to the venue's own page for tickets
and details.

Your imprint requires your prior approval for the use of texts, parts of texts
and images. I follow that: I store no description and no image from your site.
Your events appear with the title, date, time and venue only, each with a link
to your page:
https://event-junkie.de/en/venues/{{slug}}

I would like to ask whether you want to grant that approval, separately on
three points:

1. Descriptions: may I show your event descriptions with a link to the source?

2. Translation: may I machine translate these descriptions into German? The
   translation would be marked as one.

3. Images: may I show your event images? To do that I would keep a copy on the
   event-junkie.de servers, so that your server does not deliver them on every
   page view. If the rights sit with an agency or a photographer, telling me
   that is answer enough.

A no changes nothing. Things stay exactly as they are today, and I will not ask
again. Approval can be withdrawn at any time, informally, later and without a
reason. I then remove the content and confirm it within seven days.

Your address and your reply stay in my mailbox. Only the verdict reaches the
site's database, never your address.

Kind regards
Norman Lange

event-junkie.de
Information for venues: https://event-junkie.de/en/legal/for-venues
Imprint: https://event-junkie.de/en/legal/imprint
Privacy: https://event-junkie.de/en/legal/privacy
```

## 4. The first batch of eighteen mails

Chosen from the production event counts and the review in `RESULTS.tsv`. The mix is the point: a
ratio from ten clubs of one kind says less than a ratio across the kinds we actually list. The last
eight are §3's, and they are here so the batch is one send rather than two. The counts are upcoming
events on production on 2026-11-09. Surprise Club has none, and is in the batch for its prohibition.

| Venue                                     | Events   | Why this one                                                                                       |
| ----------------------------------------- | -------- | -------------------------------------------------------------------------------------------------- |
| Admiralspalast                            | 244      | The largest listing in this batch. Its licence page is an operator press area, so a contact exists |
| Bar jeder Vernunft and TIPI am Kanzleramt | 106, 163 | Variety and theatre, a sector this batch would otherwise miss. One operator, so one mail for both  |
| Uber Arena and Uber Eats Music Hall       | 102, 72  | A corporate operator. The clearest test of whether one answers at all. One mail for both           |
| Tempodrom                                 | 152      | A house that runs its own site and its own press work                                              |
| Heimathafen Neukölln                      | 115      | A cultural house rather than a club, and the sector most likely to have a press office             |
| Lido                                      | 86       | A mid-size independent club, which is the shape of most of the corpus                              |
| SO36                                      | 84       | Independent and self-run. The kind of venue most likely to answer a mail at all                    |
| Badehaus                                  | 72       | Small and independent, and the counterweight to the arenas                                         |
| silent green                              | 37       | A cultural institution that publishes its own press material                                       |
| Cosmic Comedy Club                        | 18       | English-language programming, and the one that gets the English variant                            |
| Renaissance-Theater                       | 134      | Requires written permission, so §3's mail. A theatre, in its own wording                           |
| Volksbühne Berlin and Volksbühne Prater   | 92, 14   | One mail for two sources. One operator, an institution of the Land Berlin                          |
| Kulturhaus Peter Edel                     | 41       | Requires prior approval, so §3's mail. Its answer is the only thing that can change anything       |
| Der Weiße Hase                            | 12       | The same clause as Kulturhaus Peter Edel                                                           |
| PANDA platforma                           | 6        | The same, in its own wording. A non-profit association                                             |
| Speakeazy                                 | 5        | The same, in an older template. A small stage                                                      |
| ZK/U                                      | 3        | Its images only, so §1's mail with §3's paragraph for ZK/U. Its texts stay `UNCLEAR`               |
| Surprise Club                             | 0        | The same as §3, with two sentences changed. Its imprint forbids rather than asks for approval      |

**Three mails go to an operator with two sources.** The imprints read on 2026-11-09 show one
company behind each pair. Two mails into one press office read as a campaign rather than as an
enquiry. So one mail names both venues and links both venue pages, and one answer is written back
to both sources.

- Bar jeder Vernunft and TIPI am Kanzleramt: one operating company, two sites.
- Uber Arena and Uber Eats Music Hall: one operating company of the Anschutz Entertainment Group.
- Volksbühne Berlin and Volksbühne Prater: one site and one imprint.

**No other two venues in the batch share an operator.** Admiralspalast is a theatre of ATG
Entertainment, and Tempodrom belongs to the KPS Firmengruppe. Neither names another venue we list.
Der Weiße Hase and Cosmic Comedy Club each have an operating company of their own.

**The last eight get §3's mail, not §1's.** ZK/U is the exception: it gets §1's mail with its
paragraph from §3. Everything else about them is the same: one send, one answer, one verdict written
back per source.

## 5. Where the contacts and the replies live

**Outside this repository, and outside the application.** A contact list in the system would break
[LEGAL.md](../LEGAL.md) §7.3a and the Hetzner AVV scope with it. Keep a local file with the venue,
the address, the date sent and the answer. `temp/` is the natural place, because `.gitignore`
already covers it.

## 6. Writing an answer back

Only the verdict reaches the database, through the admin API. The reply itself is the evidence, and
it stays in the mailbox. Each of the three questions has its own column, so write each answer to its
own field.

```sh
# Yes, to all three questions.
curl -sS -X PATCH "$IMPORTER/api/admin/event-sources/<slug>" \
  -H 'Content-Type: application/json' \
  -d '{"descriptionLicence":"PERMITTED","translationLicence":"PERMITTED","imageLicence":"PERMITTED",
       "licenceSourceUrl":"<the reply, or the page it points at>",
       "licenceNote":"Mail reply from <venue>, <date>. Quote the sentence that grants it."}'

# No, to the translation alone. Only the field they name.
curl -sS -X PATCH "$IMPORTER/api/admin/event-sources/<slug>" \
  -H 'Content-Type: application/json' \
  -d '{"translationLicence":"PROHIBITED","licenceNote":"Mail reply from <venue>, <date>."}'
```

**A `PROHIBITED` on `description_licence` clears the translation with it.** The translation is
derived from a text we then hold no justification for ([ADR-027](../adr/ADR-027_TRANSLATION_FOLLOWS_THE_DISPLAY_RULE.md)).

**A sister-venue mail is written back to both sources.** One answer from the operator covers both.

**Silence stays `UNCLEAR`.** It is not a refusal and it is not consent. Nothing is written back.

## 7. What the ratio decides

Record the outcome of the eighteen mails, then decide whether the remaining sources get the same mail. Two outcomes make
that decision for us:

- **Several yes answers.** The mail works, and `SCRAPING_POSITION.md` §3.1 gets stronger with every
  one. Continue.
- **Several no answers.** Asking costs listings that nobody objected to before we asked. That is the
  argument #808 records against itself, and a bad ratio is when it wins.

Silence is the third outcome and it decides nothing. It leaves the position exactly where
`SCRAPING_POSITION.md` §3.1 already puts it.
