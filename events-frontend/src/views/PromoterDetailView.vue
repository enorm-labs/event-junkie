<script lang="ts" setup>
import { computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import BaseDetailView from '@/components/BaseDetailView.vue'
import { usePageMeta } from '@/composables/usePageMeta'
import { placeholderPageMeta, promoterPageMeta } from '@/lib/pageMeta'
import { useEventSearch } from '@/composables/useEvents'
import { yesterdayIso } from '@/lib/format'
import { usePromoter } from '@/composables/usePromoter'
import { useI18n } from 'vue-i18n'

const route = useRoute()
const slug = computed(() => String(route.params.slug))

const {
  data: promoter,
  error,
  notFound,
  loading,
  run: loadPromoter,
} = usePromoter(() => slug.value)
const {
  data: events,
  error: eventsError,
  loading: eventsLoading,
  run: loadEvents,
} = useEventSearch(() => ({ promoter: slug.value, size: 50 }), 'errors.subject.promoterEvents')
// `to` with no `from` is every past event, newest first; the page size is what bounds it.
const { data: pastEvents, run: loadPastEvents } = useEventSearch(() => ({
  promoter: slug.value,
  to: yesterdayIso(),
  size: 20,
  sort: ['eventDate,desc'],
}))

function reload() {
  loadPromoter()
  loadEvents()
  loadPastEvents()
}

onMounted(reload)
watch(slug, reload)

const { t } = useI18n()

/** Entity label. A `computed` because a locale switch rewrites the URL without remounting this. */
const kind = computed(() => t('detail.promoter.kind'))

// The same values the meta injector will need server-side later (ADR-014 §Decision 3).
usePageMeta(() =>
  promoter.value
    ? promoterPageMeta(promoter.value)
    : placeholderPageMeta(
        notFound.value ? t('detail.notFoundHeading', { kind: kind.value }) : kind.value,
      ),
)
</script>

<template>
  <BaseDetailView
    :empty-text="t('detail.promoter.empty')"
    :error="error"
    :events="events"
    :events-error="eventsError"
    :events-loading="eventsLoading"
    :image-url="promoter?.imageUrl"
    :image-sources="promoter?.imageSources"
    :intrinsic-width="promoter?.intrinsicWidth"
    :intrinsic-height="promoter?.intrinsicHeight"
    :kind="kind"
    :loading="loading"
    :name="promoter?.name"
    :not-found="notFound"
    :not-found-text="t('detail.promoter.notFound')"
    :past-events="pastEvents"
    :ready="Boolean(promoter)"
  >
    <template #meta>
      <a
        v-if="promoter?.websiteUrl"
        :href="promoter.websiteUrl"
        class="text-sm text-primary underline-offset-4 hover:underline"
        rel="noopener noreferrer"
        target="_blank"
      >
        {{ t('common.actions.website') }}
      </a>
    </template>
  </BaseDetailView>
</template>
