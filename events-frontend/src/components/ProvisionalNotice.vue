<script lang="ts" setup>
/**
 * Says out loud that the legal pages are not final yet.
 *
 * The contact details are placeholders (§8.3) and the infrastructure the privacy notice describes
 * is decided rather than built (ADR-012 is `Accepted`, but nothing is deployed). A legal page that
 * presents either as settled fact is inaccurate — and an inaccurate notice is the defect these
 * pages exist to avoid. Both flags must be `false` before go-live; a unit test keeps them honest.
 *
 * Shared by both language versions of the pages, which is why its copy lives in the message
 * catalogue while the pages around it do not: these are two flat sentences with no inline markup,
 * exactly the shape JSON handles well.
 */
import { useI18n } from 'vue-i18n'

import { CONTACT_DETAILS_ARE_PROVISIONAL, INFRASTRUCTURE_IS_PROPOSED } from '@/lib/legal'

const { t } = useI18n()
</script>

<template>
  <aside
    v-if="CONTACT_DETAILS_ARE_PROVISIONAL || INFRASTRUCTURE_IS_PROPOSED"
    class="rounded-lg border border-border bg-muted/50 p-4 text-sm"
  >
    <p class="font-medium text-foreground">{{ t('legal.notFinal') }}</p>
    <ul class="mt-2 list-disc space-y-1 pl-5">
      <li v-if="CONTACT_DETAILS_ARE_PROVISIONAL">{{ t('legal.provisionalContact') }}</li>
      <li v-if="INFRASTRUCTURE_IS_PROPOSED">{{ t('legal.provisionalInfrastructure') }}</li>
    </ul>
  </aside>
</template>
