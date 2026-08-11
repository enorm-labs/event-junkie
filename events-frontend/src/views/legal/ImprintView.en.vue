<script lang="ts" setup>
/**
 * Provider identification under § 5 DDG, plus the disclaimer — English version.
 *
 * The German version, `ImprintView.de.vue`, is the authoritative one (LEGAL.md
 * §6.1), and this page says so. This one exists because a large part of the audience the site
 * addresses reads English, and Art. 12 (1) GDPR / § 5 DDG are judged against the audience
 * addressed — not because German alone would be insufficient.
 *
 * **Change both files or neither.** The facts they state must not diverge; the ones that would be
 * expensive to keep in step by hand — the address, the authority, the review date — come from
 * `@/lib/legal` instead of being typed here.
 */
import { useI18n } from 'vue-i18n'

import LegalPage from '@/components/LegalPage.vue'
import ProvisionalNotice from '@/components/ProvisionalNotice.vue'
import { CONTROLLER } from '@/lib/legal'

const { t } = useI18n()
</script>

<template>
  <LegalPage
    intro="Information required under § 5 DDG (formerly § 5 TMG)."
    show-authoritative-version
    show-review-date
    title="Imprint"
  >
    <ProvisionalNotice />

    <section>
      <h2>Service provider</h2>
      <p>{{ CONTROLLER.name }}</p>
      <p>{{ CONTROLLER.street }}</p>
      <p>{{ CONTROLLER.city }}</p>
      <p>{{ t('legal.country') }}</p>
    </section>

    <section>
      <h2>Contact</h2>
      <p>
        Email:
        <a :href="`mailto:${CONTROLLER.email}`">{{ CONTROLLER.email }}</a>
      </p>
      <p>
        For anything about the site itself — wrong event data, a bug, a venue we should be importing
        — an issue on
        <a href="https://github.com/enorm-labs/event-junkie/issues" rel="noopener" target="_blank">
          GitHub
        </a>
        reaches us fastest and is public, so others can see it has been reported.
      </p>
    </section>

    <section>
      <!-- § 18 (2) MStV: an events guide that curates and presents third-party content can fall
           under the journalistic-editorial test, which is stricter than § 5 DDG. Naming the
           responsible person costs one line and removes the question. -->
      <h2>Responsible for editorial content</h2>
      <p>Under § 18 (2) MStV: {{ CONTROLLER.name }}, address as above.</p>
    </section>

    <section>
      <h2>Not applicable</h2>
      <p>
        Event Junkie is operated by a private individual, not a company: there is no commercial
        register entry, no VAT identification number under § 27a UStG, no supervisory authority and
        no regulated professional title. No consumer contracts are concluded through this site, so
        the EU online dispute resolution platform and § 36 VSBG do not apply. We are neither obliged
        nor willing to take part in dispute resolution proceedings before a consumer arbitration
        board.
      </p>
    </section>

    <section>
      <!-- "Alle Angaben ohne Gewähr", in the formal register (§7.6). The footer carries the plain
           version, which is the one people actually read. -->
      <h2>Liability for content</h2>
      <p>
        The event information on this site is aggregated automatically from publicly available
        sources. It is provided without warranty as to accuracy, completeness or timeliness. Events
        may be moved, sold out or cancelled after we last read the source. Always confirm details
        with the venue or the official ticket seller before travelling. No liability is accepted for
        decisions taken on the basis of the information shown here.
      </p>
    </section>

    <section>
      <h2>Liability for links</h2>
      <p>
        This site links to external websites over whose content we have no control. Responsibility
        for that content lies with the respective operator. The linked pages were checked for legal
        violations at the time of linking; no such violations were apparent. Ongoing monitoring
        without concrete evidence of an infringement is not reasonable; such links will be removed
        promptly once we become aware of one.
      </p>
    </section>

    <section>
      <h2>Copyright</h2>
      <p>
        The source code of this project is published under the Apache License 2.0 — see the
        <a
          href="https://github.com/enorm-labs/event-junkie/blob/main/LICENSE"
          rel="noopener"
          target="_blank"
        >
          licence text
        </a>
        . Event descriptions, images and other material originating from venues, promoters and
        artists remain the property of their respective rights holders and are not covered by that
        licence. If you hold rights in material shown here and would like it removed, write to us
        and we will act promptly.
      </p>
    </section>
  </LegalPage>
</template>
