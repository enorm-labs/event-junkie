# Data Model

The domain model: what is stored, and how the pieces relate. It exists to capture music events scraped from Berlin venue websites — every source is listed in
[EVENT_DATA_SOURCES.md](EVENT_DATA_SOURCES.md).

## The short version

An `event` belongs to one `venue` and links to `artist`, `promoter` and `genre_tag` through join tables.
`event.sourceId` is what makes imports idempotent — an upsert keyed on it, not on the title. `event_source` holds the
per-venue import configuration and the conditional-request headers (ETag, Last-Modified) that let an unchanged page
cost one 304. It also holds the licence status that decides whether an event's description and image are served.

**Everything lives in the `events` schema, never `public`.** The name comes from the `EVENTS_SCHEMA` constant in
`events-core`, not from a YAML property. See [ADR-004](adr/ADR-004_DEDICATED_DATABASE_SCHEMA.md) and
[.github/instructions/architecture.instructions.md](../.github/instructions/architecture.instructions.md). Migrations
are owned by the importer ([ADR-005](adr/ADR-005_MIGRATIONS_OWNED_BY_IMPORTER.md)).

## Class Diagram

```mermaid
classDiagram
    direction LR

    class Venue {
        Long id
        String name
        String slug
        String address
        String city
        String postalCode
        BigDecimal latitude
        BigDecimal longitude
        String websiteUrl
        String imageUrl
        Instant createdAt
        Instant updatedAt
    }

    class Event {
        Long id
        String title
        String subtitle
        String description
        EventType eventType
        EventStatus status
        String slug
        LocalDate eventDate
        LocalTime doorsTime
        LocalTime startTime
        String imageUrl
        String sourceUrl
        String sourceId
        String ticketUrl
        String facebookEventUrl
        String genre
        BigDecimal pricePresale
        BigDecimal priceBoxOffice
        String priceCurrency
        String priceNote
        boolean soldOut
        Instant createdAt
        Instant updatedAt
    }

    class Artist {
        Long id
        String name
        String slug
        String description
        String imageUrl
        String websiteUrl
        String facebookUrl
        String instagramUrl
        String youtubeUrl
        Instant createdAt
        Instant updatedAt
    }

    class Promoter {
        Long id
        String name
        String slug
        String websiteUrl
        String imageUrl
        Instant createdAt
        Instant updatedAt
    }

    class GenreTag {
        Long id
        String name
        String slug
        Instant createdAt
        Instant updatedAt
    }

    class LineupEntry {
        ArtistRole role
        int billingOrder
    }

    class EventType {
        <<enumeration>>
        CONCERT
        FESTIVAL
        PARTY
        QUIZ
        CLUB_NIGHT
        SHOW
        OTHER
    }

    class EventStatus {
        <<enumeration>>
        SCHEDULED
        RELOCATED
        CANCELLED
        POSTPONED
    }

    class ArtistRole {
        <<enumeration>>
        HEADLINER
        SUPPORT
        DJ
    }

    Venue "1" <-- "*" Event: venue
    Event "1" --> "*" LineupEntry: lineup
    LineupEntry "*" --> "1" Artist: artist
    Event "*" --> "*" Promoter: promoters
    Event "*" --> "*" GenreTag: genreTags
    Event --> EventType: eventType
    Event --> EventStatus: status
    LineupEntry --> ArtistRole: role
```

Domain classes are organized by feature in `events-core`:

```
de.norm.events
├── artist/
│   └── Artist.kt
├── event/
│   └── Event.kt          (Event, EventType, EventStatus, LineupEntry, ArtistRole)
├── genretag/
│   └── GenreTag.kt
├── promoter/
│   └── Promoter.kt
└── venue/
    └── Venue.kt
```

## Entities

### Venue

Represents a physical venue where music events take place (e.g. Astra Kulturhaus, Badehaus Berlin, SO36).

