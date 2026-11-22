<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  fetchReviewPage,
  fetchWatchedArtists,
  type MusicBrainzCandidate,
  REVIEW_DAYS,
  type ReviewArtist,
  type ReviewPage,
} from '@/api/artists'
import { fetchAllVenues } from '@/api/venues'
import ArtistReview from '@/components/ArtistReview.vue'
import MatchedArtistSearch from '@/components/MatchedArtistSearch.vue'
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

// The AMBIGUOUS rows visitors wait for (#5214) come first, on the first page, whatever their dates: a watch turns into a
// follow only once its row is EXACT. A failed read leaves the regular list, which is the page's job.
const { data: watchedLoaded, reload: loadWatched } = useLoad<ReviewArtist[]>(
  () => fetchWatchedArtists(),
  [],
)
const watched = ref<ReviewArtist[]>([])
watch(watchedLoaded, (loaded) => {
  watched.value = [...loaded]
})
const watchedIds = computed(() => new Set(watched.value.map((a) => a.id)))
const listed = computed(() => artists.value.filter((a) => !watchedIds.value.has(a.id)))

const origin = publicOrigin(import.meta.env.MODE)

watch(page, reload, { immediate: true })
onMounted(() => Promise.all([loadVenueNames(), loadWatched()]))

/** What is left: the total the importer counted, less what this page matched since, plus what it rejected. */
const matched = ref(0)
const rejected = ref(0)
watch(result, (loaded) => {
  artists.value = loaded ? [...loaded.content] : []
  matched.value = 0
  rejected.value = 0
})
const left = computed(() =>
  Math.max(0, (result.value?.totalElements ?? 0) - matched.value + rejected.value),
)
const hasNext = computed(() => page.value + 1 < (result.value?.totalPages ?? 0))

function goTo(next: number) {
  notice.value = null
  return router.replace({ query: next ? { page: String(next) } : {} })
}

function onMatched(artist: ReviewArtist, candidate: MusicBrainzCandidate) {
  watched.value = watched.value.filter((a) => a.id !== artist.id)
  // The count is the page's own: only a row of this list lowers it.
  if (artists.value.some((a) => a.id === artist.id)) matched.value++
  artists.value = artists.value.filter((a) => a.id !== artist.id)
  notice.value = `${artist.name} is matched to ${candidate.name} on MusicBrainz.`
}

// A rejected row is AMBIGUOUS now, so it heads the list to be reviewed (#5381).
function onRejected(artist: ReviewArtist) {
  if (!artists.value.some((a) => a.id === artist.id)) {
    artists.value = [artist, ...artists.value]
    rejected.value++
  }
  notice.value = `The MusicBrainz match of ${artist.name} is rejected. It is in the list below.`
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

  <MatchedArtistSearch :origin="origin" @rejected="onRejected" />

  <p v-if="notice" class="text-sm" role="status">{{ notice }}</p>

  <section v-if="page === 0 && watched.length" class="flex flex-col gap-3" data-testid="watched">
    <h2 class="text-sm font-medium">Visitors wait for these</h2>
    <p class="max-w-prose text-sm text-muted-foreground">
      AMBIGUOUS rows whose name visitors watch, from any date. A watch becomes a follow once the row
      is EXACT. A candidate marked "waiting" is the MusicBrainz id they picked.
    </p>
    <ul class="flex flex-col gap-3">
      <ArtistReview
        v-for="artist in watched"
        :key="artist.id"
        :artist="artist"
        :origin="origin"
        :venue-names="venueNames"
        @matched="(candidate) => onMatched(artist, candidate)"
      />
    </ul>
  </section>
  <p v-if="error" class="text-destructive" role="alert">{{ error }}</p>
  <p v-else-if="loading" class="text-muted-foreground">Loading the artists…</p>
  <p
    v-else-if="!listed.length"
    class="rounded-lg border p-6 text-center text-sm"
    data-testid="no-artists"
  >
    No artist on this page needs a review.
  </p>

  <ul v-if="!loading && !error" class="flex flex-col gap-3">
    <ArtistReview
      v-for="artist in listed"
      :key="artist.id"
      :artist="artist"
      :origin="origin"
      :venue-names="venueNames"
      @matched="(candidate) => onMatched(artist, candidate)"
    />
  </ul>
</template>
