<script lang="ts" setup>
import { computed } from 'vue'
import BaseDetailView from '@/components/BaseDetailView.vue'
import DetailLinks, { type DetailLink } from '@/components/DetailLinks.vue'
import { useDetailPage } from '@/composables/useDetailPage'
import { usePromoter } from '@/composables/usePromoter'
import { promoterPageMeta } from '@/lib/pageMeta'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

const { entity: promoter, detail } = useDetailPage('promoter', usePromoter, promoterPageMeta)

// "Instagram" is a brand name, so it stays the same in both locales.
const links = computed(() =>
  [
    { label: t('common.actions.website'), url: promoter.value?.websiteUrl },
    { label: 'Instagram', url: promoter.value?.instagramUrl },
  ].filter((link): link is DetailLink => Boolean(link.url)),
)
</script>

<template>
  <BaseDetailView v-bind="detail">
    <template #meta>
      <DetailLinks :links="links" />
    </template>
  </BaseDetailView>
</template>
