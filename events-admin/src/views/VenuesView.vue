<script setup lang="ts">
import { Tags } from '@lucide/vue'
import { computed, onMounted, ref, shallowRef } from 'vue'

import { type AdminVenue, fetchAllVenues } from '@/api/venues'
import VenueCharacterTags from '@/components/VenueCharacterTags.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
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

const venues = shallowRef<AdminVenue[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const query = ref('')
const open = ref<AdminVenue | null>(null)

onMounted(async () => {
  try {
    venues.value = await fetchAllVenues()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
})

// The importer sorts by name. Closed venues go to the end, still by name.
const ordered = computed(() => [
  ...venues.value.filter((v) => !v.closedOn),
  ...venues.value.filter((v) => v.closedOn),
])

// The page holds every venue, so the filter runs in the browser.
const shown = computed(() => {
  const needle = query.value.trim().toLowerCase()
  if (!needle) return ordered.value
  return ordered.value.filter((v) => v.name.toLowerCase().includes(needle))
})
</script>

<template>
  <p v-if="loading" class="text-muted-foreground">Loading every page of venues…</p>
  <p v-else-if="error" class="text-destructive" role="alert">{{ error }}</p>
  <template v-else>
    <div class="flex flex-wrap items-center gap-2">
      <Input
        v-model="query"
        aria-label="Filter by name"
        class="h-8 w-full sm:w-64"
        placeholder="Filter by name"
      />
      <p class="text-sm text-muted-foreground" data-testid="venue-count">
        {{ shown.length }} of {{ venues.length }} venues
      </p>
    </div>

    <div class="overflow-hidden rounded-lg border">
      <Table>
        <TableHeader class="bg-muted">
          <TableRow>
            <TableHead>Venue</TableHead>
            <TableHead>Slug</TableHead>
            <TableHead><span class="sr-only">Character tags</span></TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          <TableRow
            v-for="venue in shown"
            :key="venue.id"
            :class="venue.closedOn ? 'text-muted-foreground' : undefined"
            data-testid="venue-row"
          >
            <TableCell class="font-medium">
              {{ venue.name }}
              <span v-if="venue.closedOn" class="block text-xs font-normal">
                Closed on {{ venue.closedOn }}
              </span>
            </TableCell>
            <TableCell class="font-mono text-xs">{{ venue.slug }}</TableCell>
            <TableCell class="text-right">
              <Button
                :aria-label="`Character tags of ${venue.name}`"
                size="xs"
                variant="outline"
                @click="open = venue"
              >
                <Tags aria-hidden="true" />
                Tags
              </Button>
            </TableCell>
          </TableRow>
          <TableRow v-if="!shown.length">
            <TableCell class="h-24 text-center" colspan="3">No venue matches.</TableCell>
          </TableRow>
        </TableBody>
      </Table>
    </div>
  </template>

  <Sheet :open="open !== null" @update:open="(value) => value || (open = null)">
    <SheetContent class="w-full overflow-y-auto sm:max-w-lg">
      <SheetHeader>
        <SheetTitle>{{ open?.name }}</SheetTitle>
        <SheetDescription>
          A tag needs the https:// URL of the venue’s own page that states it. No review, listing or
          impression.
        </SheetDescription>
      </SheetHeader>
      <div class="px-4 pb-4">
        <VenueCharacterTags v-if="open" :key="open.id" :venue="open" />
      </div>
    </SheetContent>
  </Sheet>
</template>
