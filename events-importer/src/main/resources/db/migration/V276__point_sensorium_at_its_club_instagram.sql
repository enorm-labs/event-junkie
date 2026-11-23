-- V276 — Sensorium's Instagram link moves to the account its own site links.
--
-- V219 set `@sensorium.berlin`, which has not posted since March. The club's site links
-- `@sensorium_club_official`, which posts the nights. The update leaves a link someone changed by hand.

UPDATE venue
SET instagram_url = 'https://www.instagram.com/sensorium_club_official/'
WHERE slug = 'sensorium' AND instagram_url = 'https://www.instagram.com/sensorium.berlin/';
