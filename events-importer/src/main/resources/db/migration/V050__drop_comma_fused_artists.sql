-- Six artist rows are a whole bill written as one name, because a comma separated the acts and
-- nothing read it as a separator (#1789). `splitHeadlinerTitle` now reads two or more top-level
-- commas as a list, and Berghain's scraper splits a name span the same way, so the next import
-- mints each act under its own name. These rows are what the old reading left behind, and there is
-- no re-seed on staging or production.
--
-- Two of the six were already parsed correctly and survive only because their events are in the
-- past: `Berlin Confidential präsentiert: …` and `Indie in Town - Vence, …` are not re-imported, so
-- the stale link was never replaced. They are removed for the same reason as the other four.
--
-- The rows are named by slug rather than by a pattern, because a comma in a name is not by itself
-- a defect. `Hey, Nothing`, `Kitty, Daisy & Lewis`, `Wracaj, bociemno` and `Yes, I'm Very Tired
-- Now` are real acts and stay. So do the five one-comma bills the new rule deliberately does not
-- guess at.
--
-- The FK cascade on event_artist removes the links.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

DELETE FROM artist
WHERE slug IN (
    'rodhad-dasha-rush-megan-leber-speedy-j-ufo95',
    'half-light-abul-mogard-marja-de-sanctis-rafael-anton-irisarri-concepcion-huerta',
    'process-party-x-effetto-notte-hall-of-bats-with-lovataraxx-hysteric-helen-olgha',
    'hum-w-kyle-hall-b2b-k15-mamaliathe-first-lady-of-modern-funkft-mauricio-fleury-bulma-brief',
    'berlin-confidential-prasentiert-georgy-gusev-sven-helbig-ivan-skanavi-deutsches-kammerorchester-berlin',
    'indie-in-town-vence-m-lucky-mirrors-for-princes-yunni'
);
