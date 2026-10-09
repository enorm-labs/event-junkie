<script setup lang="ts">
import { EllipsisVertical, LoaderCircle } from '@lucide/vue'
import { computed, onBeforeUnmount, ref } from 'vue'

import {
  type EventSource,
  fetchSource,
  importEnded,
  retrySource,
  triggerImport,
} from '@/api/eventSources'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'

const props = defineProps<{ source: EventSource }>()
const emit = defineEmits<{ updated: [source: EventSource] }>()

type Action = 'import' | 'force' | 'retry'

/** How often the row reads its source while an import runs, and for how long at most. */
const POLL_INTERVAL_MS = 3_000
const POLL_LIMIT_MS = 10 * 60_000

const busy = ref<Action | null>(null)
const polling = ref(false)
const timedOut = ref(false)
const error = ref<string | null>(null)
let timer: ReturnType<typeof setTimeout> | undefined
let unmounted = false

// Retry resets a source to IDLE. That only means something for a failed source or a stuck run.
const canRetry = computed(
  () => props.source.status === 'FAILED' || props.source.status === 'RUNNING',
)
const disabled = computed(() => busy.value !== null || polling.value)

async function run(action: Action) {
  busy.value = action
  error.value = null
  timedOut.value = false
  const slug = props.source.slug
  const lastImportAtBefore = props.source.lastImportAt
  try {
    if (action === 'retry') emit('updated', await retrySource(slug))
    else await triggerImport(slug, action === 'force')
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
    return
  } finally {
    busy.value = null
  }
  // The importer answered 202, or Retry left the source IDLE for the scheduler's next tick. Read
  // the row until a newer run has ended.
  polling.value = true
  await poll(slug, lastImportAtBefore, Date.now() + POLL_LIMIT_MS)
}

async function poll(slug: string, lastImportAtBefore: string | null, deadline: number) {
  let current: EventSource
  try {
    current = await fetchSource(slug)
  } catch (e) {
    if (unmounted) return
    error.value = e instanceof Error ? e.message : String(e)
    polling.value = false
    return
  }
  if (unmounted) return
  emit('updated', current)
  if (importEnded(lastImportAtBefore, current)) {
    polling.value = false
  } else if (Date.now() + POLL_INTERVAL_MS > deadline) {
    polling.value = false
    timedOut.value = true
  } else {
    timer = setTimeout(() => void poll(slug, lastImportAtBefore, deadline), POLL_INTERVAL_MS)
  }
}

onBeforeUnmount(() => {
  unmounted = true
  clearTimeout(timer)
})
</script>

<template>
  <div class="flex flex-col gap-1">
    <!-- Import stays a button; the rarer actions sit in a row menu, so the column fits beside the others. -->
    <div class="flex gap-1">
      <Button
        :aria-label="`Import ${source.name}`"
        :disabled="disabled"
        size="xs"
        variant="outline"
        @click="run('import')"
      >
        Import
      </Button>
      <DropdownMenu>
        <DropdownMenuTrigger as-child>
          <Button
            :aria-label="`More actions for ${source.name}`"
            :disabled="disabled"
            size="icon-xs"
            title="More actions"
            variant="ghost"
          >
            <EllipsisVertical aria-hidden="true" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" class="w-64">
          <DropdownMenuItem
            :aria-label="`Force import ${source.name}`"
            class="flex-col items-start gap-0"
            @select="run('force')"
          >
            Force import
            <span class="text-xs text-muted-foreground">
              Fetch without the cached ETag and Last-Modified, for a parser fix at an unchanged
              page.
            </span>
          </DropdownMenuItem>
          <DropdownMenuItem
            v-if="canRetry"
            :aria-label="`Retry ${source.name}`"
            class="flex-col items-start gap-0"
            @select="run('retry')"
          >
            Retry
            <span class="text-xs text-muted-foreground">
              Reset to IDLE and clear the error. The scheduler imports it on its next tick.
            </span>
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
    <!-- The column sticks to the table's right edge: notes wrap narrow, so it covers little at 390 px. -->
    <p
      v-if="polling"
      class="flex max-w-48 items-center gap-1 text-xs whitespace-normal text-muted-foreground"
      role="status"
    >
      <LoaderCircle aria-hidden="true" class="size-3 shrink-0 animate-spin" />
      Waiting for the import to end…
    </p>
    <p
      v-if="timedOut"
      class="max-w-48 text-xs whitespace-normal text-muted-foreground"
      role="status"
    >
      Still running after 10 minutes. Reload the page later.
    </p>
    <p v-if="error" class="max-w-48 text-xs whitespace-normal text-destructive" role="alert">
      {{ error }}
    </p>
  </div>
</template>
