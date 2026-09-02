<script lang="ts" setup>
/**
 * Says out loud that the legal pages are not final yet — and currently renders nothing.
 *
 * Three facts could each make a legal page state something untrue: placeholder contact details
 * (§8.3), infrastructure that is decided rather than built (ADR-012), and an Art. 28 contract not
 * concluded with the processor named there (#275). A page presenting any of them as settled fact is
 * inaccurate, and an inaccurate notice is the defect these pages exist to avoid.
 *
 * **All three flags are `false`**, so this emits no element at all. That is the go-live state.
 *
 * **It is kept rather than deleted.** Every flag is re-armable and the failure each guards is
 * silent — a new processor, an edge provider in front of the origin, a lapsed address rental. Set
 * one back to `true` and the banner returns with nothing to rebuild;
 * `views/legal/__tests__/legalViews.spec.ts` holds the pages and the flags in step both ways.
 */
import { useI18n } from 'vue-i18n'

import {
  CONTACT_DETAILS_ARE_PROVISIONAL,
  INFRASTRUCTURE_IS_PROPOSED,
  PROCESSOR_CONTRACTS_PENDING,
} from '@/lib/legal'

const { t } = useI18n()
</script>

<template>
  <aside
    v-if="
      CONTACT_DETAILS_ARE_PROVISIONAL || INFRASTRUCTURE_IS_PROPOSED || PROCESSOR_CONTRACTS_PENDING
    "
    class="rounded-lg border border-border bg-muted/50 p-4 text-sm"
  >
    <p class="font-medium text-foreground">{{ t('legal.notFinal') }}</p>
    <ul class="mt-2 list-disc space-y-1 pl-5">
      <li v-if="CONTACT_DETAILS_ARE_PROVISIONAL">{{ t('legal.provisionalContact') }}</li>
      <li v-if="INFRASTRUCTURE_IS_PROPOSED">{{ t('legal.provisionalInfrastructure') }}</li>
      <li v-if="PROCESSOR_CONTRACTS_PENDING">{{ t('legal.provisionalProcessorContract') }}</li>
    </ul>
  </aside>
</template>
