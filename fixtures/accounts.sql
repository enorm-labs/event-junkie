-- Two signed-in accounts on top of `events.sql` (#4102), so a suite that runs against a deployed BFF can
-- reach `/api/me/*` without a sign-in mail. Load it after `events.sql`: the follows and RSVPs name its rows.
-- The consumers are those of `events.sql`, and `AccountFixtureTest` reads every row back through the API.
--
-- **The session tokens are fixed and public.** A cookie `__Host-ej_session=<token>` signs in as the
-- account, because the table holds only the SHA-256 of the token (`SecretTokens.kt`), and the fixture
-- hashes a known value. That is harmless on a compose or k3d database and a back door anywhere else:
-- **never load this file into staging or production.** The addresses are `example.de`, so no mail
-- reaches anyone. `fixtures/accounts.json` carries the tokens and ids for the clients; keep both in step.
--
-- CSRF needs no row: the BFF compares the `XSRF-TOKEN` cookie with the `X-XSRF-TOKEN` header, so a
-- client sends any value in both.
--
-- Two accounts and not one, so a scan can try one account's ids with the other's cookie (#4105).

INSERT INTO accounts.account (id, email, locale)
VALUES ('ada00000-0000-4000-8000-000000000001', 'ada@example.de', 'de'),
       ('b0b00000-0000-4000-8000-000000000002', 'bob@example.de', 'en');

INSERT INTO accounts.session (token_hash, account_id, expires_at)
SELECT encode(sha256(convert_to(t.token, 'UTF8')), 'hex'), t.account_id::uuid, now() + interval '30 days'
FROM (VALUES ('fixture-session-ada', 'ada00000-0000-4000-8000-000000000001'),
             ('fixture-session-bob', 'b0b00000-0000-4000-8000-000000000002')) AS t(token, account_id);

INSERT INTO accounts.follow (account_id, kind, entity_id, notify)
SELECT 'ada00000-0000-4000-8000-000000000001'::uuid, 'venue', id, 'instant' FROM events.venue WHERE slug = 'kesselhaus-nord'
UNION ALL
SELECT 'ada00000-0000-4000-8000-000000000001'::uuid, 'artist', id, 'off' FROM events.artist WHERE slug = 'mobius-trio'
UNION ALL
SELECT 'ada00000-0000-4000-8000-000000000001'::uuid, 'promoter', id, 'off' FROM events.promoter WHERE slug = 'nachtschicht-kollektiv'
UNION ALL
SELECT 'b0b00000-0000-4000-8000-000000000002'::uuid, 'venue', id, 'off' FROM events.venue WHERE slug = 'hinterhof-bar';

-- Stored as `SavedSearchQuery.normalise` leaves it: keys sorted.
INSERT INTO accounts.follow (id, account_id, kind, query, name)
VALUES ('ada00000-0000-4000-8000-000000000011', 'ada00000-0000-4000-8000-000000000001', 'search', 'q=techno&when=weekend', 'Techno am Wochenende'),
       ('b0b00000-0000-4000-8000-000000000012', 'b0b00000-0000-4000-8000-000000000002', 'search', 'q=jazz', 'Jazz');

INSERT INTO accounts.rsvp (account_id, event_id, status)
SELECT 'ada00000-0000-4000-8000-000000000001'::uuid, id, 'going' FROM events.event WHERE source_id = 'fixture-multi-bill'
UNION ALL
SELECT 'ada00000-0000-4000-8000-000000000001'::uuid, id, 'interested' FROM events.event WHERE source_id = 'fixture-free'
UNION ALL
SELECT 'b0b00000-0000-4000-8000-000000000002'::uuid, id, 'going' FROM events.event WHERE source_id = 'fixture-sold-out';
