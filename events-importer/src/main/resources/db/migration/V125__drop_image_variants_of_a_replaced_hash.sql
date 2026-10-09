-- A re-fetch with changed bytes kept the variants of the old bytes. Their keys name the old hash,
-- which the orphan sweep deletes, so the image 404s at every size (#2939).
-- Dropping the rows lets the derivative pass render them again under the current hash.
DELETE FROM cached_image_variant v
USING cached_image c
WHERE c.id = v.cached_image_id
  AND c.content_hash IS NOT NULL
  AND strpos(v.storage_key, '/' || c.content_hash || '/') = 0;
