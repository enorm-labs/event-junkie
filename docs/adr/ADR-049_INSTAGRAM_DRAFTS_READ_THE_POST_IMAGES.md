# ADR-049: The Instagram drafts read the images of a post as well as its caption

## Status

**Accepted (2026-10-16). The importer sends the images of an Instagram post to the model with the caption. The model reads the line-up, the day and the
title from the flyer. A person approves every draft, as before. No image is stored, and no image reaches the public site.**

Decided in [#3375](https://github.com/enorm-labs/event-junkie/issues/3375), option 2, made wider: every image of a post is read.

**Supersedes rule 3 of [ADR-048](ADR-048_INSTAGRAM_POSTS_THROUGH_BUSINESS_DISCOVERY.md).** That rule said that the importer never reads `media_url` and that
no image goes to the model. All other rules of ADR-048 stand. ADR-048 keeps its reasoning, so this is a separate document and not an edit.

## Context

ADR-048 names the trigger for this decision: "The missing acts matter more than the cost of reading flyers."

The first pass on staging (2026-10-16) met that trigger. It found 3 RSO nights and 0 of 10 acts, because the captions name no line-up. The flyers name the
full line-up. Example, 16 Oct:

| Where   | Text                                                                                                                       |
| ------- | -------------------------------------------------------------------------------------------------------------------------- |
| Caption | selfcontrolbln returns to the SUMME floor next Friday, October 16th✖️ / Artwork by dominikkeller_of & yung_huhn            |
| Flyer   | SELF.CONTROL. · FR 16 Oct · RSO BERLIN · Doors 23:00 · Cosmo Vitelli · CYRK live · Moderna · Giulia Gutterer · Sesto Senso |

A probe of `rso.berlin` through Business Discovery returned an image for each type of post:

| `media_type`     | Image the API gives                                      |
| ---------------- | -------------------------------------------------------- |
| `IMAGE`          | `media_url`                                              |
| `CAROUSEL_ALBUM` | `media_url`, and `children{media_url}` for each slide    |
| `VIDEO` (reel)   | `thumbnail_url`, the cover frame. `media_url` is a video |

The constraints:

- **The model only suggests.** ADR-041 rule 3. A person applies every result.
- **A flyer often shows a photograph of a person.** Sending it to Anthropic is a new kind of data for that processor (AGENTS.md § Privacy & GDPR).
- **`media_url` is a signed CDN link, and it expires.** The importer must download the image in the same pass.
- **An image costs about 1,000–1,600 input tokens on Haiku**, about three times the cost of a caption.

## Candidate options

1. **Keep rule 3.** The line-up stays a fill by hand. No cost.
2. **Read the images of `IMAGE` and `CAROUSEL_ALBUM` posts.** A reel stays caption-only.
3. **Read the image only when the caption names no acts.** The same as 2, with fewer calls.

## Decision

**Option 2, made wider.** The operator wants no event and no fact to be missed, and the line-up most of all. A reel's cover frame and a post with no caption
can also announce a night, so they are read too.

The rules:

1. **The importer reads the images of a post.** For `IMAGE`, this is `media_url`. For `CAROUSEL_ALBUM`, it is each slide, up to
   `app.instagram.max-images-per-post` (4). For `VIDEO`, it is `thumbnail_url`.
2. **A post with no caption goes to the model when it has an image.** A post with no caption and no image is not sent.
3. **The importer downloads each image in the pass, sends it and then drops it.** No image is written to disk, to the database or to object storage. The
   download goes only to `cdninstagram.com` or `fbcdn.net`, over HTTPS, without the Meta token. It takes JPEG, PNG and WebP up to 5 MB.
4. **The model copies the text of the images into `flyerText`.** A date, a time, an act or a price must occur in the caption or in that text. The validator
   cannot prove that the image shows the text. The person who approves the draft compares the text with the post.
5. **The text from the images follows the caption.** It stays only while its draft waits (ADR-048 rule 6). A CHECK constraint keeps it on unjudged drafts
   only.
6. **LEGAL.md §7.3a and both privacy notices change with this code.** They say that the images of a post go to Anthropic, and that they can show people.

## Consequences

- **A draft can carry the line-up.** RSO publishes its line-ups only on the flyer.
- **Photographs of people go to Anthropic.** Anthropic's DPA covers them as it covers the captions. The categories of the Hetzner AVV do not change, because no
  image is stored at Hetzner. LEGAL.md §7.3a gives the reasons.
- **A pass costs more tokens.** A carousel of four slides costs about 6,000 input tokens. `maxPerRun` counts calls, not tokens.
- **The model can invent text from an image.** The validator does not catch a fact that the model writes into `flyerText` and into the answer. The approval by
  a person is the check.
- **Meta can withhold `media_url`**, for example for a post with copyrighted music. Then the post is read from its caption only.
- **Posts read before this change are not read again.** Their drafts keep the facts from the caption only.

## When to revisit

- The cost of the images exceeds the monthly limit of ADR-041.
- The drafts from images show many acts or dates that the flyer does not show.
- Meta stops giving `media_url` or `thumbnail_url` through Business Discovery.

## References

- [#3375](https://github.com/enorm-labs/event-junkie/issues/3375), the decision issue
- [ADR-048](ADR-048_INSTAGRAM_POSTS_THROUGH_BUSINESS_DISCOVERY.md) and [ADR-041](ADR-041_AI_ASSISTED_DATA_QUALITY.md)
- [Business Discovery reference](https://developers.facebook.com/docs/instagram-platform/instagram-graph-api/reference/ig-user/business_discovery)
- [Claude vision](https://docs.anthropic.com/en/docs/build-with-claude/vision), the image sizes and costs
- `docs/LEGAL.md` §7.3a, the processor entry
