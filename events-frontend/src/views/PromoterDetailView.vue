<script lang="ts" setup>
import { computed } from 'vue'
import BaseDetailView from '@/components/BaseDetailView.vue'
import DetailLinks from '@/components/DetailLinks.vue'
import { useDetailPage } from '@/composables/useDetailPage'
import { usePromoter } from '@/composables/usePromoter'
import { promoterPageMeta } from '@/lib/pageMeta'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

const {
  entity: promoter,
  description,
  detail,
} = useDetailPage('promoter', usePromoter, promoterPageMeta)

const links = computed(() =>
  promoter.value?.websiteUrl
    ? [{ label: t('common.actions.website'), url: promoter.value.websiteUrl }]
    : [],
)
</script>

<template>
  <BaseDetailView v-bind="detail">
    <template #meta>
      <DetailLinks :links="links" />
    </template>

    <p
      v-if="description"
      :lang="description.lang ?? undefined"
      class="text-prose whitespace-pre-line wrap-anywhere text-foreground/90"
    >
      {{ description.text }}
    </p>
  </BaseDetailView>
</template>
