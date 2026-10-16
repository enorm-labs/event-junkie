# events-admin

The operator's admin app. It runs on your computer only. It is never built into an image and never deployed ([ADR-045](../docs/adr/ADR-045_ADMIN_FRONTEND_IS_A_LOCAL_APP.md)).

## Run it

```sh
scripts/ej.sh up staging                      # repository root: the tunnel and the importer forward on 18081
cd events-admin && npm ci
npm run dev -- --mode staging                 # http://localhost:5174, /api/admin goes to localhost:18081
npm run dev -- --mode production              # the same against the production forward on 28081
```

The dev server stops with a message when the mode is not `staging` or `production`.

## Check it

```sh
npm run type-check && npm run check:lint && npm run check:format && npm run test:unit -- --run && npm run build-only
```

`build-admin.yml` runs the same commands on each pull request that changes this directory. Its required check is `Build & Test (admin)`.

## Conventions

The versions and the config follow `events-frontend/`. A Dependabot bump to one directory usually needs the same bump in the other. Components under
`src/components/ui/` are vendored shadcn-vue: add one with `npx shadcn-vue add <name>`. That command can add a Google Fonts `@import` to `src/assets/main.css`.
Remove it.

The layout comes from shadcn-vue's `dashboard-01` block. The registry has blocks only in the `new-york-v4` style, so the CLI cannot add one here.
Take a block's files from `https://shadcn-vue.com/r/styles/new-york-v4/<block>.json` by hand. Add its primitives with the CLI, in this app's style.
Tables use TanStack Table v9: `src/lib/sourceTable.ts` declares the features and the columns.

The admin API caps a page at 100 items. A list reads every page and shows an error for a partial listing (`src/api/eventSources.ts`).

Each source row has an Import button and a row menu with Force import and, for a `FAILED` or `RUNNING` source, Retry (`src/components/SourceActions.vue`). The
actions column sticks to the table's right edge. An import runs in the background, so after an action the row reads its source every 3 s until a run newer than
the click has ended: `lastImportAt` is later and the status is not `RUNNING`. It stops after 10 minutes with a "reload later" note, on a read error and when the
row unmounts.

Edit, in the same row menu, opens a form for `enabled`, the import interval and max retries (`src/components/SourceEditForm.vue`). It PATCHes only the changed
fields, and the row shows the source the importer answers. A new source still needs an `EventSource` value in code, so `http/importer/dev-seed.http` creates it.

Events (`src/views/EventsView.vue`) finds any event, to open it in the event editor (`src/components/EventEditor.vue`), as the worklist does. It searches
`GET /api/admin/events` with a part of the title (`q`, any case), a venue (`venueId`) and, while "Show past events" is off, `from` = today in Berlin. Enter or
Search sends the title; a venue or the switch searches at once. The list shows 20 events per page, by date and then start time: oldest first, or newest first while past events are shown. A save in the editor replaces
the row with the saved event. The query string holds the search, so a reload keeps it.

Venues to review (`src/views/VenueReviewView.vue`) lists `GET /api/admin/venues/needs-review`: the venues without an importer whose site failed three monthly checks
in a row (#2812), longest-failing first. "Still open" sets `reviewedAt` to now. "Closed on…" asks for the last open day and sets `closedOn` (ADR-046). The default
day is the day the site began to fail. Each action reads the venue and sends it back in full with one field changed, because the PUT replaces the whole venue.
"Check now" starts one pass of the site check in the background.

Venues (`src/views/VenuesView.vue`) lists every venue by name, with a name filter in the browser. Closed venues (`closedOn` set) come last, greyed. Tags opens
a venue's character tags (#2379) in the order of `VenueCharacterTag`, each with the source URL the importer stores (`src/components/VenueCharacterTags.vue`).
Save PUTs `{ sourceUrl }` to `/api/admin/venues/{id}/character-tags/{tag}`, for a new tag and for a changed URL. The app takes only an `https://` URL and
sends nothing for another. Clear asks for a confirmation, then DELETEs the tag. The page edits no other venue field.

Artists to review (`src/views/ArtistsView.vue`) lists the artists whose MusicBrainz match is `AMBIGUOUS` and who play in the next 14 days, by name, 20 per
page. Review opens a row: the events that bill the artist from today on, with the venue and the other acts (`GET /api/admin/events?artistId=`), and the
MusicBrainz candidates the importer finds for the name. No candidate is chosen at first. Choose, then Confirm, PUTs the candidate's MBID to
`/api/admin/artists/{id}/musicbrainz-id`, and the row leaves the list. When no candidate fits, leave the row: it stays `AMBIGUOUS`. A 503 shows the
importer's message, because MusicBrainz did not answer.

Suggested fixes (`src/views/SuggestionsView.vue`) lists the open rows of `GET /api/admin/data-quality/suggestions`, newest first, 20 per page. Each card shows
the event, the field, the stored and the proposed value, the evidence, the confidence, the check and the model. Accept reads the event and PUTs it back with
the proposed value in its field (`src/api/suggestions.ts`). The answer must list the field in `pinnedFields` (`lineup` for `artists`). Only then does Accept
call `POST …/suggestions/{id}/accept`. Without the pin, the suggestion stays open and the card shows why. For `artists`, Accept keeps each act the event bills
and looks up every other name. It creates an artist when no artist has that name. Reject calls `POST …/suggestions/{id}/dismiss`. A `features` suggestion goes
through `PUT /api/admin/events/{id}/features` with the full proposed set. Accept refuses a slug the app does not know, and leaves the suggestion open when
the event's features changed since the check ran.

Instagram drafts (`src/views/DraftsView.vue`) lists the pending rows of `GET /api/admin/instagram-drafts`, the soonest night first (ADR-048). A card is one
night: one venue and date, with each post that announces it, its acts, its evidence, its confidence and a link to the post. Create event opens the New event
form with `?draft=<id>`. The form fills the venue, the title, the date, the start, the prices and the post as the source URL (`src/api/instagramDrafts.ts`),
and shows the acts, the caption and the text read off the images beside it. Saving creates the event and then calls `POST …/instagram-drafts/{id}/approve` with its id, which approves
every draft of that night. A later post of a night approved before shows as "Update to event #N": the card lists the doors, start and prices that differ
from the event. Apply to event writes them with `PUT /api/admin/events/{id}` and approves the night against that event. Edit event opens the event editor
for the acts. Reject calls `POST …/instagram-drafts/{id}/reject` for the night. Venues read lists the venues the pass reads. Switch off, pressed
twice, calls `DELETE /api/admin/instagram/venues/{slug}`, which also deletes that venue's drafts.

Check with AI runs the event-classification check by hand (#3241), so a fix does not wait for the nightly run. In the event editor
(`src/components/EventEditor.vue`) it asks for a confirmation, because it costs one model call against the month's token budget. Then it calls
`POST /api/admin/data-quality/check/event/{id}` without `force` and shows what the run did: checked, skipped because the text did not change, no answer from
the model or over the budget. In a source's row menu (`src/components/SourceActions.vue`) it confirms and calls `POST …/check/source/{slug}`. That run goes in
the background, so the row says only that it started. New suggestions appear on Suggested fixes. A 409 shows the importer's message: the check is off, as on
staging, or a run is going.
