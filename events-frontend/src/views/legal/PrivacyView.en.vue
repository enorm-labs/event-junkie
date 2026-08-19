<script lang="ts" setup>
/**
 * Privacy notice under Art. 13 GDPR — English version. `PrivacyView.de.vue` is the authoritative
 * one (LEGAL.md §6.1); **change both files or neither**, and take shared facts from
 * `@/lib/legal` rather than typing them twice.
 *
 * Structured against the twelve mandatory items in docs/LEGAL.md §7.2 — missing
 * one of them is the usual defect. It deliberately does **not** describe processing that does not
 * happen: no cookie table, no consent withdrawal, no analytics section. A notice describing
 * imaginary processing is as inaccurate as one omitting real processing.
 *
 * Retention periods and the IP-logging decision are still open (§7.5.1); they depend on
 * infrastructure that does not exist yet, so the intent is stated and flagged as provisional
 * rather than invented.
 */
import { useI18n } from 'vue-i18n'

import LegalPage from '@/components/LegalPage.vue'
import ProvisionalNotice from '@/components/ProvisionalNotice.vue'
import { CONTROLLER, SUPERVISORY_AUTHORITY } from '@/lib/legal'

const { t } = useI18n()
</script>

<template>
  <LegalPage
    intro="What data Event Junkie processes, why, and what you can do about it."
    show-authoritative-version
    show-review-date
    title="Privacy"
  >
    <ProvisionalNotice />

    <section>
      <h2>The short version</h2>
      <p>
        Event Junkie sets no cookies, runs no analytics, embeds no third-party fonts, maps, social
        widgets or advertising, and has no user accounts. Nothing you do here is tracked or
        profiled. What remains is the unavoidable minimum: a web server records requests so the site
        can be delivered and defended, and your browser remembers whether you chose light or dark
        mode.
      </p>
    </section>

    <section>
      <h2>1. Controller</h2>
      <p>The controller responsible for data processing on this site is:</p>
      <p>{{ CONTROLLER.name }}</p>
      <p>{{ CONTROLLER.street }}</p>
      <p>{{ CONTROLLER.city }}</p>
      <p>{{ t('legal.country') }}</p>
      <p>
        Email:
        <a :href="`mailto:${CONTROLLER.email}`">{{ CONTROLLER.email }}</a>
      </p>
      <p>
        No data protection officer has been appointed. Neither Art. 37 GDPR nor § 38 BDSG requires
        one here: the site is run by one person, data is not processed on a large scale, and there
        is no systematic monitoring. Use the address above for anything concerning your data.
      </p>
    </section>

    <section>
      <h2>2. Server log files</h2>
      <p>
        Delivering a web page necessarily means your browser sends a request, and the servers
        involved record it. The following may be processed: date and time of the request, the page
        or file requested, the HTTP status and amount of data transferred, the referring page, and
        your browser and operating system identification. Depending on the final infrastructure,
        your IP address may also be recorded.
      </p>
      <p>
        <strong>Purpose:</strong> delivering the site, keeping it available and secure, defending
        against attacks and abuse, and diagnosing faults.
      </p>
      <p>
        <strong>Legal basis:</strong> Art. 6 (1) (f) GDPR. Our legitimate interest is operating a
        functioning, secure website — an interest recognised by Recital 49 GDPR. We do not use this
        data to identify you or to build a profile, and it is not combined with anything else.
      </p>
      <p>
        <strong>Retention:</strong> log data is intended to be deleted after seven days, other than
        entries retained longer where a specific security incident requires it. This period is
        finalised together with the hosting setup and this page is updated to match; the notice will
        always state the period actually configured.
      </p>
      <p>
        <strong>Backups:</strong> the database is backed up, and those backups are kept for
        <strong>30 days</strong>, after which they are deleted automatically. This period is separate
        from the one above: data already deleted from the live system can still exist in a backup for
        up to 30 days. Backups are used solely to recover from a failure — they are not searched and
        not analysed.
      </p>
    </section>

    <section>
      <h2>3. Local storage on your device</h2>
      <p>
        This site sets <strong>no cookies</strong>. It stores exactly one value in your browser's
        local storage: a key named <code>theme</code>, holding <code>light</code> or
        <code>dark</code>, so your display preference survives a reload.
      </p>
      <p>
        This value never leaves your device, is not transmitted to us or to anyone else, and is not
        readable by any third party. It is strictly necessary to provide the display setting you
        explicitly chose, so under § 25 (2) 2 TDDDG it requires no consent — which is why you are
        not being asked to dismiss a banner. You can clear it at any time through your browser
        settings.
      </p>
      <p>
        Should cookies ever be introduced, they will be strictly necessary (essential) ones only —
        no tracking, no analytics tools, no social-media plugins, no advertising trackers.
      </p>
    </section>

    <section>
      <!-- §7.3: artist names are personal data where the artist is a natural person. Naming this
           and offering a removal route is the single most likely real-world complaint to arrive. -->
      <h2>4. Event, artist and venue information</h2>
      <p>
        Event Junkie collects publicly available information about events in Berlin from the
        websites of venues, promoters and ticket sellers, and presents it in one place. Where a
        performing artist is a natural person, their name — and their billing in a line-up — is
        personal data.
      </p>
      <p>
        <strong>Purpose:</strong> informing the public about publicly announced cultural events.
      </p>
      <p>
        <strong>Legal basis:</strong> Art. 6 (1) (f) GDPR. Our legitimate interest is providing an
        events guide. The information concerned has already been published by the organiser for the
        express purpose of promoting the event, and is limited to that professional context — we
        store no contact details, no private addresses and no information about anyone's private
        life.
      </p>
      <p>
        <strong>If you are an artist</strong> and would like your name removed or corrected, write
        to
        <a :href="`mailto:${CONTROLLER.email}`">{{ CONTROLLER.email }}</a>
        and we will deal with it promptly. You do not need to give a reason, and you do not have to
        raise it in public.
      </p>
    </section>

    <section>
      <h2>5. Who else is involved</h2>
      <p>
        We sell nothing, and we share nothing for advertising. Data is disclosed to exactly one
        service provider, a processor bound by a contract under Art. 28 GDPR:
      </p>
      <ul>
        <li>
          <strong>Hosting</strong> — Hetzner Online GmbH, Gunzenhausen, Germany. Servers are located
          in Germany; no transfer outside the EU is involved.
        </li>
      </ul>
      <p>
        There is no content delivery network, no edge provider and no traffic proxy in front of the
        site. Your request reaches our servers in Germany directly, so no third country is involved
        in delivering this site to you and there is no transfer mechanism to disclose.
      </p>
      <p>
        If you contact us by email, or open an issue on GitHub, that data is processed to handle
        your request (Art. 6 (1) (f), or Art. 6 (1) (b) where it concerns a contract). GitHub is
        operated by a US company; choosing to write there rather than by email is yours.
      </p>
    </section>

    <section>
      <h2>6. Your rights</h2>
      <p>Under the GDPR you have the right to:</p>
      <ul>
        <li>access the personal data we hold about you (Art. 15)</li>
        <li>have inaccurate data corrected (Art. 16)</li>
        <li>have data erased (Art. 17)</li>
        <li>have processing restricted (Art. 18)</li>
        <li>receive your data in a portable format (Art. 20)</li>
        <li>withdraw consent at any time, where processing is based on consent (Art. 7 (3))</li>
      </ul>
      <p>
        <strong>Erasure and backups.</strong> An erasure under Art. 17 takes effect immediately in the
        live system. Backups are not edited individually — extracting one person from a backup would
        destroy its purpose, and would only be technically possible by accepting new risks. The data
        therefore expires with the backup itself, so within 30 days at most. If a backup has to be
        restored in the meantime, we re-apply the erasure afterwards.
      </p>
      <p>
        <strong>Right to object (Art. 21 GDPR).</strong> Because we rely on legitimate interests
        under Art. 6 (1) (f), you have the right to object at any time, on grounds relating to your
        particular situation, to processing of your personal data. If you object, we will stop
        processing unless we can demonstrate compelling legitimate grounds that override your
        interests, rights and freedoms. An objection is free and informal — an email is enough.
      </p>
      <p>
        <strong>Right to complain.</strong> You may lodge a complaint with a supervisory authority,
        in particular in the member state of your residence or place of work. The authority
        responsible for us is the
        <a :href="SUPERVISORY_AUTHORITY.url" rel="noopener" target="_blank">
          {{ SUPERVISORY_AUTHORITY.name }}
        </a>
        .
      </p>
    </section>

    <section>
      <h2>7. Is providing data required?</h2>
      <p>
        You are neither legally nor contractually obliged to provide personal data. Technically,
        however, a web page cannot be delivered without your device's IP address — so browsing this
        site without that being processed is not possible. Everything else is voluntary.
      </p>
    </section>

    <section>
      <h2>8. Automated decision-making</h2>
      <p>
        There is none. We do not use automated decision-making or profiling within the meaning of
        Art. 22 GDPR.
      </p>
    </section>

    <section>
      <h2>9. Changes to this notice</h2>
      <p>
        This notice describes what the system actually does, so it changes when the system does. The
        review date at the top of this page tells you when it was last checked.
      </p>
    </section>
  </LegalPage>
</template>
