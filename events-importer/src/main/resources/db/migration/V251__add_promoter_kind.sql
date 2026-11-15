-- What a promoter row is: a booking business, a DJ collective, a record label, a party series, and so on (#5080).
-- The slugs are `PromoterKind`'s. Null means nobody decided yet; `docs/promoters/REVIEWED.tsv` holds the decisions and
-- `scripts/promoter-websites.py` writes them, so this migration fills nothing.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).
ALTER TABLE promoter
    ADD COLUMN kind TEXT,
    ADD CONSTRAINT promoter_kind_known CHECK (kind IN ('promoter', 'collective', 'label', 'party', 'media', 'artist', 'sponsor', 'venue'));
