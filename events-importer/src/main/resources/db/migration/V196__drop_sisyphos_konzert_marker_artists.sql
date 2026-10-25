-- sisy.fan marks a live set with `*KONZERT*` or `*Konzert*`, and the parser stored the marker as part of the act
-- (#3938): `Suboptimal Dub *Konzert` and `Pavelo & Schnell *Konzert` on KOLLEKTIVER KOPFSALAT (16 October 2026),
-- `Matze Sampler *KONZERT` and `Tasheeno *Konzert` on RAVE.IOLI (23 October 2026). The scraper now strips the marker,
-- but both weekends were past before the fix shipped, and a past weekend is never re-imported.

SELECT drop_artist_billed_only_by('suboptimal-dub-konzert', 'sisyphos:');
SELECT drop_artist_billed_only_by('pavelo-schnell-konzert', 'sisyphos:');
SELECT drop_artist_billed_only_by('matze-sampler-konzert', 'sisyphos:');
SELECT drop_artist_billed_only_by('tasheeno-konzert', 'sisyphos:');
