<script setup lang="ts">
import { ChevronLeft, ChevronRight } from '@lucide/vue'
import { computed, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  fetchReviewPage,
  type MusicBrainzCandidate,
  REVIEW_DAYS,
  type ReviewArtist,
  type ReviewPage,
} from '@/api/artists'
import { fetchAllVenues } from '@/api/venues'
import ArtistReview from '@/components/ArtistReview.vue'
import { Button } from '@/components/ui/button'
import { publicOrigin } from '@/lib/environments'

const route = useRoute()
const router = useRouter()

// The page is in the query string, so a reload keeps it.
const page = computed(() => Math.max(0, Number(route.query.page) || 0))

const result = shallowRef<ReviewPage | null>(null)
const artists = ref<ReviewArtist[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const notice = ref<string | null>(null)
const venueNames = shallowRef<ReadonlyMap<number, string>>(new Map())

const origin = publicOrigin(import.meta.env.MODE)

async function load() {
  loading.value = true
  error.value = null
  try {
    result.value = await fetchReviewPage(page.value)
    artists.value = [...result.value.content]
  } catch (e) {
    result.value = null
    artists.value = []
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

watch(page, load, { immediate: true })

// A venue name is a nicety beside the date and the title, so a failed read leaves the ids.
onMounted(async () => {
  try {
    const venues = await fetchAllVenues()
    venueNames.value = new Map(venues.map((v) => [v.id, v.name]))
  } catch {
    venueNames.value = new Map()
  }
})

/** What is left: the total the importer counted, less what this page matched since. */
const matched = ref(0)
watch(result, () => (matched.value = 0))
const left = computed(() => Math.max(0, (result.value?.totalElements ?? 0) - matched.value))
const hasNext = computed(() => page.value + 1 < (result.value?.totalPages ?? 0))

function goTo(next: number) {
  notice.value = null
  return router.replace({ query: next ? { page: String(next) } : {} })
}

function onMatched(artist: ReviewArtist, candidate: MusicBrainzCandidate) {
  artists.value = artists.value.filter((a) => a.id !== artist.id)
  matched.value++
  notice.value = `${artist.name} is matched to ${candidate.name} on MusicBrainz.`
}
</script>

<template>
  <div class="flex flex-wrap items-center gap-2">
    <p class="max-w-prose text-sm text-muted-foreground">
      Artists billed in the next {{ REVIEW_DAYS }} days whose MusicBrainz match is AMBIGUOUS. Read
      the billing, then choose the candidate that is this act. When none fits, leave the row.
    </p>
    <div class="ml-auto flex items-center gap-2">
      <p class="text-sm text-muted-foreground tabular-nums" data-testid="left-count">
        {{ left }} to review
      </p>
      <Button
        :disabled="page === 0 || loading"
        aria-label="Previous page"
        size="icon-sm"
        variant="outline"
        @click="goTo(page - 1)"
      >
        <ChevronLeft />
      </Button>
      <Button
        :disabled="!hasNext || loading"
        aria-label="Next page"
        size="icon-sm"
        variant="outline"
        @click="goTo(page + 1)"
      >
        <ChevronRight />
      </Button>
    </div>
  </div>

  <p v-if="notice" class="text-sm" role="status">{{ notice }}</p>
  <p v-if="error" class="text-destructive" role="alert">{{ error }}</p>
  <p v-else-if="loading" class="text-muted-foreground">Loading the artists…</p>
  <p
    v-else-if="!artists.length"
    class="rounded-lg border p-6 text-center text-sm"
    data-testid="no-artists"
  >
    No artist on this page needs a review.
  </p>

  <ul v-if="!loading && !error" class="flex flex-col gap-3">
    <ArtistReview
      v-for="artist in artists"
      :key="artist.id"
      :artist="artist"
      :origin="origin"
      :venue-names="venueNames"
      @matched="(candidate) => onMatched(artist, candidate)"
    />
  </ul>
</template>
