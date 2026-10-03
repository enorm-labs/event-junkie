<script lang="ts" setup>
/**
 * The header search (#2514): a button that opens a palette over the page, full screen on a phone.
 * A Reka listbox rather than a combobox popover, so the field and its results are one dialog and
 * the arrow keys move through the results while the caret stays in the field.
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import {
  DialogClose,
  DialogContent,
  DialogOverlay,
  DialogRoot,
  DialogTitle,
  DialogTrigger,
  ListboxContent,
  ListboxFilter,
  ListboxGroup,
  ListboxGroupLabel,
  ListboxItem,
  ListboxRoot,
} from 'reka-ui'
import { Search, X } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { MIN_SEARCH_LENGTH, useGlobalSearch } from '@/composables/useGlobalSearch'
import { useFormat } from '@/composables/useFormat'
import { useLocalePath } from '@/composables/useLocalePath'
import { districtLabel } from '@/lib/districts'

const { t } = useI18n()
const { formatShortDate } = useFormat()
const localePath = useLocalePath()
const router = useRouter()
const route = useRoute()

const open = ref(false)
const term = ref('')
const { results, loading, error } = useGlobalSearch(term)

const q = computed(() => term.value.trim())
const searchPagePath = computed(() => localePath(`/search?q=${encodeURIComponent(q.value)}`))

interface Option {
  value: string
  label: string
  meta?: string
}

// Venues first: a venue's name also matches every event there, so its events would bury it.
const groups = computed(() => {
  const r = results.value
  if (!r) return []
  const upcoming = (count?: number) =>
    count === undefined ? undefined : t('common.upcomingCount', { count })
  const all: { key: string; options: Option[] }[] = [
    {
      key: 'venues',
      options: (r.venues?.items ?? []).map((venue) => ({
        value: localePath(`/venues/${venue.slug}`),
        label: venue.name ?? '',
        meta: [districtLabel(venue.district), upcoming(venue.upcomingEventCount)]
          .filter(Boolean)
          .join(' · '),
      })),
    },
    {
      key: 'events',
      options: (r.events?.items ?? []).map((event) => ({
        value: localePath(`/events/${event.slug}`),
        label: event.title ?? '',
        meta: [formatShortDate(event.eventDate), event.venue?.name].filter(Boolean).join(' · '),
      })),
    },
    {
      key: 'artists',
      options: (r.artists?.items ?? []).map((artist) => ({
        value: localePath(`/artists/${artist.slug}`),
        label: artist.name ?? '',
      })),
    },
    {
      key: 'promoters',
      options: (r.promoters?.items ?? []).map((promoter) => ({
        value: localePath(`/promoters/${promoter.slug}`),
        label: promoter.name ?? '',
        meta: upcoming(promoter.upcomingEventCount),
      })),
    },
  ]
  return all.filter((group) => group.options.length)
})

const found = computed(() => {
  const r = results.value
  return [r?.events, r?.venues, r?.artists, r?.promoters].reduce(
    (sum, group) => sum + (group?.total ?? 0),
    0,
  )
})

const status = computed(() => {
  if (q.value.length < MIN_SEARCH_LENGTH) return t('search.hint')
  if (error.value) return error.value
  if (loading.value && !results.value) return t('search.loading')
  if (results.value && !found.value) return t('search.empty', { q: q.value })
  return ''
})

// Tracked so Enter in the field can tell "open this result" from "open the results page".
const highlighted = ref<string>()

function go(path: unknown) {
  if (typeof path !== 'string') return
  open.value = false
  router.push(path)
}

function submit() {
  if (!highlighted.value && q.value.length >= MIN_SEARCH_LENGTH) go(searchPagePath.value)
}

// A new term replaces the options, and the listbox emits nothing when its highlighted one goes.
watch(term, () => {
  highlighted.value = undefined
})
// A fresh palette each time: the last term would otherwise greet the next search.
watch(open, (isOpen) => {
  if (!isOpen) term.value = ''
})
watch(
  () => route.fullPath,
  () => {
    open.value = false
  },
)

// `/` as on GitHub and YouTube, Ctrl/Cmd+K as in most palettes; never while the visitor types.
function onKeydown(event: KeyboardEvent) {
  const target = event.target as HTMLElement | null
  const typing =
    target?.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target?.tagName ?? '')
  const shortcut =
    (event.key === 'k' && (event.metaKey || event.ctrlKey)) ||
    (event.key === '/' && !typing && !event.metaKey && !event.ctrlKey && !event.altKey)
  if (!shortcut) return
  event.preventDefault()
  open.value = true
}
onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <DialogRoot v-model:open="open">
    <DialogTrigger as-child>
      <Button
        :aria-label="t('search.label')"
        :title="t('search.label')"
        size="icon"
        variant="outline"
      >
        <Search />
      </Button>
    </DialogTrigger>
    <DialogOverlay class="fixed inset-0 z-40 bg-background/80" />
    <DialogContent
      :aria-describedby="undefined"
      class="fixed inset-0 z-50 flex flex-col bg-background outline-hidden md:inset-x-4 md:top-16 md:bottom-auto md:mx-auto md:max-h-2/3 md:max-w-xl md:rounded-lg md:border md:border-border"
    >
      <DialogTitle class="sr-only">{{ t('search.label') }}</DialogTitle>
      <ListboxRoot
        class="flex min-h-0 flex-1 flex-col"
        highlight-on-hover
        @highlight="highlighted = $event?.value as string | undefined"
        @update:model-value="go"
      >
        <div class="flex items-center gap-2 border-b border-border p-2 pl-4">
          <Search aria-hidden="true" class="size-4 shrink-0 text-muted-foreground" />
          <ListboxFilter
            v-model="term"
            :aria-label="t('search.label')"
            :placeholder="t('search.placeholder')"
            auto-focus
            class="h-10 min-w-0 flex-1 bg-transparent text-lede outline-hidden placeholder:text-muted-foreground"
            @keydown.enter="submit"
          />
          <DialogClose as-child>
            <Button
              :aria-label="t('search.close')"
              :title="t('search.close')"
              size="icon"
              variant="ghost"
            >
              <X />
            </Button>
          </DialogClose>
        </div>
        <!-- Polite, so a screen reader hears the count once the visitor pauses, not per letter. -->
        <p aria-live="polite" class="sr-only" role="status">
          {{ results && !loading ? t('search.status', { count: found }) : '' }}
        </p>
        <p v-if="status" class="px-4 py-3 text-body text-muted-foreground">{{ status }}</p>
        <ListboxContent
          v-if="groups.length"
          :aria-label="t('search.resultsFor', { q })"
          class="min-h-0 flex-1 overflow-y-auto p-2"
        >
          <ListboxGroup v-for="group in groups" :key="group.key" class="pb-2">
            <ListboxGroupLabel class="px-2 py-1 text-meta text-muted-foreground">
              {{ t(`search.groups.${group.key}`) }}
            </ListboxGroupLabel>
            <ListboxItem
              v-for="option in group.options"
              :key="option.value"
              :value="option.value"
              class="block cursor-pointer rounded-md px-2 py-1.5 data-highlighted:bg-accent data-highlighted:text-accent-foreground"
            >
              <span class="block truncate text-body font-medium">{{ option.label }}</span>
              <span v-if="option.meta" class="block truncate text-meta text-muted-foreground">
                {{ option.meta }}
              </span>
            </ListboxItem>
          </ListboxGroup>
          <ListboxGroup class="border-t border-border pt-2">
            <ListboxItem
              :value="searchPagePath"
              class="block cursor-pointer rounded-md px-2 py-1.5 text-body text-primary data-highlighted:bg-accent"
            >
              {{ t('search.allResults', { q }) }}
            </ListboxItem>
          </ListboxGroup>
        </ListboxContent>
      </ListboxRoot>
    </DialogContent>
  </DialogRoot>
</template>
