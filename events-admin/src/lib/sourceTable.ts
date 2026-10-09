import {
  columnVisibilityFeature,
  createColumnHelper,
  createSortedRowModel,
  rowSortingFeature,
  tableFeatures,
} from '@tanstack/vue-table'

import type { EventSource } from '@/api/eventSources'

/** TanStack Table v9 bundles only the features a table registers. */
export const features = tableFeatures({
  columnVisibilityFeature,
  rowSortingFeature,
  sortedRowModel: createSortedRowModel(),
})

const helper = createColumnHelper<typeof features, EventSource>()
// Names and slugs sort with the German collator, so Æ sits beside A and Ü beside U.
const collator = new Intl.Collator('de', { sensitivity: 'base', numeric: true })

// An empty value becomes `undefined`, which `sortUndefined: 'last'` keeps last in both directions.
const orNothing = <T>(value: T | null) => value ?? undefined

export const columns = helper.columns([
  helper.accessor('name', {
    header: 'Name',
    sortFn: (a, b) => collator.compare(a.original.name, b.original.name),
    enableHiding: false,
  }),
  helper.accessor('slug', {
    header: 'Slug',
    sortFn: (a, b) => collator.compare(a.original.slug, b.original.slug),
  }),
  helper.accessor('sourceType', { header: 'Type' }),
  helper.accessor('enabled', { header: 'Enabled', sortDescFirst: true }),
  helper.accessor('status', { header: 'Status' }),
  helper.accessor((s) => orNothing(s.lastImportAt), {
    id: 'lastImportAt',
    header: 'Last import',
    sortDescFirst: true,
    sortUndefined: 'last',
  }),
  helper.accessor((s) => orNothing(s.lastSuccessAt), {
    id: 'lastSuccessAt',
    header: 'Last success',
    sortDescFirst: true,
    sortUndefined: 'last',
  }),
  helper.accessor((s) => orNothing(s.lastEventCount), {
    id: 'lastEventCount',
    header: 'Events',
    sortDescFirst: true,
    sortUndefined: 'last',
  }),
  helper.accessor((s) => orNothing(s.lastFailureReason), {
    id: 'lastFailureReason',
    header: 'Failure',
    sortUndefined: 'last',
  }),
  helper.accessor((s) => orNothing(s.flaggedAt), {
    id: 'flaggedAt',
    header: 'Flagged',
    sortDescFirst: true,
    sortUndefined: 'last',
  }),
  // No accessor, so it cannot sort. `SourceActions.vue` renders the cell.
  helper.display({ id: 'actions', header: 'Actions', enableHiding: false }),
])

/** Type is the slug in capitals for nearly every source, so it starts hidden; the menu shows it. */
export const INITIAL_VISIBILITY = { sourceType: false }
