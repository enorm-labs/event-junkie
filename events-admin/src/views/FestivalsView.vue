<script setup lang="ts">
import { Check, Pencil, Plus, Trash2, X } from '@lucide/vue'
import { onMounted, ref, shallowRef } from 'vue'

import { deleteFestival, type Festival, fetchFestivals } from '@/api/festivals'
import FestivalForm from '@/components/FestivalForm.vue'
import { Button } from '@/components/ui/button'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'

const festivals = shallowRef<Festival[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
/** The festival the form edits: null for a new one, undefined while the form is closed. */
const editing = ref<Festival | null | undefined>(undefined)
/** The festival whose deletion waits for a confirmation. */
const confirming = ref<Festival | null>(null)
const deleting = ref(false)
const deleteError = ref<string | null>(null)

/** The importer lists the latest first day first. A save keeps that order. */
const byFirstDayDesc = (a: Festival, b: Festival) =>
  b.firstDay.localeCompare(a.firstDay) || b.id - a.id

onMounted(async () => {
  try {
    festivals.value = await fetchFestivals()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
})

function saved(festival: Festival) {
  festivals.value = [...festivals.value.filter((f) => f.id !== festival.id), festival].sort(
    byFirstDayDesc,
  )
  editing.value = undefined
}

async function remove(festival: Festival) {
  deleting.value = true
  deleteError.value = null
  try {
    await deleteFestival(festival.id)
    festivals.value = festivals.value.filter((f) => f.id !== festival.id)
    confirming.value = null
  } catch (e) {
    deleteError.value = e instanceof Error ? e.message : String(e)
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <p v-if="loading" class="text-muted-foreground">Loading the festivals…</p>
  <p v-else-if="error" class="text-destructive" role="alert">{{ error }}</p>
  <template v-else>
    <div class="flex flex-wrap items-center gap-2">
      <Button size="sm" @click="editing = null">
        <Plus aria-hidden="true" />
        New festival
      </Button>
      <p class="text-sm text-muted-foreground" data-testid="festival-count">
        {{ festivals.length }} festivals
      </p>
    </div>

    <div
      v-if="confirming"
      :aria-label="`Confirm deleting ${confirming.name}`"
      class="flex flex-col gap-2 rounded-md border border-destructive/40 bg-muted/50 p-3 text-sm"
      data-testid="confirm-delete"
      role="group"
    >
      <p>
        Delete <strong>{{ confirming.name }}</strong
        >? The home page stops announcing it.
      </p>
      <p v-if="deleteError" class="text-destructive" role="alert">{{ deleteError }}</p>
      <div class="flex flex-wrap gap-2">
        <Button :disabled="deleting" size="sm" variant="destructive" @click="remove(confirming)">
          <Check aria-hidden="true" />
          Delete
        </Button>
        <Button
          :disabled="deleting"
          size="sm"
          variant="outline"
          @click="((confirming = null), (deleteError = null))"
        >
          <X aria-hidden="true" />
          Cancel
        </Button>
      </div>
    </div>

    <div class="overflow-hidden rounded-lg border">
      <Table>
        <TableHeader class="bg-muted">
          <TableRow>
            <TableHead>Festival</TableHead>
            <TableHead>Days</TableHead>
            <TableHead><span class="sr-only">Actions</span></TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          <TableRow v-for="festival in festivals" :key="festival.id" data-testid="festival-row">
            <TableCell class="font-medium whitespace-normal">
              <span data-testid="festival-name">{{ festival.name }}</span>
              <a
                :href="festival.url"
                class="block font-mono text-xs font-normal break-all text-muted-foreground hover:underline"
                rel="noopener noreferrer"
                target="_blank"
                >{{ festival.url }}</a
              >
            </TableCell>
            <TableCell class="font-mono text-xs whitespace-normal">
              {{ festival.firstDay
              }}{{ festival.lastDay === festival.firstDay ? '' : ` – ${festival.lastDay}` }}
            </TableCell>
            <TableCell class="text-right whitespace-nowrap">
              <div class="flex justify-end gap-2">
                <Button
                  :aria-label="`Edit ${festival.name}`"
                  size="xs"
                  variant="outline"
                  @click="editing = festival"
                >
                  <Pencil aria-hidden="true" />
                  <span class="hidden sm:inline">Edit</span>
                </Button>
                <Button
                  :aria-label="`Delete ${festival.name}`"
                  size="xs"
                  variant="outline"
                  @click="((confirming = festival), (deleteError = null))"
                >
                  <Trash2 aria-hidden="true" />
                </Button>
              </div>
            </TableCell>
          </TableRow>
          <TableRow v-if="!festivals.length">
            <TableCell class="h-24 text-center" colspan="3">No festival yet.</TableCell>
          </TableRow>
        </TableBody>
      </Table>
    </div>
  </template>

  <Sheet :open="editing !== undefined" @update:open="(value) => value || (editing = undefined)">
    <SheetContent class="w-full overflow-y-auto sm:max-w-lg">
      <SheetHeader>
        <SheetTitle>{{ editing ? editing.name : 'New festival' }}</SheetTitle>
        <SheetDescription>
          A festival across many venues. The home page links to its programme.
        </SheetDescription>
      </SheetHeader>
      <div class="px-4 pb-4">
        <FestivalForm
          v-if="editing !== undefined"
          :key="editing?.id ?? 'new'"
          :festival="editing"
          @cancel="editing = undefined"
          @saved="saved"
        />
      </div>
    </SheetContent>
  </Sheet>
</template>