| Field         | Type           | Nullable | Description                                      | Example                                           |
| ------------- | -------------- | -------- | ------------------------------------------------ | ------------------------------------------------- |
| `id`          | `BIGINT`       | No       | Auto-generated primary key                       | `42`                                              |
| `name`        | `TEXT`         | No       | Display name of the venue                        | `Astra Kulturhaus`                                |
| `slug`        | `TEXT` (UQ)    | No       | URL-friendly identifier                          | `astra-kulturhaus`                                |
| `address`     | `TEXT`         | Yes      | Street address                                   | `Revaler Str. 99`                                 |
| `city`        | `TEXT`         | No       | City (defaults to `Berlin`)                      | `Berlin`                                          |
| `postal_code` | `TEXT`         | Yes      | Postal code                                      | `10245`                                           |
| `latitude`    | `DECIMAL(9,6)` | Yes      | Geographic latitude                              | `52.507242`                                       |
| `longitude`   | `DECIMAL(9,6)` | Yes      | Geographic longitude                             | `13.451803`                                       |
| `website_url` | `TEXT`         | Yes      | Venue's official website                         | `https://www.astra-berlin.de`                     |
| `image_url`   | `TEXT`         | Yes      | Venue logo or photo                              | `https://example.com/astra.jpg`                   |
| `description` | `TEXT`         | Yes      | Short prose description shown on the detail page | `A former power plant turned techno institution…` |
| `created_at`  | `TIMESTAMPTZ`  | No       | Record creation timestamp                        |                                                   |
| `updated_at`  | `TIMESTAMPTZ`  | No       | Last modification timestamp                      |                                                   |

### Event

Core entity representing a single music event at a venue on a specific date.

| Field                | Type            | Nullable | Description                                                     | Example                                                    |
| -------------------- | --------------- | -------- | --------------------------------------------------------------- | ---------------------------------------------------------- |
| `id`                 | `BIGINT`        | No       | Auto-generated primary key                                      | `101`                                                      |
| `venue_id`           | `BIGINT` FK     | No       | References `venue.id`                                           | `42`                                                       |
| `title`              | `TEXT`          | No       | Event headline                                                  | `THE ADICTS`                                               |
| `subtitle`           | `TEXT`          | Yes      | Tour name or support acts line                                  | `„Adios Amigos Tour 2026" + Support: MAID OF ACE + KAOS`   |
| `description`        | `TEXT`          | Yes      | Longer description / artist bio                                 | `Formed in Ipswich in the late 1970s…`                     |
| `event_type`         | `TEXT`          | No       | Event category (see `EventType` enum)                           | `CONCERT`                                                  |
| `status`             | `TEXT`          | No       | Scheduling status (see `EventStatus` enum, default `SCHEDULED`) | `SCHEDULED`                                                |
| `slug`               | `TEXT`          | No       | URL-friendly identifier                                         | `2026-06-12-the-adicts`                                    |
| `event_date`         | `DATE`          | No       | Calendar date of the event                                      | `2026-06-12`                                               |
| `doors_time`         | `TIME`          | Yes      | When doors open                                                 | `19:00`                                                    |
| `start_time`         | `TIME`          | Yes      | When the show starts                                            | `20:00`                                                    |
| `image_url`          | `TEXT`          | Yes      | Event poster / flyer URL                                        | `https://example.com/adicts-poster.jpg`                    |
| `source_url`         | `TEXT`          | Yes      | Original URL on the venue website                               | `https://www.astra-berlin.de/events/2026-06-12-the-adicts` |
| `source_id`          | `TEXT` (UQ)     | No       | Unique import key for idempotent upserts                        | `astra:2026-06-12-the-adicts`                              |
| `ticket_url`         | `TEXT`          | Yes      | External ticket shop URL (eventim, dice, etc.)                  | `https://www.eventim.de/event/...`                         |
| `facebook_event_url` | `TEXT`          | Yes      | Direct link to the Facebook event page                          | `https://fb.me/e/60JFqXAUr`                                |
| `genre`              | `TEXT`          | Yes      | Raw music genre/style text from the source venue (display only) | `Punk`                                                     |
| `price_presale`      | `DECIMAL(10,2)` | Yes      | Presale ticket price (Vorverkauf)                               | `38.00`                                                    |
| `price_box_office`   | `DECIMAL(10,2)` | Yes      | Box office ticket price (Abendkasse)                            | `45.00`                                                    |
| `price_currency`     | `TEXT`          | No       | ISO 4217 currency code (default EUR)                            | `EUR`                                                      |
| `price_note`         | `TEXT`          | Yes      | Free-form pricing info for non-standard pricing                 | `donation 2-5€`                                            |
| `sold_out`           | `BOOLEAN`       | No       | Whether all tickets are sold out                                | `false`                                                    |
| `created_at`         | `TIMESTAMPTZ`   | No       | Record creation timestamp                                       |                                                            |
| `updated_at`         | `TIMESTAMPTZ`   | No       | Last modification timestamp                                     |                                                            |

