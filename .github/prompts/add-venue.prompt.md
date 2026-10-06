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

1. **Check it is not there already.** `grep -n '"name": "<name>"' http/importer/dev-seed.http`, and the venue's row in `docs/EVENT_DATA_SOURCES.md`.
   A venue in ✅ Imported has a row. A venue whose site now publishes a dated programme belongs in 🔨 Ready, not here (#2810 has the list).

2. **Look at the venue**, on its own site and in search results:
    - **Open?** A parked domain or a site frozen for years is evidence, not proof. Say which it is, with the URL.
    - **Address and postal code** from the venue's own imprint or contact page.
    - **Programme link**, in this order: the venue's own programme page; its Resident Advisor club or promoter page; a ticket platform it really sells
      on. None at all is allowed: the page then links the website.
    - **Type** as `VenueType` slugs, and **capacity** only where the venue publishes one.

3. **Coordinates**: `python3 scripts/geocode-venues.py "<street> <no>, <postal code> Berlin"`. The key comes from `$GOOGLE_MAPS_API_KEY` or the http-client
   environment. Take the printed point. **Never fill `district` from the geocoder**: pick it from the address, one of the 23 pre-2001 districts, and flag
   one that sits on a border.

4. **Photo, optional.** Only a picture with a stated licence from Wikimedia Commons or Flickr, never one from the venue's site (#808). Propose it as a
   `docs/venue-images/REVIEWED.tsv` row for the operator to mark `CONFIRMED`; `scripts/venue-images.py` writes it after the deploy. No photo is fine: the
   card draws a poster.

5. **Description**, English and German, one sentence each. Read the venue's About or history page and its Wikipedia articles **for facts only**, then
   write our own prose: the venue's text is copyrighted (#808) and Wikipedia's is CC BY-SA, so no sentence is copied or closely paraphrased. The style
   is `/scaffold-importer` § 6: open with what sets the venue apart, name its formats plainly, no hedged tail. Keep each fact's source URL for the
   operator.

6. **Show the operator one table and stop.** Name, address, postal code, district, coordinates, types, programme link, the evidence it is open, and a
   photo candidate if any, and the two descriptions. Mark every cell you are unsure of. Write nothing until the operator confirms or corrects each row.

7. **Write the confirmed venues** into `http/importer/dev-seed.http`, each as one block in alphabetical position, after the imported venues' style:

    ```http
    ### --- Create <Name> venue (not imported, #2766) ---
    POST {{importer-host}}/api/admin/venues
    Content-Type: application/json

    {
        "name": "<Name>",
        "address": "<street no>",
        "city": "Berlin",
        "postalCode": "<postal code>",
        "district": "<district slug>",
        "latitude": <lat>,
        "longitude": <lon>,
        "websiteUrl": "<homepage>",
        "description": "<English sentence>",
        "descriptionLanguage": "en",
        "descriptionAlt": "<German sentence>",
        "descriptionAltLanguage": "de",
        "venueTypes": ["<type>"],
        "programmeUrl": "<programme link>",
        "reviewedAt": "<confirmation time, UTC ISO-8601>"
    }

    > {%
        client.test("<Name> venue created", function () {
            client.assert(response.status === 201, "Expected 201 but got " + response.status);
        });
    %}
    ```

    List the venue under `Venues without an importer (alphabetical)` in the file's header, as `Name — programme link`; the first batch adds that list
    after the `Sources` one.

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
- **A venue that closes** is #2731's question. Until it is answered, removal is a data migration, as V051 was.
- **Why nothing here re-checks a venue**: that is #2812, a monthly probe of the venue sites and a quarterly review keyed on `reviewedAt`.
