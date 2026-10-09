<script lang="ts" setup>
/**
 * The venue opt-out route — English version. `ForVenuesView.de.md` is the authoritative one
 * (LEGAL.md §6.1), and this page says so.
 *
 * The route itself is `docs/SCRAPING_POSITION.md` §5. This page is where it gets published: a
 * commitment a venue operator cannot find is not a commitment (#789).
 *
 * "How we read" is §2 of that document in short form. If the importer's behaviour changes, this
 * section changes with it — otherwise the page describes a system that no longer exists.
 *
 * **Change both language versions or neither.** The address and the mailbox come from
 * `@/lib/legal` rather than being typed here.
 *
 * Markdown, compiled to a component at build time (vite.config.ts, #471). A `<section>` line, a
 * blank line, the Markdown, a blank line and `</section>`: without the blank lines the text inside
 * is not read as Markdown.
 */
import { RouterLink } from 'vue-router'

import LegalPage from '@/components/LegalPage.vue'
import { useLocalePath } from '@/composables/useLocalePath'
import { CONTROLLER } from '@/lib/legal'

const localePath = useLocalePath()
</script>

<LegalPage intro="How Event Junkie reads your event page, and how to get back off it." show-authoritative-version title="For venues">

<section>

## The short version

Event Junkie collects publicly announced events in Berlin and links every one of them back to your own page. If you would rather not be listed, write to me: I disable the source and remove your events. I do not ask for a reason.

</section>

<section>

## What the importer reads from your page

Per event: the title, the date, the start time, the venue, the line-up and the kind of event, plus the links to your page and to the ticket seller. It also reads the description and the image you publish yourself.

Event Junkie downloads the image and keeps a copy, so your server does not have to serve it again on every page view. The copy sits on Event Junkie's own servers rather than with a third party, and it goes when you opt out.

</section>

<section>

## How the importer reads

- Once a day, the overview page of each source, its further pages where the list continues, and the detail pages they link to.
- With at least 200 milliseconds between two requests to the same host.
- With `ETag` and `Last-Modified`, so an unchanged overview page costs you nothing but a `304`.
- With a user agent that names the project and links to its source code.
- Without arbitrary crawling: each importer knows exactly one page structure.
- And according to your `robots.txt`, which it checks before every request.

</section>

<section>

## If you would rather not be listed

1. Write to <a :href="'mailto:' + CONTROLLER.email">{{ CONTROLLER.email }}</a> and name the venue.
2. I disable the source. The importer stops reading it.
3. I remove that venue's events from the database.
4. I delete the stored copies of your images from Event Junkie's servers.
5. I answer to confirm, within seven days.

I do not ask for a reason and I do not argue the law with you. A venue that wants out gets out.

**It does not have to be everything.** If it is only the images you mind, or only the description texts, I remove exactly that and leave the events in place. I delete the material you objected to from the database (for images, the stored copies too), and the importer does not import it again. The dates stay findable. Tell me which part you would rather the site did not store.

</section>

<section>

## A `robots.txt` rule does the same

A rule in your `robots.txt` that disallows the pages in question has the same effect and needs no message to me. The importer reads the file once per host per day and checks every request against it. A forbidden address is not fetched: the run fails instead.

</section>

<section>

## If something is merely wrong

For a wrong start time, a moved show or an event that is no longer happening, the route is shorter. Correct it on your own page: the importer reads every venue once a day, and the next read picks up the change. If the site still shows it wrong after that, write to me or report it publicly on [GitHub](https://github.com/enorm-labs/event-junkie/issues).

</section>

<section>

## For promoters and artists

The importer reads venues, not promoters. A promoter or an artist is on this site because a venue credited them. So there is no source to disable, but there is a page of their own, and the same route applies to it as to a venue: write to <a :href="'mailto:' + CONTROLLER.email">{{ CONTROLLER.email }}</a> and name yourself. I correct or remove the name, the website link, the logo or the description, or take the page down altogether. The description texts are written for this site, not copied, except for bands, orchestras and choirs: their short description comes from Wikipedia and is credited to it. I do not ask for a reason and I answer within seven days. The events themselves stay listed under their venue.

</section>

<section>

## Rights in text and images

Descriptions, images and other material originating from venues, promoters and artists remain the property of their respective rights holders. If you hold rights in something shown here and would like it removed, the <RouterLink :to="localePath('/legal/imprint')">imprint</RouterLink> has the route. For an image, that deletes the copy on Event Junkie's servers as well.

</section>

</LegalPage>
