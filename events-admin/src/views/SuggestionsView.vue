<script setup lang="ts">
import { Check, ChevronLeft, ChevronRight, ExternalLink, X } from '@lucide/vue'
import { computed, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  applySuggestion,
  cannotApply,
  dismissSuggestion,
  fetchSuggestions,
  SUGGESTION_CHECKS,
  type Suggestion,
  type SuggestionPage,
} from '@/api/suggestions'
import EventEditor from '@/components/EventEditor.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { publicOrigin } from '@/lib/environments'

const route = useRoute()
const router = useRouter()

// The query string holds the filter and the page, so a view can be linked and survives a reload.
const checkName = computed(() => String(route.query.check ?? ''))
const page = computed(() => Math.max(0, Number(route.query.page) || 0))

const result = shallowRef<SuggestionPage | null>(null)
const suggestions = ref<Suggestion[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
/** The suggestion whose action runs. One at a time: Accept is a read and two writes. */
const busy = ref<number | null>(null)
/** The error of the last action, by suggestion id, shown in that suggestion's card. */
const failures = ref<Record<number, string>>({})
const notice = ref<string | null>(null)
const editing = ref<number | null>(null)

const origin = publicOrigin(import.meta.env.MODE)

const selectClass =
  'h-8 rounded-lg border border-input bg-transparent px-2.5 text-sm focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none'

function navigate(query: { check?: string; page?: number }) {
  const next = { check: checkName.value, page: page.value, ...query }
  return router.replace({
    query: {
      ...(next.check ? { check: next.check } : {}),
      ...(next.page ? { page: String(next.page) } : {}),
    },
  })
}

async function load() {
  loading.value = true
  error.value = null
  failures.value = {}
  try {
    result.value = await fetchSuggestions({ page: page.value, checkName: checkName.value })
    suggestions.value = [...result.value.content]
  } catch (e) {
    result.value = null
    suggestions.value = []
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

watch([checkName, page], load, { immediate: true })

/** What is left open: the total the importer counted, less what this page decided since. */
const decided = ref(0)
watch(result, () => (decided.value = 0))
const open = computed(() => Math.max(0, (result.value?.totalElements ?? 0) - decided.value))
const hasNext = computed(() => page.value + 1 < (result.value?.totalPages ?? 0))

async function decide(suggestion: Suggestion, action: () => Promise<unknown>, done: string) {
  busy.value = suggestion.id
  notice.value = null
  delete failures.value[suggestion.id]
  try {
    await action()
  } catch (e) {
    failures.value[suggestion.id] = e instanceof Error ? e.message : String(e)
    return
  } finally {
    busy.value = null
  }
  suggestions.value = suggestions.value.filter((s) => s.id !== suggestion.id)
  decided.value++
  notice.value = done
}

function accept(suggestion: Suggestion) {
  return decide(
    suggestion,
    () => applySuggestion(suggestion),
    `Accepted: ${suggestion.field} of "${suggestion.eventTitle}" is saved and pinned.`,
  )
}

function reject(suggestion: Suggestion) {
  return decide(
    suggestion,
    () => dismissSuggestion(suggestion.id),
    `Rejected: ${suggestion.field} of "${suggestion.eventTitle}" stays as it is.`,
  )
}

function percent(confidence: number) {
  return `${Math.round(confidence * 100)} %`
}
</script>

<template>
  <div class="flex flex-wrap items-center gap-2">
    <p class="max-w-prose text-sm text-muted-foreground">
      Fixes a check proposes, newest first. Accept saves the proposed value and pins the field, so
      the next import keeps it. Reject leaves the event as it is.
    </p>
    <div class="ml-auto flex flex-wrap items-center gap-2">
      <label class="flex items-center gap-2 text-sm" for="suggestions-check">
        Check
        <select
          :class="selectClass"
          :value="checkName"
          id="suggestions-check"
          name="check"
          @change="navigate({ check: ($event.target as HTMLSelectElement).value, page: 0 })"
        >
          <option value="">All checks</option>
          <option v-for="name in SUGGESTION_CHECKS" :key="name" :value="name">{{ name }}</option>
        </select>
      </label>
      <p class="text-sm text-muted-foreground tabular-nums" data-testid="open-count">
        {{ open }} open
      </p>
      <Button
        :disabled="page === 0 || loading"
        aria-label="Previous page"
        size="icon-sm"
        variant="outline"
        @click="navigate({ page: page - 1 })"
      >
        <ChevronLeft />
      </Button>
      <Button
        :disabled="!hasNext || loading"
        aria-label="Next page"
        size="icon-sm"
        variant="outline"
        @click="navigate({ page: page + 1 })"
      >
        <ChevronRight />
      </Button>
    </div>
  </div>

  <p v-if="notice" class="text-sm" role="status">{{ notice }}</p>
  <p v-if="error" class="text-destructive" role="alert">{{ error }}</p>
  <p v-else-if="loading" class="text-muted-foreground">Loading the suggestions…</p>
  <p
    v-else-if="!suggestions.length"
    class="rounded-lg border p-6 text-center text-sm"
    data-testid="no-suggestions"
  >
    No suggestion is open.
  </p>

  <ul v-if="!loading && !error" class="flex flex-col gap-3">
    <li
      v-for="suggestion in suggestions"
      :key="suggestion.id"
      class="flex flex-col gap-3 rounded-lg border bg-card p-4 text-card-foreground"
      data-testid="suggestion"
    >
      <div class="flex flex-wrap items-start gap-x-3 gap-y-1">
        <div class="min-w-0 flex-1">
          <h2 class="text-base font-medium">
            <button
              :aria-label="`Edit ${suggestion.eventTitle}`"
              class="text-left underline-offset-4 hover:underline focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
              type="button"
              @click="editing = suggestion.eventId"
            >
              {{ suggestion.eventTitle }}
            </button>
          </h2>
          <p class="text-sm text-muted-foreground">
            <span class="tabular-nums">{{ suggestion.eventDate }}</span> ·
            {{ suggestion.venueName }} ·
            <a
              :href="`${origin}/en/events/${encodeURIComponent(suggestion.eventSlug)}`"
              class="inline-flex items-center gap-1 underline-offset-4 hover:underline"
              rel="noopener noreferrer"
              target="_blank"
            >
              Public page
              <ExternalLink aria-hidden="true" class="size-3" />
            </a>
          </p>
        </div>
        <Badge class="font-mono" variant="secondary">{{ suggestion.field }}</Badge>
      </div>

      <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-sm">
        <dt class="text-muted-foreground">Stored</dt>
        <dd class="break-words" data-testid="stored">
          <template v-if="suggestion.storedValue">{{ suggestion.storedValue }}</template>
          <span v-else class="text-muted-foreground italic">empty</span>
        </dd>
        <dt class="text-muted-foreground">Proposed</dt>
        <dd class="font-medium break-words" data-testid="proposed">
          <template v-if="suggestion.proposedValue">{{ suggestion.proposedValue }}</template>
          <span v-else class="font-normal text-muted-foreground italic">empty</span>
        </dd>
        <dt class="text-muted-foreground">Evidence</dt>
        <dd>
          <blockquote class="border-l-2 pl-2 break-words whitespace-pre-line">
            {{ suggestion.evidence }}
          </blockquote>
        </dd>
        <dt class="text-muted-foreground">Confidence</dt>
        <dd class="tabular-nums">{{ percent(suggestion.confidence) }}</dd>
        <dt class="text-muted-foreground">Check</dt>
        <dd class="break-words">
          <span class="font-mono text-xs">{{ suggestion.checkName }}</span>
          <span class="text-muted-foreground"> · {{ suggestion.model ?? 'no model' }} </span>
        </dd>
      </dl>

      <p
        v-if="failures[suggestion.id]"
        class="text-sm text-destructive"
        data-testid="failure"
        role="alert"
      >
        {{ failures[suggestion.id] }}
      </p>

      <div class="flex flex-wrap items-center gap-2">
        <Button
          :aria-describedby="cannotApply(suggestion.field) ? `cannot-${suggestion.id}` : undefined"
          :aria-label="`Accept ${suggestion.field} for ${suggestion.eventTitle}`"
          :disabled="busy !== null || cannotApply(suggestion.field) !== null"
          size="sm"
          @click="accept(suggestion)"
        >
          <Check aria-hidden="true" />
          Accept
        </Button>
        <Button
          :aria-label="`Reject ${suggestion.field} for ${suggestion.eventTitle}`"
          :disabled="busy !== null"
          size="sm"
          variant="outline"
          @click="reject(suggestion)"
        >
          <X aria-hidden="true" />
          Reject
        </Button>
        <p
          v-if="cannotApply(suggestion.field)"
          :id="`cannot-${suggestion.id}`"
          class="text-xs text-muted-foreground"
        >
          {{ cannotApply(suggestion.field) }}
        </p>
      </div>
    </li>
  </ul>

  <EventEditor :event-id="editing" @close="editing = null" @saved="load" />
</template>
