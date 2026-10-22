<script setup lang="ts">
import { computed, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  dismissWorklistRow,
  fetchDismissals,
  fetchWorklist,
  QUALITY_ISSUES,
  type QualityIssueKey,
  undoDismissal,
  WORKLIST_LIMIT,
  type Worklist,
  type WorklistDismissal,
  type WorklistEntry,
} from '@/api/dataQuality'
import { type EventSource, fetchAllSources } from '@/api/eventSources'
import EventEditor from '@/components/EventEditor.vue'
import PagerButtons from '@/components/PagerButtons.vue'
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
const busy = ref(false)
const actionError = ref<string | null>(null)
const showDismissed = ref(false)
const dismissals = shallowRef<WorklistDismissal[]>([])

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

async function loadDismissals() {
  dismissals.value = showDismissed.value ? await fetchDismissals(issue.value) : []
}

watch([issue, showDismissed], () => loadDismissals().catch(failed))

function failed(e: unknown) {
  actionError.value = e instanceof Error ? e.message : String(e)
}

// Each action reloads the page, so the row and the dismissed count stay what the importer says.
async function act(action: () => Promise<unknown>) {
  busy.value = true
  actionError.value = null
  try {
    await action()
    await Promise.all([load(), loadDismissals()])
  } catch (e) {
    failed(e)
  } finally {
    busy.value = false
  }
}

function dismiss(entry: WorklistEntry) {
  return act(() => dismissWorklistRow(issue.value, entry))
}

function undo(dismissal: WorklistDismissal) {
  return act(() => undoDismissal(dismissal.id))
}

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
        v-if="worklist?.dismissed || showDismissed"
        :aria-expanded="showDismissed"
        aria-controls="worklist-dismissals"
        data-testid="dismissed-count"
        size="sm"
        variant="ghost"
        @click="showDismissed = !showDismissed"
      >
        {{ worklist?.dismissed ?? 0 }} correct as is
      </Button>
      <PagerButtons
        :has-next="hasNext"
        :has-previous="offset > 0"
        :loading="loading"
        @next="navigate({ offset: offset + WORKLIST_LIMIT })"
        @previous="navigate({ offset: Math.max(0, offset - WORKLIST_LIMIT) })"
      />
    </div>
  </div>

  <p v-if="actionError" class="text-destructive" role="alert">{{ actionError }}</p>
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
          <TableHead><span class="sr-only">Actions</span></TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        <TableRow v-if="loading">
          <TableCell class="h-24 text-center text-muted-foreground" colspan="6">
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
            <TableCell class="text-right">
              <Button
                v-if="entry.fingerprint"
                :aria-label="`Correct as is: ${entry.title}`"
                :disabled="busy"
                size="sm"
                variant="outline"
                @click="dismiss(entry)"
              >
                Correct as is
              </Button>
            </TableCell>
          </TableRow>
          <TableRow v-if="!worklist?.entries.length">
            <TableCell class="h-24 text-center" colspan="6">No event fails this check.</TableCell>
          </TableRow>
        </template>
      </TableBody>
    </Table>
  </div>

  <section v-if="showDismissed" id="worklist-dismissals" class="flex flex-col gap-2">
    <h2 class="text-sm font-medium">Correct as is</h2>
    <p class="text-sm text-muted-foreground">
      A row comes back by itself when a value the check flagged changes.
    </p>
    <div class="overflow-hidden rounded-lg border">
      <Table>
        <TableHeader class="bg-muted">
          <TableRow>
            <TableHead>Date</TableHead>
            <TableHead>Title</TableHead>
            <TableHead>Source</TableHead>
            <TableHead>Dismissed</TableHead>
            <TableHead><span class="sr-only">Actions</span></TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          <TableRow v-for="d in dismissals" :key="d.id" data-testid="dismissal">
            <TableCell class="tabular-nums">{{ d.eventDate }}</TableCell>
            <TableCell class="whitespace-normal">
              {{ d.eventTitle }}
              <Badge v-if="!d.active" variant="outline">changed, back on the list</Badge>
            </TableCell>
            <TableCell class="font-mono">{{ d.sourceSlug }}</TableCell>
            <TableCell class="tabular-nums">{{ d.dismissedAt.slice(0, 10) }}</TableCell>
            <TableCell class="text-right">
              <Button
                :aria-label="`Undo: ${d.eventTitle}`"
                :disabled="busy"
                size="sm"
                variant="outline"
                @click="undo(d)"
              >
                Undo
              </Button>
            </TableCell>
          </TableRow>
          <TableRow v-if="!dismissals.length">
            <TableCell class="h-16 text-center" colspan="5"
              >Nothing is dismissed for this check.</TableCell
            >
          </TableRow>
        </TableBody>
      </Table>
    </div>
  </section>

  <EventEditor :event-id="editing" @close="editing = null" @saved="load" />
</template>
