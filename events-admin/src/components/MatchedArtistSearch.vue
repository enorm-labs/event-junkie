<script setup lang="ts">
import { Ban, ExternalLink, Search, X } from '@lucide/vue'
import { ref, shallowRef } from 'vue'

import { rejectMusicBrainzMatch, type ReviewArtist, searchMatchedArtists } from '@/api/artists'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { errorText } from '@/lib/errorText'

defineProps<{ origin: string }>()

const emit = defineEmits<{ rejected: [artist: ReviewArtist] }>()

const term = ref('')
/** Null until the first search. */
const results = shallowRef<ReviewArtist[] | null>(null)
const searching = ref(false)
const searchError = ref<string | null>(null)
/** The row the operator asked to reject and has not confirmed yet. */
const chosen = ref<ReviewArtist | null>(null)
const saving = ref(false)
const saveError = ref<string | null>(null)

async function search() {
  const typed = term.value.trim()
  if (!typed) return
  searching.value = true
  searchError.value = null
  chosen.value = null
  try {
    results.value = await searchMatchedArtists(typed)
  } catch (e) {
    results.value = null
    searchError.value = errorText(e)
  } finally {
    searching.value = false
  }
}

function choose(artist: ReviewArtist) {
  saveError.value = null
  chosen.value = artist
}

async function confirm() {
  const artist = chosen.value
  if (!artist) return
  saving.value = true
  saveError.value = null
  let saved: ReviewArtist
  try {
    saved = await rejectMusicBrainzMatch(artist.id)
  } catch (e) {
    saveError.value = errorText(e)
    return
  } finally {
    saving.value = false
  }
  // An emptied list is hidden: "no matched artist" would read as a failed search.
  const remaining = (results.value ?? []).filter((a) => a.id !== artist.id)
  results.value = remaining.length ? remaining : null
  chosen.value = null
  emit('rejected', saved)
}
</script>

<template>
  <section class="flex flex-col gap-3 rounded-lg border p-4" data-testid="matched-search">
    <h2 class="text-sm font-medium">Matched artists</h2>
    <p class="max-w-prose text-sm text-muted-foreground">
      A page that shows another act's text, photo and links has a wrong match. Find the artist by
      name and reject the match.
    </p>
    <form class="flex flex-wrap items-center gap-2" role="search" @submit.prevent="search">
      <label for="matched-name">
        <span class="sr-only">Artist name</span>
        <Input
          id="matched-name"
          v-model="term"
          class="h-8 w-64"
          name="name"
          placeholder="Part of the name"
          type="search"
        />
      </label>
      <Button :disabled="searching || !term.trim()" size="sm" type="submit" variant="outline">
        <Search aria-hidden="true" />
        Search
      </Button>
    </form>

    <p v-if="searchError" class="text-sm text-destructive" role="alert">{{ searchError }}</p>
    <p
      v-else-if="results && !results.length"
      class="text-sm text-muted-foreground"
      data-testid="no-matched"
    >
      No matched artist has this in its name.
    </p>
    <ul v-else-if="results" class="flex flex-col gap-2 text-sm">
      <li
        v-for="artist in results"
        :key="artist.id"
        class="flex flex-wrap items-center gap-2 rounded-md border p-2"
        data-testid="matched-artist"
      >
        <div class="flex min-w-0 flex-1 flex-wrap items-center gap-x-3 gap-y-1 break-words">
          <a
            :href="`${origin}/en/artists/${encodeURIComponent(artist.slug)}`"
            class="inline-flex items-center gap-1 font-medium underline-offset-4 hover:underline"
            rel="noopener noreferrer"
            target="_blank"
          >
            {{ artist.name }}
            <ExternalLink aria-hidden="true" class="size-3 shrink-0" />
          </a>
          <a
            v-if="artist.musicbrainzId"
            :href="`https://musicbrainz.org/artist/${artist.musicbrainzId}`"
            class="inline-flex items-center gap-1 text-muted-foreground underline-offset-4 hover:underline"
            rel="noopener noreferrer"
            target="_blank"
          >
            MusicBrainz
            <ExternalLink aria-hidden="true" class="size-3 shrink-0" />
          </a>
        </div>
        <Button
          :aria-label="`Reject the MusicBrainz match of ${artist.name}`"
          :disabled="saving"
          :variant="chosen?.id === artist.id ? 'default' : 'outline'"
          size="xs"
          @click="choose(artist)"
        >
          Reject match
        </Button>
      </li>
    </ul>

    <div
      v-if="chosen"
      class="flex flex-col gap-2 rounded-md border border-destructive/40 bg-muted/50 p-3 text-sm"
      data-testid="confirm-reject"
      role="group"
      aria-label="Confirm the rejection"
    >
      <p class="break-words">
        Reject the MusicBrainz match of <strong>{{ chosen.name }}</strong>? Its text, Commons photo
        and links are cleared, and it goes to the review list as AMBIGUOUS. The sweep never matches
        it to this MusicBrainz artist again.
      </p>
      <p v-if="saveError" class="text-destructive" role="alert">{{ saveError }}</p>
      <div class="flex flex-wrap gap-2">
        <Button :disabled="saving" size="sm" variant="destructive" @click="confirm">
          <Ban aria-hidden="true" />
          Reject
        </Button>
        <Button :disabled="saving" size="sm" variant="outline" @click="chosen = null">
          <X aria-hidden="true" />
          Cancel
        </Button>
      </div>
    </div>
  </section>
</template>
