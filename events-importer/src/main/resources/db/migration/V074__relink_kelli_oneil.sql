-- Arcanoa billed its 2026-09-05 night as `Kellie O`Neil - The Hidden Grove`, and an older parse
-- minted that whole line as the artist (#2179). Today's parse bills the 2026-11-06 night as
-- `Kelli O'Neil`, the venue's current spelling. The September night has left the venue's page and
-- is never imported again, so its link moves here. Both titles stay as the venue published them.
--
-- The loser is keyed on slug and on its name, in either form of the apostrophe (V072 folds it), so
-- a row a person corrected since is left alone. Both steps join the survivor, so nothing is deleted
-- where `kelli-o-neil` is missing.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, title_derived, set_start, set_end)
SELECT ea.event_id, survivor.id, ea.role, ea.billing_order, ea.stage, ea.title_derived, ea.set_start, ea.set_end
FROM artist loser
JOIN artist survivor ON survivor.slug = 'kelli-o-neil'
JOIN event_artist ea ON ea.artist_id = loser.id
WHERE loser.slug = 'kellie-o-neil-the-hidden-grove'
  AND loser.name IN ('Kellie O''Neil - The Hidden Grove', 'Kellie O`Neil - The Hidden Grove')
ON CONFLICT (event_id, artist_id) DO NOTHING;

DELETE FROM artist loser
USING artist survivor
WHERE loser.slug = 'kellie-o-neil-the-hidden-grove'
  AND loser.name IN ('Kellie O''Neil - The Hidden Grove', 'Kellie O`Neil - The Hidden Grove')
  AND survivor.slug = 'kelli-o-neil';
