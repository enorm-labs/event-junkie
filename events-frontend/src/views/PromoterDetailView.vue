<script lang="ts" setup>
import { computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import BaseDetailView from '@/components/BaseDetailView.vue'
import { usePageMeta } from '@/composables/usePageMeta'
import { notFoundPageMeta, placeholderPageMeta, promoterPageMeta } from '@/lib/pageMeta'
import { useEventSearch } from '@/composables/useEvents'
import { usePastEvents } from '@/composables/usePastEvents'
import { usePromoter } from '@/composables/usePromoter'
import { imageCredit } from '@/lib/imageCredit'
import { useI18n } from 'vue-i18n'
import { descriptionFor } from '@/lib/description'
import type { Locale } from '@/i18n/locales'

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
const { past, run: loadPastEvents } = usePastEvents(() => ({ promoter: slug.value }), events)

function reload() {
  loadPromoter()
  loadEvents()
  loadPastEvents()
}

onMounted(reload)
watch(slug, reload)

const { t, locale } = useI18n()

const description = computed(() =>
  promoter.value ? descriptionFor(promoter.value, locale.value as Locale) : null,
)

/** Entity label. A `computed` because a locale switch rewrites the URL without remounting this. */
const kind = computed(() => t('detail.promoter.kind'))

// The same values the meta injector will need server-side later (ADR-014 §Decision 3).
usePageMeta(() =>
  promoter.value
    ? promoterPageMeta(promoter.value, locale.value as Locale)
    : notFound.value
      ? notFoundPageMeta(t('detail.notFoundHeading', { kind: kind.value }))
      : placeholderPageMeta(kind.value),
)

const credit = computed(() => imageCredit(promoter.value))
</script>

<template>
  <BaseDetailView
    :empty-text="t('detail.promoter.empty')"
    :error="error"
    :events="events"
    :events-error="eventsError"
    :events-loading="eventsLoading"
    :image-url="promoter?.imageUrl"
    :credit="credit"
    :image-sources="promoter?.imageSources"
    :intrinsic-width="promoter?.intrinsicWidth"
    :intrinsic-height="promoter?.intrinsicHeight"
    :kind="kind"
    :loading="loading"
    :name="promoter?.name"
    :not-found="notFound"
    :not-found-text="t('detail.promoter.notFound')"
    :past="past"
    :ready="Boolean(promoter)"
  >
    <template #meta>
      <a
        v-if="promoter?.websiteUrl"
        :href="promoter.websiteUrl"
        class="text-body text-primary underline-offset-4 hover:underline"
        rel="noopener noreferrer"
        target="_blank"
      >
        {{ t('common.actions.website') }}
      </a>
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
