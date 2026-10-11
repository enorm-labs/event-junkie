-- Festsaal files `BIG QUEER MARKET` under the genre `Markt`, and the scraper typed it a concert and stored its title as the
-- act (#3097). The night is now `OTHER` and bills no one, but a past night is never imported again, so its link stays.

SELECT drop_artist_billed_only_by('big-queer-market', 'festsaal:');
