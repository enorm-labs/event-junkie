-- Rows for the DAST scan (#1421): enough pages for the spider to find and enough parameters for the
-- API scan to fuzz, without scraping anyone. The k3d cluster starts with an empty database, and an
-- empty site gives ZAP one page to look at.
--
-- A fixture and not an import, on purpose. `k3d-rehearsal.sh import` fetches one venue's page once,
-- for a person running a rehearsal. A nightly workflow doing the same is a third party paying for
-- our scanner, and a live page changes shape, so the URL floor in scan-coverage-baseline.txt would
-- mean nothing. These rows are the same every night.
--
-- Written against migrations V001 to V033. A migration that adds a NOT NULL column or a CHECK this
-- file does not satisfy fails the seed step with a PostgreSQL error naming the column, and that is
-- the fix: this file, not the migration. Dates are relative to CURRENT_DATE, so the events are
-- always upcoming and the public search returns them.
--
-- Run inside the `events` schema: dast.yml passes `-v ON_ERROR_STOP=1` and this SET.
SET search_path TO events;

INSERT INTO venue (name, slug, address, postal_code, district, latitude, longitude, website_url, description, description_language)
VALUES
    ('Kesselhaus Nord', 'kesselhaus-nord', 'Prenzlauer Allee 1', '10405', 'prenzlauer-berg', 52.531000, 13.421000, 'https://kesselhaus-nord.example', 'Ein Club im alten Kesselhaus. Zwei Floors, ein Garten.', 'de'),
    ('Salon Zur Wilden Renate & Co.', 'salon-zur-wilden-renate-co', 'Alt-Stralau 70', '10245', 'friedrichshain', 52.497000, 13.470000, 'https://renate.example', NULL, NULL),
    ('Jazzkeller $& Kreuzberg', 'jazzkeller-kreuzberg', 'Oranienstraße 12', '10999', 'kreuzberg', 52.501000, 13.421000, 'https://jazzkeller.example', 'Small basement stage. Jazz on weekdays, blues at the weekend.', 'en');

INSERT INTO promoter (name, slug, website_url)
VALUES ('Nachtschicht Kollektiv', 'nachtschicht-kollektiv', 'https://nachtschicht.example');

INSERT INTO artist (name, slug, website_url)
VALUES
    ('Møbius Trio', 'mobius-trio', 'https://mobius.example'),
    ('DJ Überdruck', 'dj-uberdruck', NULL);

INSERT INTO genre_tag (name, slug, family)
VALUES
    ('Techno', 'techno', 'electronic'),
    ('Punk', 'punk', 'punk'),
    ('Jazz', 'jazz', 'jazz-blues');

-- Thirty events across fourteen days, ten per venue. generate_series keeps the fixture short and the
-- count fixed, and the modulo arithmetic spreads genres, prices and flags so every filter the BFF
-- exposes has rows on both sides of it.
INSERT INTO event (venue_id, title, subtitle, description, description_language, event_type, slug, event_date, doors_time, start_time,
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
FROM venue v
         CROSS JOIN generate_series(1, 10) AS n;

INSERT INTO event_genre_tag (event_id, genre_tag_id)
SELECT e.id, g.id
FROM event e
         JOIN genre_tag g ON g.name = e.genre
WHERE e.source_id LIKE 'dast-%';

INSERT INTO event_artist (event_id, artist_id, role, billing_order)
SELECT e.id, a.id, 'HEADLINER', 0
FROM event e
         JOIN artist a ON a.slug = CASE WHEN e.event_type = 'PARTY' THEN 'dj-uberdruck' ELSE 'mobius-trio' END
WHERE e.source_id LIKE 'dast-%';

INSERT INTO event_promoter (event_id, promoter_id)
SELECT e.id, p.id
FROM event e
         CROSS JOIN promoter p
WHERE e.source_id LIKE 'dast-%' AND e.event_type = 'PARTY';
