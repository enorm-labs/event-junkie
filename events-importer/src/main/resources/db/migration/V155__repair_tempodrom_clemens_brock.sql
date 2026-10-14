-- Tempodrom's 2026-10-05 night `Clemens Brock - Der Vadda` stored its whole title as the act (#3162). `Der Vadda` is the
-- programme; the comedian is Clemens Brock. The night is past, and a past night is never imported again, so its link
-- moves here. The upcoming productions the parser now bills differently are fixed by the next import.
--
-- The act takes the old row's place at its role, stage and billing order, and is created only when there is a link to
-- replace. Then the old row goes with drop_artist_billed_only_by, unless another venue still bills it.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

INSERT INTO artist (name, slug)
SELECT 'Clemens Brock', 'clemens-brock'
WHERE EXISTS (
        SELECT 1
        FROM event_artist ea
        JOIN event e ON e.id = ea.event_id
        JOIN artist a ON a.id = ea.artist_id
        WHERE e.source_id = 'tempodrom:clemens_brock-der_vadda_2026-10-05_20'
          AND a.slug = 'clemens-brock-der-vadda'
    )
  AND NOT EXISTS (SELECT 1 FROM artist WHERE slug = 'clemens-brock');

INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, title_derived, set_start, set_end)
SELECT ea.event_id, brock.id, ea.role, ea.billing_order, ea.stage, ea.title_derived, ea.set_start, ea.set_end
FROM event_artist ea
JOIN event e ON e.id = ea.event_id
JOIN artist a ON a.id = ea.artist_id
JOIN artist brock ON brock.slug = 'clemens-brock'
WHERE e.source_id = 'tempodrom:clemens_brock-der_vadda_2026-10-05_20'
  AND a.slug = 'clemens-brock-der-vadda'
ON CONFLICT (event_id, artist_id) DO NOTHING;

SELECT drop_artist_billed_only_by('clemens-brock-der-vadda', 'tempodrom:');
