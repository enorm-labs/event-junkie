-- The engine now closes a „ quote itself (#4743). A stored translation is kept until its source changes, so the old ones are repaired here.
UPDATE event
SET description_alt = regexp_replace(description_alt, '„([^„“”"]*)"', '„\1“', 'g')
WHERE description_alt_origin = 'MACHINE'
  AND description_alt_language = 'de'
  AND description_alt ~ '„[^„“”"]*"';

UPDATE event
SET description_alt = regexp_replace(description_alt, '„([^„“”"]*)"', '“\1”', 'g')
WHERE description_alt_origin = 'MACHINE'
  AND description_alt_language = 'en'
  AND description_alt ~ '„[^„“”"]*"';
