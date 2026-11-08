-- V235 — Lauschangriff loses its website link: the operator's security software flags lauschangriff-berlin.de as malware.
--
-- The programme link to Resident Advisor stays. The condition keeps any other link someone set by hand.

UPDATE venue SET website_url = NULL WHERE slug = 'lauschangriff' AND website_url = 'http://www.lauschangriff-berlin.de';
