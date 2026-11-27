<script lang="ts" setup>
import { computed } from 'vue'
import BaseDetailView from '@/components/BaseDetailView.vue'
import DetailLinks, { type DetailLink } from '@/components/DetailLinks.vue'
import { serviceIcons } from '@/lib/serviceIcons'
import { useDetailPage } from '@/composables/useDetailPage'
import { useLocalePath } from '@/composables/useLocalePath'
import { usePromoter } from '@/composables/usePromoter'
import { promoterPageMeta } from '@/lib/pageMeta'
import { promoterKindKey } from '@/lib/promoterKinds'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const localePath = useLocalePath()

const { entity: promoter, detail } = useDetailPage('promoter', usePromoter, promoterPageMeta)

const kindLabel = computed(() => {
  const key = promoterKindKey(promoter.value?.kind)
  return key ? t(key) : null
})

// The kind lists every promoter of that kind, as a venue's type lists its venues (#5091).
const kindLink = computed(() =>
  kindLabel.value
    ? { path: localePath('/promoters'), query: { kind: promoter.value?.kind } }
    : null,
)

// "Instagram" is a brand name, so it stays the same in both locales.
const links = computed(() =>
  [
    {
      label: t('common.actions.website'),
      icon: serviceIcons.website,
      url: promoter.value?.websiteUrl,
    },
    { label: 'Instagram', icon: serviceIcons.instagram, url: promoter.value?.instagramUrl },
  ].filter((link): link is DetailLink & typeof link => Boolean(link.url)),
)
</script>

<template>
  <BaseDetailView v-bind="detail" image-fit="logo">
    <template #meta>
      <DetailLinks :lead="kindLabel" :lead-to="kindLink" :links="links" />
    </template>
  </BaseDetailView>
</template>
