<script setup lang="ts">
import { useDebounceFn } from '@vueuse/core'
import { ref, watch } from 'vue'

import { type NameKind, type NamedRow, searchByName } from '@/api/names'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'

/** Finds an artist or a promoter by part of the name and hands the chosen row up. */
const props = defineProps<{
  kind: NameKind
  id: string
  label: string
  /** Rows already on the event, left out of the matches. */
  exclude: number[]
}>()
const emit = defineEmits<{ pick: [row: NamedRow] }>()

/** Fewer letters match too much of the table to be worth a request. */
const MIN_LETTERS = 2

const term = ref('')
const rows = ref<NamedRow[]>([])
const total = ref(0)
const searching = ref(false)
const error = ref<string | null>(null)
let latest = 0

const search = useDebounceFn(async (name: string) => {
  const request = ++latest
  searching.value = true
  error.value = null
  try {
    const result = await searchByName(props.kind, name)
    // A slower answer to an older term must not replace the newer one.
    if (request !== latest) return
    rows.value = result.rows
    total.value = result.total
  } catch (e) {
    if (request === latest) error.value = e instanceof Error ? e.message : String(e)
  } finally {
    if (request === latest) searching.value = false
  }
}, 250)

watch(term, (name) => {
  if (name.trim().length < MIN_LETTERS) {
    latest++
    rows.value = []
    total.value = 0
    searching.value = false
    return
  }
  void search(name)
})

function pick(row: NamedRow) {
  emit('pick', row)
  term.value = ''
}
</script>

<template>
  <div class="flex flex-col gap-1.5">
    <label class="flex flex-col gap-1.5" :for="id">
      <span class="text-sm font-medium">{{ label }}</span>
      <Input
        :id="id"
        v-model="term"
        autocomplete="off"
        :name="id"
        placeholder="Type part of the name"
        type="search"
      />
    </label>
    <p v-if="error" class="text-xs text-destructive" role="alert">{{ error }}</p>
    <p v-else-if="searching" class="text-xs text-muted-foreground">Searching…</p>
    <template v-else-if="term.trim().length >= MIN_LETTERS">
      <ul v-if="rows.length" class="flex flex-col gap-1" :aria-label="`Matches for ${term.trim()}`">
        <li v-for="row in rows" :key="row.id" class="flex items-center justify-between gap-2">
          <span class="min-w-0 truncate text-sm" :title="row.name">{{ row.name }}</span>
          <Button
            :aria-label="`Add ${row.name}`"
            :disabled="exclude.includes(row.id)"
            size="xs"
            type="button"
            variant="outline"
            @click="pick(row)"
          >
            {{ exclude.includes(row.id) ? 'Added' : 'Add' }}
          </Button>
        </li>
      </ul>
      <p v-else class="text-xs text-muted-foreground">No {{ kind }} match “{{ term.trim() }}”.</p>
      <p v-if="total > rows.length" class="text-xs text-muted-foreground">
        {{ total - rows.length }} more match. Type more of the name.
      </p>
    </template>
  </div>
</template>
