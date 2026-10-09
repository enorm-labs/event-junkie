import { computed, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import type { ImageSource } from '@/api/types'
import type { Locale } from '@/i18n/locales'
import { type Described, descriptionFor } from '@/lib/description'
import { type AttributedImage, imageCredit } from '@/lib/imageCredit'
import { notFoundPageMeta, type PageMeta, placeholderPageMeta } from '@/lib/pageMeta'
import type { useAsync } from './useAsync'
import { useEventSearch } from './useEvents'
import { usePageMeta } from './usePageMeta'
import { usePastEvents } from './usePastEvents'

/** The entities with a detail page on `BaseDetailView`; each names its route, filter and messages. */
export type DetailKind = 'venue' | 'artist' | 'promoter'

/** What `BaseDetailView` and the page meta read from the entity. */
interface DetailEntity extends Described, AttributedImage {
  name?: string | null
  imageSources?: ImageSource[] | null
  intrinsicWidth?: number | null
  intrinsicHeight?: number | null
}

/**
 * The data side of a venue, artist or promoter page: the entity by the route's slug, its upcoming
 * and past events, the page meta, and the props `BaseDetailView` takes, as `detail` for a
 * `v-bind`. The view adds only what is its own: the `meta` slot, the description, the extras.
 */
export function useDetailPage<T extends DetailEntity>(
  kind: DetailKind,
  load: (slug: () => string) => ReturnType<typeof useAsync<T>>,
  pageMeta: (entity: T, locale: Locale) => PageMeta,
) {
  const route = useRoute()
  const slug = computed(() => String(route.params.slug))
  const { t, locale } = useI18n()

  const { data: entity, error, notFound, loading, run: loadEntity } = load(() => slug.value)
  const {
    data: events,
    error: eventsError,
    loading: eventsLoading,
    run: loadEvents,
  } = useEventSearch(() => ({ [kind]: slug.value, size: 50 }), `errors.subject.${kind}Events`)
  const { past, run: loadPastEvents } = usePastEvents(() => ({ [kind]: slug.value }), events)

  function reload() {
    loadEntity()
    loadEvents()
    loadPastEvents()
  }

  onMounted(reload)
  watch(slug, reload)

  /** Entity label. A `computed` because a locale switch rewrites the URL without remounting this. */
  const kindLabel = computed(() => t(`detail.${kind}.kind`))

  // The same values the meta injector will need server-side later (ADR-014 §Decision 3).
  usePageMeta(() =>
    entity.value
      ? pageMeta(entity.value, locale.value as Locale)
      : notFound.value
        ? notFoundPageMeta(t('detail.notFoundHeading', { kind: kindLabel.value }))
        : placeholderPageMeta(kindLabel.value),
  )

  // The visitor's language where the entity has a text in it, and the other one otherwise. The
  // same rule the event page uses, from the same function (#1210).
  const description = computed(() =>
    entity.value ? descriptionFor(entity.value, locale.value as Locale) : null,
  )

  const detail = computed(() => ({
    kind: kindLabel.value,
    loading: loading.value,
    error: error.value,
    notFound: notFound.value,
    ready: Boolean(entity.value),
    notFoundText: t(`detail.${kind}.notFound`),
    name: entity.value?.name,
    imageUrl: entity.value?.imageUrl,
    imageSources: entity.value?.imageSources,
    intrinsicWidth: entity.value?.intrinsicWidth,
    intrinsicHeight: entity.value?.intrinsicHeight,
    credit: imageCredit(entity.value),
    events: events.value,
    eventsLoading: eventsLoading.value,
    eventsError: eventsError.value,
    emptyText: t(`detail.${kind}.empty`),
    past,
    reportText: t(`detail.${kind}.report`),
    reportPath: `/${kind}s/${slug.value}`,
  }))

  return { entity, description, detail }
}
