# Add Venue

Give a venue we do not import a row and a page: the facts the operator confirms, a link to its programme, and a seed block that reaches both clusters
after the release (#2766). The counterpart to [`/scaffold-importer`](scaffold-importer.prompt.md), which builds an importer; this builds none.

Usage: `/add-venue <name> [<url>]…`, one venue or a batch of about 25.

## Important

- **The operator confirms every fact before it is written.** Address, district, coordinates and "still open" are facts only a person can settle. Two
  agreeing geocoders are not proof: `scripts/geocode-venues.py` records a district both Google and OpenStreetMap got wrong. Draft, show, wait.
- **Never fetch `ra.co`, `facebook.com`, `instagram.com` or `eventbrite.com` by script.** Their terms forbid automated access (#356). A Resident Advisor
  page may be the programme link; find its URL in a search snippet or let the operator read it in a browser. A 403 or a `robots.txt` refusal on the venue's
  own site is an answer, not an obstacle.
- **No event source, no import request.** A venue with a source is an imported venue, and that is `/scaffold-importer`.
- This skill writes a branch and a pull request. Rows reach a cluster only through `## After deploy`.

## Scope

A venue gets a row when it is a **music or comedy venue that is open and public**. The rules the operator set for #2766:

| In                                                                                 | Out                                                                            |
| ---------------------------------------------------------------------------------- | ------------------------------------------------------------------------------ |
| Clubs, bars, concert halls, open-air venues with a music programme                 | Casinos, agencies, production companies, collectives without a venue           |
| Comedy clubs                                                                       | Rental grounds and event locations without a public programme of their own     |
| Theatres whose programme is not only drama: concerts, cabaret, comedy, music shows | Pure drama or musical houses                                                   |
| Restaurants and beer gardens that host DJ nights or concerts                       | Restaurants without in-scope events                                            |
| Venues whose programme lives only on RA, Instagram or Facebook                     | Members-only spaces, sex venues, closed venues, classical until EVENT_SCOPE.md |

A borderline case is a question for the operator, not a call to make.

## Steps

1. **Check it is not there already.** `grep -rn '"name": "<name>"' http/importer/seed/venues/`, and the venue's row in `docs/EVENT_DATA_SOURCES.md`.
   A venue in ✅ Imported has a row. A venue whose site now publishes a dated programme belongs in 🔨 Ready, not here (#2810 has the list).

2. **Look at the venue**, on its own site and in search results:
    - **Open?** A parked domain or a site frozen for years is evidence, not proof. Say which it is, with the URL.
    - **Address and postal code** from the venue's own imprint or contact page.
    - **Programme link**, in this order: the venue's own programme page; its Resident Advisor club or promoter page; a ticket platform it really sells
      on. None at all is allowed: the page then links the website.
    - **Type** as `VenueType` slugs, and **capacity** only where the venue publishes one.
    - **Instagram and Facebook pages**, where the venue has one (#2839). Copy the URL from a link on the venue's own site or from a search snippet;
      never open either host by script. The admin API takes only `https://` URLs on `instagram.com` and `facebook.com`, and the venue page shows them
      beside "Website". Leave a field out when you find no account; a guessed handle is worse than none.

3. **Coordinates come from OpenStreetMap; Google only detects.** Google's terms do not let us keep its points on a map that is not Google's, and
   OpenStreetMap's (ODbL) we may keep (LEGAL.md § 9.3). So a pin from Google Maps, the operator's included, is a hint and never the stored value.
    - Write the venue file (step 7), then run `python3 scripts/osm-venue-coordinates.py --venue "<name>" --apply`. It asks Nominatim for the street address and
      writes the point when OpenStreetMap matched the house number, or when a name search finds an object carrying the venue's own website.
    - Then run `python3 scripts/geocode-venues.py --not-imported --by-name`. Where Google finds the venue's name far from its address, the address
      is the suspect: put it to the operator.
    - A venue the script reports as `far`, `missing` or `street-only` goes to the operator with both points. The fix is an OpenStreetMap point
      read off openstreetmap.org, or a corrected address.
    - **Never fill `district` from a geocoder**: pick it from the address, one of the 23 pre-2001 districts, and flag one that sits on a border.

4. **Photo, optional.** Only a picture with a stated licence from Wikimedia Commons or Flickr, never one from the venue's site (#808). Propose it as a
   `docs/venue-images/REVIEWED.tsv` row for the operator to mark `CONFIRMED`; `scripts/venue-images.py` writes it after the deploy. No photo is fine: the
   card draws a poster.

5. **Description**, English and German, one sentence each. Read the venue's About or history page and its Wikipedia articles **for facts only**, then
   write our own prose: the venue's text is copyrighted (#808) and Wikipedia's is CC BY-SA, so no sentence is copied or closely paraphrased. The style
   is `/scaffold-importer` § 6: open with what sets the venue apart, name its formats plainly, no hedged tail. Keep each fact's source URL for the
   operator.

6. **Show the operator one table and stop.** Name, address, postal code, district, coordinates, types, programme link, social links, the evidence it is open, and a
   photo candidate if any, and the two descriptions. Mark every cell you are unsure of. Write nothing until the operator confirms or corrects each row.

7. **Write the confirmed venues**, each as its own file `http/importer/seed/venues/<venue-slug>.json` (#2824). The file name is the slug
   `SlugGenerator` makes from the name (`Café Köpenick` → `cafe-kopenick.json`); `SeedVenueFilesTest` fails on a wrong one, and
   `scripts/dev-seed-parity.sh` says which name it wants.

    ```json
    {
        "venue": {
            "name": "<Name>",
            "address": "<street no>",
            "city": "Berlin",
            "postalCode": "<postal code>",
            "district": "<district slug>",
            "latitude": <lat>,
            "longitude": <lon>,
            "websiteUrl": "<homepage>",
            "instagramUrl": "<Instagram profile, or leave the line out>",
            "facebookUrl": "<Facebook page, or leave the line out>",
            "description": "<English sentence>",
            "descriptionLanguage": "en",
            "descriptionAlt": "<German sentence>",
            "descriptionAltLanguage": "de",
            "venueTypes": ["<type>"],
            "programmeUrl": "<programme link>",
            "reviewedAt": "<confirmation time, UTC ISO-8601>"
        },
        "sources": []
    }
    ```

    Then run `scripts/dev-seed-parity.sh` to regenerate `http/importer/dev-seed.http`, and commit both. Never edit `dev-seed.http` by hand.

8. **Prove it locally.** Start the importer on an empty database (`COMPOSE_PROJECT_NAME=<own> POSTGRES_HOST_PORT=<free>`, never the shared one another
   checkout migrated), run `python3 scripts/seed-sources.py --host http://localhost:8081 --apply`, and check each `GET /api/venues/<slug>` answers
   `"imported": false` with its `programmeUrl`. Tear the database down afterwards.

9. **Open the pull request** with [`/open-pr`](open-pr.prompt.md). `Part of #2766` until the last batch, then `Closes #2766`. Its `## After deploy` carries:

    ```markdown
    - [ ] both: run `scripts/seed-sources.py --host <forwarded importer> --apply --yes` and check the new venues answer `"imported": false`
    ```

    And one line per confirmed photo: `- [ ] both: run scripts/venue-images.py --venue "<Name>" --apply`.

## Notes

- **A venue that gets an importer later keeps its row.** `/scaffold-importer` § 6 reuses the block and adds only the source.
- **A venue that closes keeps its row** (ADR-046): set `closedOn` to its last day open, in the seed block and by a data migration for the clusters, as `V119` does for Jonny Knüppel.
- **Why nothing here re-checks a venue**: that is #2812, a monthly probe of the venue sites and a quarterly review keyed on `reviewedAt`.
