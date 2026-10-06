-- An artist row that is an album title fused to an act, not an act (#2778). Morphine Raum's 2026-10-14 set line
-- `Maurice Louca & Ayman Asfour - FERA` was split on its `&`, so the second name kept the album. The parser now keeps
-- the head when the event title bills it and leaves the tail out. The next import of an upcoming night replaces its
-- lineup, but a past night is never imported again, so this drops the row whichever comes first.

SELECT drop_artist_billed_only_by('ayman-asfour-fera', 'morphine:');
