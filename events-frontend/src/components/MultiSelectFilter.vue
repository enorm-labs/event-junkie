<script lang="ts" setup>
/**
 * A filter that takes several values: a field-styled trigger naming the selection, opening a list
 * of checkboxes and a clear action. It holds no URL logic. It emits the whole new selection in
 * option order, so the URL, and the BFF's cache key, do not depend on the order of the ticks.
 * With `counts`, each option shows how many results ticking it leaves, and one that leaves none is
 * disabled rather than hidden, so the list does not jump (#2671). Every tick applies at once, so
 * "Done" only closes the list: without it, visitors looked for a way to confirm.
 * With `searchable`, a field above the options narrows them by name, ignoring case and accents, for
 * a list too long to scroll on a phone (#3979). A ticked option stays listed whatever the query,
 * and the options ticked when the list opens lead it, frozen until it closes so a tick moves nothing.
 */
import { computed, ref, useId } from 'vue'
import { ChevronDown } from '@lucide/vue'
import { PopoverClose } from 'reka-ui'
import { useI18n } from 'vue-i18n'
import BaseInput from '@/components/BaseInput.vue'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { cn, FIELD_CLASS } from '@/lib/utils'

export interface FilterOption {
  value: string
  label: string
}

const props = defineProps<{
  options: readonly FilterOption[]
  selected: readonly string[]
  /** The accessible name of the trigger and the list, e.g. "Filter by event type". */
  label: string
  /** The trigger text when nothing is selected. */
  allLabel: string
  /** The trigger text for two or more selected values. */
  countLabel: (count: number) => string
  clearLabel: string
  /** A line above the options for what they cannot say: how they combine, or what no option matches. */
  hint?: string
  /** Results per option if it is ticked; an option absent from it leaves none. Omitted: no counts. */
  counts?: Readonly<Record<string, number>>
  /** Adds a search field above the options; it clears when the list closes. */
  searchable?: boolean
  /** The accessible name and placeholder of the search field. */
  searchLabel?: string
  /** The line shown when the query matches no option. */
  noMatchLabel?: string
  class?: string
}>()

const { t } = useI18n()
const hintId = useId()

const emit = defineEmits<{ change: [values: string[]] }>()

/** A value without an option, such as a venue before its list loads, shows as itself. */
const summary = computed(() => {
  const [first, ...rest] = props.selected
  if (first === undefined) return props.allLabel
  if (rest.length) return props.countLabel(props.selected.length)
  return props.options.find((option) => option.value === first)?.label ?? first
})

const query = ref('')

/** Lower case without accents, so `saal` finds Säälchen. */
function folded(text: string): string {
  return text.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()
}

/** The values ticked when the list last opened. */
const pinned = ref<ReadonlySet<string>>(new Set())

function onOpen(open: boolean) {
  if (open) pinned.value = new Set(props.selected)
  else query.value = ''
}

const ordered = computed(() => {
  if (!props.searchable) return props.options
  const isPinned = (option: FilterOption) => pinned.value.has(option.value)
  return [...props.options.filter(isPinned), ...props.options.filter((option) => !isPinned(option))]
})

const shown = computed(() => {
  const needle = folded(query.value.trim())
  if (!needle) return ordered.value
  return ordered.value.filter(
    (option) => props.selected.includes(option.value) || folded(option.label).includes(needle),
  )
})

function count(value: string): number | undefined {
  return props.counts ? (props.counts[value] ?? 0) : undefined
}

/** A selected option stays enabled, so it can always be unticked. */
function unavailable(value: string): boolean {
  return count(value) === 0 && !props.selected.includes(value)
}

function toggle(value: string, checked: boolean) {
  const next = new Set(props.selected)
  if (checked) next.add(value)
  else next.delete(value)
  emit(
    'change',
    props.options.filter((option) => next.has(option.value)).map((option) => option.value),
  )
}
</script>

<template>
  <Popover @update:open="onOpen">
    <!-- eslint-disable shadcn/require-static-classes -- FIELD_CLASS lives in lib/utils, shared with BaseInput and BaseSelect -->
    <PopoverTrigger
      :aria-label="`${label}: ${summary}`"
      :class="cn(FIELD_CLASS, 'flex items-center justify-between gap-2 text-left', props.class)"
    >
      <!-- eslint-enable shadcn/require-static-classes -->
      <span class="truncate">{{ summary }}</span>
      <ChevronDown aria-hidden="true" class="size-4 shrink-0 text-muted-foreground" />
    </PopoverTrigger>
    <PopoverContent :collision-padding="8" align="start" class="w-56">
      <p v-if="hint" :id="hintId" class="mb-2 text-meta text-muted-foreground">{{ hint }}</p>
      <BaseInput
        v-if="searchable"
        v-model="query"
        :aria-label="searchLabel"
        :placeholder="searchLabel"
        class="mb-2 w-full"
        type="search"
      />
      <fieldset
        :aria-describedby="hint ? hintId : undefined"
        :class="{ 'max-h-72 overflow-y-auto': searchable }"
        class="flex flex-col gap-1"
      >
        <legend class="sr-only">{{ label }}</legend>
        <label
          v-for="option in shown"
          :key="option.value"
          class="flex min-h-8 items-center gap-2 has-disabled:text-muted-foreground"
        >
          <input
            :checked="selected.includes(option.value)"
            :disabled="unavailable(option.value)"
            class="size-4 shrink-0 rounded border-border accent-primary outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
            type="checkbox"
            @change="toggle(option.value, ($event.target as HTMLInputElement).checked)"
          />
          {{ option.label }}
          <span
            v-if="count(option.value) !== undefined"
            class="ml-auto text-meta text-muted-foreground tabular-nums"
            >{{ count(option.value) }}</span
          >
        </label>
        <p v-if="!shown.length && query" class="text-meta text-muted-foreground">
          {{ noMatchLabel }}
        </p>
      </fieldset>
      <div class="mt-2 flex justify-between gap-2">
        <Button
          :disabled="!selected.length"
          size="sm"
          type="button"
          variant="ghost"
          @click="emit('change', [])"
        >
          {{ clearLabel }}
        </Button>
        <PopoverClose as-child>
          <Button size="sm" type="button">{{ t('common.actions.done') }}</Button>
        </PopoverClose>
      </div>
    </PopoverContent>
  </Popover>
</template>