### Artist

Represents a musical artist or band. Normalized separately so artists can appear in multiple events.

| Field           | Type          | Nullable | Description                 | Example                                        |
| --------------- | ------------- | -------- | --------------------------- | ---------------------------------------------- |
| `id`            | `BIGINT`      | No       | Auto-generated primary key  | `7`                                            |
| `name`          | `TEXT`        | No       | Stage or band name          | `The Adicts`                                   |
| `slug`          | `TEXT` (UQ)   | No       | URL-friendly identifier     | `the-adicts`                                   |
| `description`   | `TEXT`        | Yes      | Artist biography            | `Formed in Ipswich in the late 1970s…`         |
| `image_url`     | `TEXT`        | Yes      | Photo or logo URL           | `https://example.com/adicts.jpg`               |
| `website_url`   | `TEXT`        | Yes      | Official homepage           | `https://theadicts.net/`                       |
| `facebook_url`  | `TEXT`        | Yes      | Facebook page URL           | `https://www.facebook.com/theadicts`           |
| `instagram_url` | `TEXT`        | Yes      | Instagram profile URL       | `https://www.instagram.com/theadictsofficial/` |
| `youtube_url`   | `TEXT`        | Yes      | YouTube channel URL         | `https://www.youtube.com/@theadictsofficial`   |
| `created_at`    | `TIMESTAMPTZ` | No       | Record creation timestamp   |                                                |
| `updated_at`    | `TIMESTAMPTZ` | No       | Last modification timestamp |                                                |

### Promoter

Represents an event promoter or presenter. Shared across events and venues.

| Field         | Type          | Nullable | Description                 | Example                                |
| ------------- | ------------- | -------- | --------------------------- | -------------------------------------- |
| `id`          | `BIGINT`      | No       | Auto-generated primary key  | `3`                                    |
| `name`        | `TEXT`        | No       | Promoter name               | `36 Concerts`                          |
| `slug`        | `TEXT` (UQ)   | No       | URL-friendly identifier     | `36-concerts`                          |
| `website_url` | `TEXT`        | Yes      | Website or social page      | `https://www.facebook.com/36Concerts/` |
| `image_url`   | `TEXT`        | Yes      | Logo image URL              | `https://example.com/36-concerts.jpg`  |
| `created_at`  | `TIMESTAMPTZ` | No       | Record creation timestamp   |                                        |
| `updated_at`  | `TIMESTAMPTZ` | No       | Last modification timestamp |                                        |

### event_artist (Join Table / LineupEntry)

Links events to artists with role and billing order to model the lineup. In the domain model the `LineupEntry` class
represents this, and it holds a full `Artist` object. The persistence layer (`EventArtistEntity`) maps to this join
table with foreign keys.

| Field           | Type        | Nullable | Description                         | Example     |
| --------------- | ----------- | -------- | ----------------------------------- | ----------- |
| `id`            | `BIGINT`    | No       | Auto-generated primary key          | `12`        |
| `event_id`      | `BIGINT` FK | No       | References `event.id`               | `101`       |
| `artist_id`     | `BIGINT` FK | No       | References `artist.id`              | `7`         |
| `role`          | `TEXT`      | No       | `HEADLINER`, `SUPPORT`, or `DJ`     | `HEADLINER` |
| `billing_order` | `INT`       | No       | Position in lineup (0 = top-billed) | `0`         |

Unique constraint on `(event_id, artist_id)` prevents duplicate artist-event associations.

### EventPromoter (Join Table)

Links events to their promoters/presenters.

| Field         | Type        | Nullable | Description              | Example |
| ------------- | ----------- | -------- | ------------------------ | ------- |
| `event_id`    | `BIGINT` FK | No       | References `event.id`    | `101`   |
| `promoter_id` | `BIGINT` FK | No       | References `promoter.id` | `3`     |

