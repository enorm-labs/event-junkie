<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
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
import PagerButtons from '@/components/PagerButtons.vue'
import { useLoad } from '@/composables/useLoad'
import { publicOrigin } from '@/lib/environments'

const route = useRoute()
const router = useRouter()

// The page is in the query string, so a reload keeps it.
const page = computed(() => Math.max(0, Number(route.query.page) || 0))

const {
  data: result,
  loading,
  error,
  reload,
} = useLoad<ReviewPage | null>(() => fetchReviewPage(page.value), null)
const artists = ref<ReviewArtist[]>([])
const notice = ref<string | null>(null)
// A venue name is a nicety beside the date and the title, so a failed read leaves the ids.
const { data: venueNames, reload: loadVenueNames } = useLoad<ReadonlyMap<number, string>>(
  async () => new Map((await fetchAllVenues()).map((v) => [v.id, v.name])),
  new Map(),
)

const origin = publicOrigin(import.meta.env.MODE)

watch(page, reload, { immediate: true })
onMounted(loadVenueNames)

/** What is left: the total the importer counted, less what this page matched since. */
const matched = ref(0)
watch(result, (loaded) => {
  artists.value = loaded ? [...loaded.content] : []
  matched.value = 0
})
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
      <PagerButtons
        :has-next="hasNext"
        :has-previous="page > 0"
        :loading="loading"
        @next="goTo(page + 1)"
        @previous="goTo(page - 1)"
      />
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
