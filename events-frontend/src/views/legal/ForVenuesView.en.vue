<script lang="ts" setup>
/**
 * The venue opt-out route — English version. `ForVenuesView.de.vue` is the authoritative one
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
 */
import { RouterLink } from 'vue-router'

import LegalPage from '@/components/LegalPage.vue'
import { useLocalePath } from '@/composables/useLocalePath'
import { CONTROLLER } from '@/lib/legal'

const localePath = useLocalePath()
</script>

<template>
  <LegalPage
    intro="How Event Junkie reads your event page — and how to get back off it."
    show-authoritative-version
    title="For venues"
  >
    <section>
      <h2>The short version</h2>
      <p>
        Event Junkie collects publicly announced events in Berlin and links every one of them back
        to your own page. If you would rather not be listed, write to us: we disable the source and
        remove your events. We do not ask for a reason.
      </p>
    </section>

    <section>
      <h2>What we read from your page</h2>
      <p>
        Per event: the title, the date, the start time, the venue, the line-up and the kind of
        event, plus the links to your page and to the ticket seller. Along with the description and
        the image you publish yourself.
      </p>
      <p>
        We download the image and keep a copy, so your server does not have to serve it again on
        every page view. The copy sits with us rather than with a third party, and it goes when you
        opt out.
      </p>
    </section>

    <section>
      <h2>How we read</h2>
      <ul>
        <li>Once a day, one overview page per source, plus the detail pages it links to.</li>
        <li>With at least 200 milliseconds between two requests to the same host.</li>
        <li>
          With <code>ETag</code> and <code>Last-Modified</code>, so an unchanged page costs you
          nothing but a <code>304</code>.
        </li>
        <li>With a user agent that names the project and links to its source code.</li>
        <li>Without arbitrary crawling: each importer knows exactly one page structure.</li>
        <li>And according to your <code>robots.txt</code>, which we check before every request.</li>
      </ul>
    </section>

    <section>
      <h2>If you would rather not be listed</h2>
      <ol>
        <li>
          Write to
          <a :href="`mailto:${CONTROLLER.email}`">{{ CONTROLLER.email }}</a>
          and name the venue.
        </li>
        <li>We disable the source. The importer stops reading it.</li>
        <li>We remove that venue's events from the database.</li>
        <li>We delete the stored copies of your images from our servers.</li>
        <li>We answer to confirm, within seven days.</li>
      </ol>
      <p>
        We do not ask for a reason and we do not argue the law with you. A venue that wants out gets
        out.
      </p>
      <p>
        <strong>It does not have to be everything.</strong> If it is only the images you mind, or
        only the description texts, we remove exactly that and leave the events in place. We delete
        the material you objected to from our database — for images, the stored copies too — and do
        not import it again. The dates stay findable. Tell us which part you would rather we did not
        store.
      </p>
    </section>

    <section>
      <h2>A <code>robots.txt</code> rule does the same</h2>
      <p>
        A rule in your <code>robots.txt</code> that disallows the pages in question has the same
        effect and needs no message to us. We read the file once per host per day and check every
        request against it. A forbidden address is not fetched: the run fails instead.
      </p>
    </section>

    <section>
      <h2>If something is merely wrong</h2>
      <p>
        For a wrong start time, a moved show or an event that is no longer happening, the route is
        shorter. Write to us, or report it publicly on
        <a href="https://github.com/enorm-labs/event-junkie/issues" rel="noopener" target="_blank">
          GitHub </a
        >. Either is faster than our next read.
      </p>
    </section>

    <section>
      <h2>Rights in text and images</h2>
      <p>
        Descriptions, images and other material originating from venues, promoters and artists
        remain the property of their respective rights holders. If you hold rights in something
        shown here and would like it removed, the
        <RouterLink :to="localePath('/legal/imprint')">imprint</RouterLink>
        has the route. For an image, that deletes the copy on our servers as well.
      </p>
    </section>
  </LegalPage>
</template>
