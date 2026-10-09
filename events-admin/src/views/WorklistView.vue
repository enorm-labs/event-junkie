<script setup lang="ts">
import { ChevronLeft, ChevronRight } from '@lucide/vue'
import { computed, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  fetchWorklist,
  QUALITY_ISSUES,
  type QualityIssueKey,
  WORKLIST_LIMIT,
  type Worklist,
} from '@/api/dataQuality'
import { type EventSource, fetchAllSources } from '@/api/eventSources'
import EventEditor from '@/components/EventEditor.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'

const route = useRoute()
const router = useRouter()

// The query string holds the filter, so a worklist page can be linked and survives a reload.
const ISSUE_KEYS = new Set<string>(QUALITY_ISSUES.map((i) => i.key))
const issue = computed<QualityIssueKey>(() => {
  const value = String(route.query.issue ?? '')
  return (ISSUE_KEYS.has(value) ? value : QUALITY_ISSUES[0].key) as QualityIssueKey
})
const source = computed(() => String(route.query.source ?? ''))
const offset = computed(() => Math.max(0, Number(route.query.offset) || 0))

const worklist = shallowRef<Worklist | null>(null)
const sources = shallowRef<EventSource[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const editing = ref<number | null>(null)

const selectClass =
  'h-8 rounded-lg border border-input bg-transparent px-2.5 text-sm focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none'

function navigate(query: { issue?: string; source?: string; offset?: number }) {
  const next = { issue: issue.value, source: source.value, offset: offset.value, ...query }
  return router.replace({
    query: {
      issue: next.issue,
      ...(next.source ? { source: next.source } : {}),
      ...(next.offset ? { offset: String(next.offset) } : {}),
    },
  })
}

async function load() {
  loading.value = true
  error.value = null
  try {
    worklist.value = await fetchWorklist({
      issue: issue.value,
      source: source.value,
      offset: offset.value,
    })
  } catch (e) {
    worklist.value = null
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

watch([issue, source, offset], load, { immediate: true })

onMounted(async () => {
  // The source list only fills the filter. Without it the worklist still works for every source.
  try {
    sources.value = await fetchAllSources()
  } catch {
    sources.value = []
  }
})

// The endpoint returns no total. A full page means there may be another one.
const hasNext = computed(() => (worklist.value?.count ?? 0) >= WORKLIST_LIMIT)
</script>

<template>
  <div class="flex flex-wrap items-center gap-2">
    <label class="flex items-center gap-2 text-sm" for="worklist-issue">
      Issue
      <select
        :class="selectClass"
        :value="issue"
        id="worklist-issue"
        name="issue"
        @change="navigate({ issue: ($event.target as HTMLSelectElement).value, offset: 0 })"
      >
        <option v-for="option in QUALITY_ISSUES" :key="option.key" :value="option.key">
          {{ option.label }}
        </option>
      </select>
    </label>
    <label class="flex items-center gap-2 text-sm" for="worklist-source">
      Source
      <select
        :class="selectClass"
        :value="source"
        id="worklist-source"
        name="source"
        @change="navigate({ source: ($event.target as HTMLSelectElement).value, offset: 0 })"
      >
        <option value="">All sources</option>
        <option value="manual">manual (hand-created)</option>
        <!-- A source in the query that the list lacks still shows as selected. -->
        <option
          v-if="source && source !== 'manual' && !sources.some((s) => s.slug === source)"
          :value="source"
        >
          {{ source }}
        </option>
        <option v-for="s in sources" :key="s.slug" :value="s.slug">{{ s.name }}</option>
      </select>
    </label>
    <div class="ml-auto flex items-center gap-2">
      <p class="text-sm text-muted-foreground">
        <template v-if="worklist?.count"> {{ offset + 1 }}–{{ offset + worklist.count }} </template>
        <template v-else>0</template>
      </p>
      <Button
        :disabled="offset === 0 || loading"
        aria-label="Previous page"
        size="icon-sm"
        variant="outline"
        @click="navigate({ offset: Math.max(0, offset - WORKLIST_LIMIT) })"
      >
        <ChevronLeft />
      </Button>
      <Button
        :disabled="!hasNext || loading"
        aria-label="Next page"
        size="icon-sm"
        variant="outline"
        @click="navigate({ offset: offset + WORKLIST_LIMIT })"
      >
        <ChevronRight />
      </Button>
    </div>
  </div>

  <p v-if="error" class="text-destructive" role="alert">{{ error }}</p>
  <div v-else class="overflow-hidden rounded-lg border">
    <Table>
      <TableHeader class="bg-muted">
        <TableRow>
          <TableHead>Date</TableHead>
          <TableHead>Start</TableHead>
          <TableHead>Title</TableHead>
          <TableHead>Source</TableHead>
          <TableHead>Import flags</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        <TableRow v-if="loading">
          <TableCell class="h-24 text-center text-muted-foreground" colspan="5">
            Loading the worklist…
          </TableCell>
        </TableRow>
        <template v-else>
          <TableRow v-for="entry in worklist?.entries ?? []" :key="entry.id">
            <TableCell class="tabular-nums">{{ entry.eventDate }}</TableCell>
            <TableCell class="tabular-nums">{{ entry.startTime?.slice(0, 5) ?? '—' }}</TableCell>
            <TableCell class="whitespace-normal">
              <button
                class="text-left underline-offset-4 hover:underline focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
                type="button"
                @click="editing = entry.id"
              >
                {{ entry.title }}
              </button>
            </TableCell>
            <TableCell class="font-mono">{{ entry.sourceSlug }}</TableCell>
            <TableCell class="whitespace-normal">
              <span class="flex flex-wrap gap-1">
                <Badge
                  v-for="flag in entry.flags"
                  :key="`${flag.kind}:${flag.value}`"
                  :title="flag.kind"
                  variant="outline"
                >
                  {{ flag.value }}
                </Badge>
              </span>
            </TableCell>
          </TableRow>
          <TableRow v-if="!worklist?.entries.length">
            <TableCell class="h-24 text-center" colspan="5">No event fails this check.</TableCell>
          </TableRow>
        </template>
      </TableBody>
    </Table>
  </div>

  <EventEditor :event-id="editing" @close="editing = null" @saved="load" />
</template>
