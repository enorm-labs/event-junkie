-- The Theater im Delphi title `Kunstlieder aus Lateinamerika` names a programme, not an act (#2899).
-- The parser now bills the row's credit line. The night is past and is never imported again, so the row stays without this.

SELECT drop_artist_billed_only_by('kunstlieder-aus-lateinamerika', 'theater_im_delphi:');