Composite primary key `(event_id, promoter_id)`.

### GenreTag

Represents a normalized music genre label used for structured filtering. Genre tags are auto-created during event imports from the raw genre text on events. The
raw text is preserved for display, and these tags enable frontend filtering.

| Field        | Type          | Nullable | Description                 | Example   |
| ------------ | ------------- | -------- | --------------------------- | --------- |
| `id`         | `BIGINT`      | No       | Auto-generated primary key  | `1`       |
| `name`       | `TEXT`        | No       | Canonical display name      | `Hip Hop` |
| `slug`       | `TEXT` (UQ)   | No       | URL-friendly identifier     | `hip-hop` |
| `created_at` | `TIMESTAMPTZ` | No       | Record creation timestamp   |           |
| `updated_at` | `TIMESTAMPTZ` | No       | Last modification timestamp |           |

### EventGenreTag (Join Table)

Links events to their normalized genre tags (many-to-many).

| Field          | Type        | Nullable | Description                | Example |
| -------------- | ----------- | -------- | -------------------------- | ------- |
| `id`           | `BIGINT`    | No       | Auto-generated primary key | `5`     |
| `event_id`     | `BIGINT` FK | No       | References `event.id`      | `101`   |
| `genre_tag_id` | `BIGINT` FK | No       | References `genre_tag.id`  | `1`     |

Unique constraint on `(event_id, genre_tag_id)` prevents duplicate tag-event associations.

## Design Decisions

### Idempotent Imports via `source_id`

Each event has a unique `source_id` (e.g. `astra:2026-06-12-the-adicts`) that identifies it from the import source. This allows the importer to use upsert
semantics: if an event with the same `source_id` already exists, it gets updated rather than duplicated. This is critical for scheduled re-imports.

### Rich Domain Model in `events-core`

The Kotlin domain classes in `events-core` use embedded object references (e.g. `Event.venue: Venue`) rather than raw foreign key IDs. This makes the domain
model expressive and self-documenting. The persistence layer in `events-importer`
and `events-bff` maps between these domain objects and the flat relational schema.

### Separate `LineupEntry` / `event_artist` Join Entity

A dedicated join entity (rather than just a list of artist IDs on the event) captures:

- **Role** — whether the artist is a headliner, support act, or DJ
- **Billing order** — the position in the lineup (lower = higher on the bill)

This information is displayed prominently on venue websites and is important for the UI.

In the domain model, `LineupEntry` holds a full `Artist` reference (consistent with how `Event`
references `Venue` and `Promoter`). The persistence layer uses `EventArtistEntity` with foreign key IDs to map to the `event_artist` database table.

### Inline Price Fields Instead of Separate Table

Pricing is embedded directly on the `event` table as `price_presale`, `price_box_office`, `price_currency`, and
`price_note` rather than in a separate `event_price` table. This was chosen because:

- Berlin venue websites consistently show at most two price types: presale (Vorverkauf) and box office (Abendkasse)
- Some venues use non-standard pricing (e.g. "donation 2-5€") captured by `price_note`
- A 1:1 relationship between event and its price record adds unnecessary join overhead
- Nullable `DECIMAL` columns cleanly express "no price information available"
- Keeps queries simple — no joins needed to display event listings with prices

### Genre Tags vs. Genre Enum

The `genre` field on events is free-text scraped from venue websites. A separate `genre_tag` table with a many-to-many join table (`event_genre_tag`) provides
normalized genre tags for structured filtering. This approach was chosen over an enum because:

- Scraped genre data is messy and inconsistent across venues (e.g. "Hip-Hop", "Hip Hop", "Rap", "HipHop")
- Events frequently have multiple genres (e.g. "Indie, Rock, Folk")
- A fixed enum would require constant updates as new venues produce new genre labels
- The `GenreNormalizer` maps known synonyms to canonical names while preserving unknown genres as-is
- The raw genre text is kept on the event for display, and normalized tags enable structured filtering
- Genre tags are auto-created during imports — no manual curation required

### External Ticket URL

