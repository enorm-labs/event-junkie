<script lang="ts" setup>
/**
 * The event type filter: a field-styled trigger that opens a list of checkboxes, so a visitor can
 * pick several types and see events of any of them (#1995). Each tick writes the URL at once, as
 * the selects beside it do. The URL keeps the types in `EVENT_TYPES` order.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ChevronDown } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { useEventFilters } from '@/composables/useEventFilters'
import { useFormat } from '@/composables/useFormat'
import { cn, FIELD_CLASS } from '@/lib/utils'

const props = defineProps<{ class?: string }>()

const EVENT_TYPES = [
  'CONCERT',
  'FESTIVAL',
  'PARTY',
  'QUIZ',
  'SHOW',
  'SCREENING',
  'EXHIBITION',
  'READING',
  'OTHER',
]

const { t } = useI18n()
const { formatEventType } = useFormat()
const { queryList, applyFilters } = useEventFilters()

const selected = computed(() => queryList('eventType'))

const summary = computed(() => {
  const [first, ...rest] = selected.value
  if (!first) return t('events.filters.allTypes')
  return rest.length
    ? t('events.filters.typesSelected', { n: selected.value.length })
    : formatEventType(first)
})

function toggle(type: string, checked: boolean) {
  const next = new Set(selected.value)
  if (checked) next.add(type)
  else next.delete(type)
  applyFilters({ eventType: EVENT_TYPES.filter((it) => next.has(it)) })
}
</script>

<template>
  <Popover>
    <PopoverTrigger
      :aria-label="`${t('events.filters.byType')}: ${summary}`"
      :class="cn(FIELD_CLASS, 'flex items-center justify-between gap-2 text-left', props.class)"
      data-testid="event-type-filter"
    >
      <span class="truncate">{{ summary }}</span>
      <ChevronDown aria-hidden="true" class="size-4 shrink-0 text-muted-foreground" />
    </PopoverTrigger>
    <PopoverContent align="start" class="w-56">
      <fieldset class="flex flex-col gap-1">
        <legend class="sr-only">{{ t('events.filters.byType') }}</legend>
        <label v-for="type in EVENT_TYPES" :key="type" class="flex h-8 items-center gap-2">
          <input
            :checked="selected.includes(type)"
            class="size-4 rounded border-border accent-primary outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
            type="checkbox"
            @change="toggle(type, ($event.target as HTMLInputElement).checked)"
          />
          {{ formatEventType(type) }}
        </label>
      </fieldset>
      <Button
        :disabled="!selected.length"
        size="sm"
        type="button"
        variant="ghost"
        @click="applyFilters({ eventType: [] })"
      >
        {{ t('events.filters.clearTypes') }}
      </Button>
    </PopoverContent>
  </Popover>
</template>
