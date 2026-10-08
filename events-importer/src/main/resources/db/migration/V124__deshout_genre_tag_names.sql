-- VOID Club bills its genres in capitals, and the first import that creates a tag fixes its name
-- for every venue, so ÆDEN's `Trance` showed as `TRANCE` (#2924). The normalizer now de-shouts an
-- unknown genre, but a tag is found by slug and keeps its stored name, so the rows are renamed here.
--
-- Keyed on slug and the old name, so a row a person corrected since is left alone. The slug does
-- not change. Unqualified table names, deliberately: Flyway sets `search_path` (ADR-004).

UPDATE genre_tag
SET name = renamed.new_name
FROM (
    VALUES
        ('ambient', 'AMBIENT', 'Ambient'),
        ('bass', 'BASS', 'Bass'),
        ('beton-arme', 'BETON ARME', 'Beton Arme'),
        ('bounce', 'BOUNCE', 'Bounce'),
        ('cartoon-hits', 'CARTOON HITS', 'Cartoon Hits'),
        ('early-hardcore', 'EARLY HARDCORE', 'Early Hardcore'),
        ('gabber', 'GABBER', 'Gabber'),
        ('hard-dance', 'HARD DANCE', 'Hard Dance'),
        ('hardbounce', 'HARDBOUNCE', 'Hardbounce'),
        ('hardcore', 'HARDCORE', 'Hardcore'),
        ('hardgroove', 'HARDGROOVE', 'Hardgroove'),
        ('hardtechno', 'HARDTECHNO', 'Hardtechno'),
        ('hardtrance', 'HARDTRANCE', 'Hardtrance'),
        ('jungle', 'JUNGLE', 'Jungle'),
        ('neurofunk', 'NEUROFUNK', 'Neurofunk'),
        ('psytrance', 'PSYTRANCE', 'Psytrance'),
        ('speedcore', 'SPEEDCORE', 'Speedcore'),
        ('terror', 'TERROR', 'Terror'),
        ('trance', 'TRANCE', 'Trance')
) AS renamed (slug, old_name, new_name)
WHERE genre_tag.slug = renamed.slug
  AND genre_tag.name = renamed.old_name;