The `ticket_url` field stores a link to the external ticket shop (eventim.de, ticketshop.live, vvk.link, dice.fm, etc.). This is distinct from `source_url` (the
venue's own event page). Nearly every event on Berlin venue websites links to an external ticket provider, and this information is valuable for users.

### Event Status for Relocated/Cancelled Events

Berlin venues frequently update event listings to mark events as relocated ("VERLEGT"), cancelled, or postponed. The `status` field captures this state so the
frontend can display appropriate badges and the importer can update events without losing the original record.

### `slug` Fields on All Main Entities

URL-friendly slugs are stored on venues, artists, promoters, genre tags, and events. These are used for:

- Clean REST API URLs (e.g. `/venues/astra-kulturhaus`)
- Matching against source website URL patterns during import
- Human-readable identifiers in logs and debugging

### Per-Source Licence Status on `event_source`

Five columns, added in `V006` for [#283](https://github.com/enorm-labs/event-junkie/issues/283):

| Column                | Holds                                                                        |
| --------------------- | ---------------------------------------------------------------------------- |
| `description_licence` | `PERMITTED`, `PROHIBITED`, `UNCLEAR`, or null while the source is unreviewed |
| `image_licence`       | The same question for images, answered separately                            |
| `licence_reviewed_at` | The date of the review                                                       |
| `licence_source_url`  | The page that was read                                                       |
| `licence_note`        | The sentence that decided it                                                 |

**Two status columns rather than one**, because a venue's own prose and its agency photographs are different answers. A
single column would force the stricter one onto both.

**Three evidence columns beside them**, for the reason `V005` gives about `robots.txt`. A status with no page behind it
cannot be checked by the next person.

**Null is a fourth state and it is not `UNCLEAR`.** Null means nobody reviewed the source. `UNCLEAR` means somebody read
its pages and found nothing that decides it. Both display. To merge them would lose the only signal that says how much
review work is left.

**The read rule is fail-open: only `PROHIBITED` withholds.** It lives in the BFF rather than the frontend, so no API
consumer can get it wrong. [`docs/SCRAPING_POSITION.md`](SCRAPING_POSITION.md) §3.1 records what that accepts.

A `CHECK` constraint holds each column to the vocabulary, because the enum cannot reach a hand-edited row.

### Cached Venue Images on `cached_image`

`V008` adds two tables so a venue image can be served from our own origin, and the visitor's browser
never contacts the venue ([ADR-019](adr/ADR-019_VENUE_IMAGE_DELIVERY.md)).

| Table                  | Holds                                                                         |
| ---------------------- | ----------------------------------------------------------------------------- |
| `cached_image`         | One row per venue image URL: the hash, the type, the size, the intrinsic size |
| `cached_image_variant` | One row per file we serve: a width, a format and its object key               |

**Two tables rather than one.** A single row cannot hold several widths in several formats, and
putting them in columns would fix the set of derivatives in the schema.

**`event.image_url` is not touched.** It keeps the venue's URL, which is the provenance and what a
refetch needs. The BFF substitutes our URL when it builds a response, so the venue's URL never
reaches a browser.

**`failed_at` and `failure_reason` are a negative cache, not an error log.** The import runs daily,
so without them a dead URL is requested every night forever — load on a venue that returns nothing.
`deleted_at` is set by the takedown route rather than by a `DELETE`. A removed image is therefore
not fetched again by the next pass, which still sees the URL on the page.

**A source that prohibits its images has no URL here to find.**
[#807](https://github.com/enorm-labs/event-junkie/issues/807) made the importer store `null` for a
prohibited `image_url`. The exclusion is structural.

### PostgreSQL-Specific Choices

- **`GENERATED ALWAYS AS IDENTITY`** for auto-incrementing IDs (SQL standard, preferred over `SERIAL`)
- **`TIMESTAMPTZ`** for all timestamps (timezone-aware, avoids surprises with UTC conversions)
- **`TEXT`** over `VARCHAR(n)` (PostgreSQL treats them identically, and `TEXT` avoids arbitrary length limits)
- **`DECIMAL(10,2)`** for prices (exact arithmetic, no floating-point rounding)
- **`DECIMAL(9,6)`** for coordinates (6 decimal places ≈ ~11 cm precision)

## Flyway Migration

The database schema is managed by Flyway in `events-importer`:

```
events-importer/src/main/resources/db/migration/
└── V001__create_initial_schema.sql
```
