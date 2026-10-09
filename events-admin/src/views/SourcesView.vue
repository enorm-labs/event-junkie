<script setup lang="ts">
import { ArrowDown, ArrowUp, ChevronDown, Columns3 } from '@lucide/vue'
import { FlexRender, useTable } from '@tanstack/vue-table'
import { computed, onMounted, ref, shallowRef } from 'vue'

import { type EventSource, fetchAllSources } from '@/api/eventSources'
import SourceActions from '@/components/SourceActions.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Input } from '@/components/ui/input'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { INITIAL_VISIBILITY, columns, features } from '@/lib/sourceTable'

const sources = shallowRef<EventSource[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const query = ref('')

// The page holds every source, so the search filters in the browser.
const shown = computed(() => {
  const needle = query.value.trim().toLowerCase()
  if (!needle) return sources.value
  return sources.value.filter(
    (s) => s.name.toLowerCase().includes(needle) || s.slug.includes(needle),
  )
})

const table = useTable({
  features,
  columns,
  data: shown,
  initialState: {
    sorting: [{ id: 'name', desc: false }],
    columnVisibility: INITIAL_VISIBILITY,
  },
})

// No year: every import is recent, and the year cost each date column a third of its width.
const dateTime = new Intl.DateTimeFormat('en-GB', {
  day: '2-digit',
  month: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  timeZone: 'Europe/Berlin',
})

function formatInstant(value: unknown): string {
  return typeof value === 'string' ? dateTime.format(new Date(value)) : '—'
}

function statusVariant(status: unknown) {
  if (status === 'FAILED') return 'destructive'
  if (status === 'SUCCESS') return 'secondary'
  return 'outline'
}

const DATE_COLUMNS = new Set(['lastImportAt', 'lastSuccessAt', 'flaggedAt'])

// The actions stick to the table's right edge, so a wide table scrolls under them. A sticky cell
// needs a solid background; on hover and with its menu open it mixes the row's tint, muted at 50 %.
// Whole class names, so Tailwind finds them in this file.
const STICKY_HEAD = 'sticky right-0 z-10 shadow-[inset_1px_0_0_var(--color-border)] bg-muted'
const STICKY_CELL =
  'sticky right-0 z-10 shadow-[inset_1px_0_0_var(--color-border)] bg-background ' +
  '[tr:hover>&]:bg-[color-mix(in_oklab,var(--color-muted)_50%,var(--color-background))] ' +
  '[tr:has([aria-expanded=true])>&]:bg-[color-mix(in_oklab,var(--color-muted)_50%,var(--color-background))]'

/** An action read its source again: swap that row and keep the rest. */
function replaceSource(updated: EventSource) {
  sources.value = sources.value.map((s) => (s.slug === updated.slug ? updated : s))
}

onMounted(async () => {
  try {
    sources.value = await fetchAllSources()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <p v-if="loading" class="text-muted-foreground">Loading every page of sources…</p>
  <p v-else-if="error" class="text-destructive" role="alert">{{ error }}</p>
  <template v-else>
    <div class="flex flex-wrap items-center gap-2">
      <Input
        v-model="query"
        aria-label="Search by name or slug"
        class="h-8 w-full sm:w-64"
        placeholder="Search by name or slug"
      />
      <p class="text-sm text-muted-foreground">
        {{ shown.length }} of {{ sources.length }} sources
      </p>
      <DropdownMenu>
        <DropdownMenuTrigger as-child>
          <Button class="ml-auto" size="sm" variant="outline">
            <Columns3 />
            Columns
            <ChevronDown />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" class="w-48">
          <DropdownMenuCheckboxItem
            v-for="column in table.getAllColumns().filter((c) => c.getCanHide())"
            :key="column.id"
            :model-value="column.getIsVisible()"
            @update:model-value="(value) => column.toggleVisibility(!!value)"
          >
            {{ column.columnDef.header }}
          </DropdownMenuCheckboxItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>

    <div class="overflow-hidden rounded-lg border">
      <Table>
        <TableHeader class="bg-muted">
          <TableRow v-for="headerGroup in table.getHeaderGroups()" :key="headerGroup.id">
            <TableHead
              v-for="header in headerGroup.headers"
              :key="header.id"
              :aria-sort="
                !header.column.getCanSort()
                  ? undefined
                  : header.column.getIsSorted() === 'asc'
                    ? 'ascending'
                    : header.column.getIsSorted() === 'desc'
                      ? 'descending'
                      : 'none'
              "
              :class="{
                'text-right': header.column.id === 'lastEventCount',
                [STICKY_HEAD]: header.column.id === 'actions',
              }"
            >
              <button
                v-if="header.column.getCanSort()"
                class="inline-flex items-center gap-1 rounded-sm hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
                type="button"
                @click="header.column.getToggleSortingHandler()?.($event)"
              >
                <FlexRender :header="header" />
                <ArrowUp
                  v-if="header.column.getIsSorted() === 'asc'"
                  aria-hidden="true"
                  class="size-3.5"
                />
                <ArrowDown
                  v-else-if="header.column.getIsSorted() === 'desc'"
                  aria-hidden="true"
                  class="size-3.5"
                />
              </button>
              <FlexRender v-else :header="header" />
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          <TableRow v-for="row in table.getRowModel().rows" :key="row.original.slug">
            <TableCell
              v-for="cell in row.getVisibleCells()"
              :key="cell.id"
              :class="{
                'whitespace-normal': cell.column.id === 'name',
                'font-mono': cell.column.id === 'slug' || cell.column.id === 'sourceType',
                'text-right tabular-nums': cell.column.id === 'lastEventCount',
                'max-w-xs whitespace-normal': cell.column.id === 'lastFailureReason',
                [STICKY_CELL]: cell.column.id === 'actions',
              }"
              :title="
                cell.column.id === 'lastFailureReason'
                  ? (row.original.lastFailureReason ?? undefined)
                  : undefined
              "
            >
              <a
                v-if="cell.column.id === 'name'"
                :href="row.original.url"
                class="underline-offset-4 hover:underline"
                rel="noreferrer"
                target="_blank"
              >
                {{ row.original.name }}
              </a>
              <template v-else-if="cell.column.id === 'enabled'">
                {{ row.original.enabled ? 'yes' : 'no' }}
              </template>
              <Badge
                v-else-if="cell.column.id === 'status'"
                :variant="statusVariant(row.original.status)"
              >
                {{ row.original.status }}
              </Badge>
              <template v-else-if="DATE_COLUMNS.has(cell.column.id)">
                {{ formatInstant(cell.getValue()) }}
              </template>
              <!-- A failure reason runs to hundreds of characters: two lines here, the rest on hover. -->
              <span v-else-if="cell.column.id === 'lastFailureReason'" class="line-clamp-2">
                {{ row.original.lastFailureReason ?? '' }}
              </span>
              <SourceActions
                v-else-if="cell.column.id === 'actions'"
                :source="row.original"
                @updated="replaceSource"
              />
              <template v-else>{{ cell.getValue() ?? '—' }}</template>
            </TableCell>
          </TableRow>
          <TableRow v-if="!table.getRowModel().rows.length">
            <TableCell :colspan="table.getVisibleLeafColumns().length" class="h-24 text-center">
              No source matches.
            </TableCell>
          </TableRow>
        </TableBody>
      </Table>
    </div>
  </template>
</template>
