-- The reusable test dataset (#272): the same rows everywhere, no network, one file. Three consumers
-- read it: `scripts/dev-env.sh seed-fixture`, the BFF's `FixtureTest`, which asserts every shape
-- below through the API and so fails the build when a migration breaks this file, and `dast.yml`,
-- which spiders these rows on k3d (#1421). A fixture and not an import, on purpose: `seed-all` and
-- `k3d-rehearsal.sh import` fetch real venue pages, and a scanner or a test should not.
--
-- Two halves. The generated half is volume: thirty events over fourteen days, rows on both sides of
-- every filter. The named half is one event per shape, `source_id = 'fixture-<shape>'`, so a test
-- finds the row by name: a multi-artist bill, a festival, sold out, free, no price, no genre, two
-- rooms of one venue on one night, relocated, cancelled, postponed, past, a translated description,
-- and one artist MusicBrainz verified.
--
-- Dates are relative to CURRENT_DATE. The past row is three days back, because the BFF keeps
-- yesterday's late starts listed until 06:00 (#299). No row has an image: `image_url` needs the
-- attribution triple (V020). Every name is schema-qualified, because psql and the BFF's R2DBC client
-- both run this and only one of them has a session to set `search_path` on.
--
-- **Keeping it current is FixtureTest's job, not memory's.** It asserts that every column of `event`,
-- `venue` and `artist` is set on some row, and that every `EventStatus` and `ArtistRole` value is
-- here. A migration adding a NOT NULL column or a CHECK breaks the load and names the column; a
-- nullable one loads and covers nothing, and the column assertion is what catches that. Add the row,
-- or waive the column in `FixtureTest.WAIVED` with a reason.

INSERT INTO events.venue (name, slug, address, postal_code, district, latitude, longitude, website_url, description, description_language,
                          description_alt, description_alt_language)
VALUES
    ('Kesselhaus Nord', 'kesselhaus-nord', 'Prenzlauer Allee 1', '10405', 'prenzlauer-berg', 52.531000, 13.421000, 'https://kesselhaus-nord.example', 'Ein Club im alten Kesselhaus. Zwei Floors, ein Garten.', 'de',
     'A club in the old boiler house. Two floors and a garden.', 'en'),
    ('Salon Zur Wilden Renate & Co.', 'salon-zur-wilden-renate-co', 'Alt-Stralau 70', '10245', 'friedrichshain', 52.497000, 13.470000, 'https://renate.example', NULL, NULL, NULL, NULL),
    ('Jazzkeller $& Kreuzberg', 'jazzkeller-kreuzberg', 'Oranienstraße 12', '10999', 'kreuzberg', 52.501000, 13.421000, 'https://jazzkeller.example', 'Small basement stage. Jazz on weekdays, blues at the weekend.', 'en', NULL, NULL);

INSERT INTO events.promoter (name, slug, website_url)
VALUES ('Nachtschicht Kollektiv', 'nachtschicht-kollektiv', 'https://nachtschicht.example');

INSERT INTO events.artist (name, slug, website_url)
VALUES
    ('Møbius Trio', 'mobius-trio', 'https://mobius.example'),
    ('DJ Überdruck', 'dj-uberdruck', NULL);

INSERT INTO events.genre_tag (name, slug, family)
VALUES
    ('Techno', 'techno', 'electronic'),
    ('Punk', 'punk', 'punk'),
    ('Jazz', 'jazz', 'jazz-blues');

-- The generated half. Thirty events across fourteen days, ten per venue. generate_series keeps the fixture short and the
-- count fixed, and the modulo arithmetic spreads genres, prices and flags so every filter the BFF
-- exposes has rows on both sides of it.
INSERT INTO events.event (venue_id, title, subtitle, description, description_language, event_type, slug, event_date, doors_time, start_time,
                   end_date, end_time, source_url, source_id, ticket_url, genre, price_presale, price_box_office, sold_out, free)
SELECT v.id,
       CASE v.slug
           WHEN 'kesselhaus-nord' THEN 'Nachtschicht #' || n || ' — Techno bis früh'
           WHEN 'salon-zur-wilden-renate-co' THEN 'Punk''s Not Dead Vol. ' || n
           ELSE 'Jazz $& Blues Session ' || n
       END,
       CASE WHEN n % 3 = 0 THEN 'Support: Møbius Trio' END,
       CASE WHEN n % 2 = 0 THEN 'Türöffnung eine Stunde vor Beginn. Keine Fotos auf dem Floor. <b>nicht</b> HTML.' END,
       CASE WHEN n % 2 = 0 THEN 'de' END,
       CASE WHEN v.slug = 'kesselhaus-nord' THEN 'PARTY' ELSE 'CONCERT' END,
       v.slug || '-' || n || '-' || to_char(CURRENT_DATE + n, 'YYYY-MM-DD'),
       CURRENT_DATE + n,
       TIME '19:00',
       TIME '20:00',
       CASE WHEN v.slug = 'kesselhaus-nord' THEN CURRENT_DATE + n + 1 END,
       CASE WHEN v.slug = 'kesselhaus-nord' THEN TIME '08:00' END,
       v.website_url || '/events/' || n,
       'dast-' || v.slug || '-' || n,
       CASE WHEN n % 4 = 0 THEN 'https://tickets.example/' || v.slug || '/' || n END,
       CASE v.slug WHEN 'kesselhaus-nord' THEN 'Techno' WHEN 'salon-zur-wilden-renate-co' THEN 'Punk' ELSE 'Jazz' END,
       CASE WHEN n % 5 <> 0 THEN 12.50 + n END,
       CASE WHEN n % 5 <> 0 THEN 15.00 + n END,
       n = 7,
       n % 5 = 0
FROM events.venue v
         CROSS JOIN generate_series(1, 10) AS n;

INSERT INTO events.event_genre_tag (event_id, genre_tag_id)
SELECT e.id, g.id
FROM events.event e
         JOIN events.genre_tag g ON g.name = e.genre
WHERE e.source_id LIKE 'dast-%';

INSERT INTO events.event_artist (event_id, artist_id, role, billing_order)
SELECT e.id, a.id, 'HEADLINER', 0
FROM events.event e
         JOIN events.artist a ON a.slug = CASE WHEN e.event_type = 'PARTY' THEN 'dj-uberdruck' ELSE 'mobius-trio' END
WHERE e.source_id LIKE 'dast-%';

INSERT INTO events.event_promoter (event_id, promoter_id)
SELECT e.id, p.id
FROM events.event e
         CROSS JOIN events.promoter p
WHERE e.source_id LIKE 'dast-%' AND e.event_type = 'PARTY';

-- ============================================================================================
-- The named half: one event per shape, `source_id = 'fixture-<shape>'`. Each row is the whole
-- shape and nothing else, so a test that fails names the one thing that broke.
-- ============================================================================================

-- The cast for the named rows. `mobius-trio` above is the one row a MusicBrainz lookup verified
-- (ADR-031). The MBID is nobody's: the site links it and MusicBrainz answers 404, which is the
-- right outcome for an invented artist and beats borrowing a real act's identity.
UPDATE events.artist
SET musicbrainz_id = '00000000-0000-4000-8000-000000000272', musicbrainz_match = 'EXACT', musicbrainz_checked_at = now()
WHERE slug = 'mobius-trio';

INSERT INTO events.artist (name, slug, website_url, description, facebook_url, instagram_url, youtube_url)
VALUES
    ('Anna Kessel', 'anna-kessel', 'https://annakessel.example',
     'Songwriterin aus Leipzig. Zweites Album im Frühjahr.', 'https://facebook.example/annakessel',
     'https://instagram.example/annakessel', 'https://youtube.example/@annakessel'),
    ('Rauhfaser', 'rauhfaser', NULL, NULL, NULL, NULL, NULL),
    ('DJ Nachtfalter', 'dj-nachtfalter', NULL, NULL, NULL, NULL, NULL);

INSERT INTO events.promoter (name, slug, website_url)
VALUES ('Sommerlaune Festival GmbH', 'sommerlaune-festival', 'https://sommerlaune.example');

-- A multi-artist bill: a headliner, two supports and a DJ, in billing order, and one line-up entry
-- the importer derived from the title rather than read from a line-up (V038).
INSERT INTO events.event (venue_id, title, subtitle, event_type, slug, event_date, doors_time, start_time, source_url, source_id, ticket_url,
                          facebook_event_url, genre, price_presale, price_box_office)
SELECT v.id, 'Møbius Trio', 'Support: Anna Kessel, Rauhfaser · Afterparty: DJ Nachtfalter', 'CONCERT',
       'fixture-multi-bill-' || to_char(CURRENT_DATE + 3, 'YYYY-MM-DD'), CURRENT_DATE + 3, TIME '19:00', TIME '20:00',
       v.website_url || '/events/multi-bill', 'fixture-multi-bill', 'https://tickets.example/multi-bill',
       'https://fb.example/e/multi-bill', 'Jazz', 18.00, 22.00
FROM events.venue v WHERE v.slug = 'jazzkeller-kreuzberg';

INSERT INTO events.event_artist (event_id, artist_id, role, billing_order, title_derived)
SELECT e.id, a.id, r.role, r.billing_order, r.title_derived
FROM events.event e
         CROSS JOIN (VALUES ('mobius-trio', 'HEADLINER', 0, true),
                            ('anna-kessel', 'SUPPORT', 1, false),
                            ('rauhfaser', 'SUPPORT', 2, false),
                            ('dj-nachtfalter', 'DJ', 3, false)) AS r(slug, role, billing_order, title_derived)
         JOIN events.artist a ON a.slug = r.slug
WHERE e.source_id = 'fixture-multi-bill';

INSERT INTO events.event_genre_tag (event_id, genre_tag_id)
SELECT e.id, g.id FROM events.event e, events.genre_tag g WHERE e.source_id = 'fixture-multi-bill' AND g.slug = 'jazz';

-- A festival: three days by the venue's own word (ADR-029), three artists on two stages, a promoter.
INSERT INTO events.event (venue_id, title, subtitle, description, description_language, event_type, slug, event_date, doors_time, start_time,
                          end_date, end_time, source_url, source_id, ticket_url, genre, price_presale, price_box_office)
SELECT v.id, 'Sommerlaune Festival', 'Drei Tage, zwei Bühnen', 'Das Festival im Garten des Kesselhauses. Camping auf der Wiese.', 'de', 'FESTIVAL',
       'fixture-festival-' || to_char(CURRENT_DATE + 10, 'YYYY-MM-DD'), CURRENT_DATE + 10, TIME '14:00', TIME '16:00',
       CURRENT_DATE + 12, TIME '23:00', v.website_url || '/events/sommerlaune', 'fixture-festival', 'https://tickets.example/sommerlaune',
       'Techno', 89.00, 110.00
FROM events.venue v WHERE v.slug = 'kesselhaus-nord';

INSERT INTO events.event_artist (event_id, artist_id, role, billing_order, stage)
SELECT e.id, a.id, r.role, r.billing_order, r.stage
FROM events.event e
         CROSS JOIN (VALUES ('dj-uberdruck', 'HEADLINER', 0, 'Hauptbühne'),
                            ('dj-nachtfalter', 'DJ', 1, 'Garten'),
                            ('rauhfaser', 'SUPPORT', 2, 'Hauptbühne')) AS r(slug, role, billing_order, stage)
         JOIN events.artist a ON a.slug = r.slug
WHERE e.source_id = 'fixture-festival';

INSERT INTO events.event_genre_tag (event_id, genre_tag_id)
SELECT e.id, g.id FROM events.event e, events.genre_tag g WHERE e.source_id = 'fixture-festival' AND g.slug = 'techno';

INSERT INTO events.event_promoter (event_id, promoter_id)
SELECT e.id, p.id FROM events.event e, events.promoter p WHERE e.source_id = 'fixture-festival' AND p.slug = 'sommerlaune-festival';

-- Sold out, with the ticket link the venue still shows.
INSERT INTO events.event (venue_id, title, event_type, slug, event_date, start_time, source_url, source_id, ticket_url, genre, price_presale, sold_out)
SELECT v.id, 'Anna Kessel — Ausverkauft', 'CONCERT', 'fixture-sold-out-' || to_char(CURRENT_DATE + 4, 'YYYY-MM-DD'), CURRENT_DATE + 4, TIME '20:00',
       v.website_url || '/events/sold-out', 'fixture-sold-out', 'https://tickets.example/sold-out', 'Punk', 25.00, true
FROM events.venue v WHERE v.slug = 'salon-zur-wilden-renate-co';

INSERT INTO events.event_artist (event_id, artist_id, role, billing_order)
SELECT e.id, a.id, 'HEADLINER', 0 FROM events.event e, events.artist a WHERE e.source_id = 'fixture-sold-out' AND a.slug = 'anna-kessel';

INSERT INTO events.event_genre_tag (event_id, genre_tag_id)
SELECT e.id, g.id FROM events.event e, events.genre_tag g WHERE e.source_id = 'fixture-sold-out' AND g.slug = 'punk';

-- Free: the venue says so, and there is no price to show.
INSERT INTO events.event (venue_id, title, event_type, slug, event_date, start_time, source_url, source_id, genre, free)
SELECT v.id, 'Jam Session — Eintritt frei', 'CONCERT', 'fixture-free-' || to_char(CURRENT_DATE + 5, 'YYYY-MM-DD'), CURRENT_DATE + 5, TIME '21:00',
       v.website_url || '/events/jam', 'fixture-free', 'Jazz', true
FROM events.venue v WHERE v.slug = 'jazzkeller-kreuzberg';

INSERT INTO events.event_genre_tag (event_id, genre_tag_id)
SELECT e.id, g.id FROM events.event e, events.genre_tag g WHERE e.source_id = 'fixture-free' AND g.slug = 'jazz';

-- No price, and not free either: the venue asks for a donation, and the note is all it publishes.
INSERT INTO events.event (venue_id, title, event_type, slug, event_date, start_time, source_url, source_id, genre, price_note)
SELECT v.id, 'Rauhfaser — Spende erbeten', 'CONCERT', 'fixture-no-price-' || to_char(CURRENT_DATE + 6, 'YYYY-MM-DD'), CURRENT_DATE + 6, TIME '20:00',
       v.website_url || '/events/spende', 'fixture-no-price', 'Punk', 'Spende 5–10 €'
FROM events.venue v WHERE v.slug = 'salon-zur-wilden-renate-co';

INSERT INTO events.event_artist (event_id, artist_id, role, billing_order)
SELECT e.id, a.id, 'HEADLINER', 0 FROM events.event e, events.artist a WHERE e.source_id = 'fixture-no-price' AND a.slug = 'rauhfaser';

INSERT INTO events.event_genre_tag (event_id, genre_tag_id)
SELECT e.id, g.id FROM events.event e, events.genre_tag g WHERE e.source_id = 'fixture-no-price' AND g.slug = 'punk';

-- No genre: the source names none, so the row carries no text and no tag, and no genre or family
-- filter may return it.
INSERT INTO events.event (venue_id, title, event_type, slug, event_date, start_time, source_url, source_id, price_presale)
SELECT v.id, 'Offene Bühne', 'SHOW', 'fixture-no-genre-' || to_char(CURRENT_DATE + 7, 'YYYY-MM-DD'), CURRENT_DATE + 7, TIME '19:30',
       v.website_url || '/events/offene-buehne', 'fixture-no-genre', 5.00
FROM events.venue v WHERE v.slug = 'jazzkeller-kreuzberg';

-- Two rooms of one venue on one night: one venue row, two events, the room in the subtitle, as the
-- importers store a house with two floors. Past the generated fortnight, so that night has exactly
-- these two rows under the venue.
INSERT INTO events.event (venue_id, title, subtitle, event_type, slug, event_date, start_time, end_date, end_time, source_url, source_id, genre,
                          price_presale)
SELECT v.id, r.title, r.subtitle, 'PARTY', 'fixture-' || r.shape || '-' || to_char(CURRENT_DATE + 15, 'YYYY-MM-DD'), CURRENT_DATE + 15, r.start_time,
       CURRENT_DATE + 16, TIME '08:00', v.website_url || '/events/' || r.shape, 'fixture-' || r.shape, 'Techno', 15.00
FROM events.venue v
         CROSS JOIN (VALUES ('room-floor', 'Nachtschicht Floor', 'Floor 1', TIME '23:00'),
                            ('room-garden', 'Nachtschicht Garten', 'Garten', TIME '22:00')) AS r(shape, title, subtitle, start_time)
WHERE v.slug = 'kesselhaus-nord';

INSERT INTO events.event_genre_tag (event_id, genre_tag_id)
SELECT e.id, g.id FROM events.event e, events.genre_tag g WHERE e.source_id IN ('fixture-room-floor', 'fixture-room-garden') AND g.slug = 'techno';

-- Relocated: the venue's note says where it went, and the row stays here (ADR-030).
INSERT INTO events.event (venue_id, title, event_type, status, relocated_to, slug, event_date, start_time, source_url, source_id, genre, price_presale)
SELECT v.id, 'Anna Kessel — verlegt', 'CONCERT', 'RELOCATED', 'Kesselhaus Nord', 'fixture-relocated-' || to_char(CURRENT_DATE + 9, 'YYYY-MM-DD'),
       CURRENT_DATE + 9, TIME '20:00', v.website_url || '/events/verlegt', 'fixture-relocated', 'Punk', 20.00
FROM events.venue v WHERE v.slug = 'salon-zur-wilden-renate-co';

-- Cancelled: still published by the venue, still listed, marked.
INSERT INTO events.event (venue_id, title, event_type, status, slug, event_date, start_time, source_url, source_id, genre, price_presale)
SELECT v.id, 'Rauhfaser — abgesagt', 'CONCERT', 'CANCELLED', 'fixture-cancelled-' || to_char(CURRENT_DATE + 11, 'YYYY-MM-DD'), CURRENT_DATE + 11,
       TIME '20:00', v.website_url || '/events/abgesagt', 'fixture-cancelled', 'Punk', 20.00
FROM events.venue v WHERE v.slug = 'salon-zur-wilden-renate-co';

INSERT INTO events.event_genre_tag (event_id, genre_tag_id)
SELECT e.id, g.id FROM events.event e, events.genre_tag g WHERE e.source_id IN ('fixture-relocated', 'fixture-cancelled') AND g.slug = 'punk';

-- Postponed: the venue says the date moves and names no new one, so the row keeps the date it had.
-- The fourth EventStatus, and `FixtureTest` asserts all four are here.
INSERT INTO events.event (venue_id, title, event_type, status, slug, event_date, start_time, source_url, source_id, genre, price_presale)
SELECT v.id, 'DJ Überdruck — verschoben', 'PARTY', 'POSTPONED', 'fixture-postponed-' || to_char(CURRENT_DATE + 17, 'YYYY-MM-DD'), CURRENT_DATE + 17,
       TIME '23:00', v.website_url || '/events/verschoben', 'fixture-postponed', 'Techno', 15.00
FROM events.venue v WHERE v.slug = 'kesselhaus-nord';

INSERT INTO events.event_genre_tag (event_id, genre_tag_id)
SELECT e.id, g.id FROM events.event e, events.genre_tag g WHERE e.source_id = 'fixture-postponed' AND g.slug = 'techno';

-- Past: three days back (see the header), so the default list, Tonight and the calendar from today
-- leave it out, while its slug still resolves.
INSERT INTO events.event (venue_id, title, event_type, slug, event_date, start_time, source_url, source_id, genre, price_presale)
SELECT v.id, 'Jazz $& Blues Session — vorbei', 'CONCERT', 'fixture-past-' || to_char(CURRENT_DATE - 3, 'YYYY-MM-DD'), CURRENT_DATE - 3, TIME '20:00',
       v.website_url || '/events/vorbei', 'fixture-past', 'Jazz', 12.00
FROM events.venue v WHERE v.slug = 'jazzkeller-kreuzberg';

-- A translated description: German from the venue, English by a machine, disclosed as such
-- (ADR-026, ADR-027). The hash is what the translation was made from; any value marks it current.
INSERT INTO events.event (venue_id, title, event_type, slug, event_date, start_time, source_url, source_id, genre, price_presale,
                          description, description_language, description_language_confidence,
                          description_alt, description_alt_language, description_alt_origin, description_alt_engine, description_alt_source_hash)
SELECT v.id, 'Møbius Trio — Albumrelease', 'CONCERT', 'fixture-translated-' || to_char(CURRENT_DATE + 13, 'YYYY-MM-DD'), CURRENT_DATE + 13, TIME '20:00',
       v.website_url || '/events/albumrelease', 'fixture-translated', 'Jazz', 16.00,
       'Das Trio stellt sein zweites Album vor. Im Anschluss Session mit Gästen.', 'de', 0.990,
       'The trio presents its second album. A session with guests follows.', 'en', 'MACHINE', 'deepl', 'fixture'
FROM events.venue v WHERE v.slug = 'jazzkeller-kreuzberg';

INSERT INTO events.event_artist (event_id, artist_id, role, billing_order)
SELECT e.id, a.id, 'HEADLINER', 0 FROM events.event e, events.artist a WHERE e.source_id = 'fixture-translated' AND a.slug = 'mobius-trio';

INSERT INTO events.event_genre_tag (event_id, genre_tag_id)
SELECT e.id, g.id FROM events.event e, events.genre_tag g WHERE e.source_id = 'fixture-translated' AND g.slug = 'jazz';
