<script lang="ts" setup>
/**
 * Picks an act that has no Berlin date yet, to be told when it gets one (#5214). The BFF asks MusicBrainz for the name, so
 * the visitor's address never reaches it. A search starts once typing stops, with three characters or more. An act this
 * site already carries comes back with its slug, and the button follows that artist instead.
 */
import { Check, Plus } from '@lucide/vue'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import ErrorLine from '@/components/ErrorLine.vue'
import SearchInput from '@/components/SearchInput.vue'
import SectionLabel from '@/components/SectionLabel.vue'
import { Button } from '@/components/ui/button'
import {
  fetchWatches,
  type MusicBrainzCandidate,
  prepareCsrf,
  searchMusicBrainz,
  watchAct,
} from '@/api/account'
import { ApiError } from '@/api/client'
import { useLocalePath } from '@/composables/useLocalePath'
import { CARD_LIST_CLASS, UNDERLINED_LINK_CLASS } from '@/lib/utils'

const props = defineProps<{
  /** Searched at once, as the search page's term. */
  initialQuery?: string
  /** No link to the Following page after a pick: the page itself. */
  noHint?: boolean
}>()
const emit = defineEmits<{ change: [] }>()

const { t } = useI18n()
const localePath = useLocalePath()

const MIN_LENGTH = 3
/** Long enough that a typed name is one request, short enough not to feel slow. */
const DEBOUNCE_MS = 400

type State = 'idle' | 'loading' | 'found' | 'none' | 'unavailable' | 'failed'

const query = ref(props.initialQuery ?? '')
const state = ref<State>('idle')
const candidates = ref<MusicBrainzCandidate[]>([])
/** The ids watched or followed: those stored before and those picked here. */
const done = ref(new Set<string>())
const followed = ref(new Set<string>())
const busy = ref<string | null>(null)
const problem = ref('')
const picked = ref(false)

let timer: ReturnType<typeof setTimeout> | undefined
/** Counts the searches, so a slower answer to an older name never replaces a newer one. */
let ticket = 0
let csrfReady: Promise<void> | null = null

async function search(term: string) {
  const mine = ++ticket
  state.value = 'loading'
  try {
    const found = await searchMusicBrainz(term)
    if (mine !== ticket) return
    candidates.value = found
    state.value = found.length ? 'found' : 'none'
  } catch (e) {
    if (mine !== ticket) return
    candidates.value = []
    state.value = e instanceof ApiError && e.status === 503 ? 'unavailable' : 'failed'
  }
}

function schedule(term: string, delay: number) {
  clearTimeout(timer)
  const trimmed = term.trim()
  if (trimmed.length < MIN_LENGTH) {
    ticket++
    state.value = 'idle'
    candidates.value = []
    return
  }
  timer = setTimeout(() => void search(trimmed), delay)
}

watch(query, (term) => schedule(term, DEBOUNCE_MS))

onMounted(async () => {
  schedule(query.value, 0)
  try {
    done.value = new Set((await fetchWatches()).map((item) => item.mbid))
  } catch {
    // Only marks what is watched already; a pick still works, and a second one changes nothing.
  }
})

onBeforeUnmount(() => clearTimeout(timer))

async function pick(candidate: MusicBrainzCandidate) {
  if (busy.value) return
  busy.value = candidate.mbid
  problem.value = ''
  try {
    csrfReady ??= prepareCsrf()
    await csrfReady
    const outcome = await watchAct(candidate.mbid)
    done.value = new Set([...done.value, candidate.mbid])
    if (outcome.followed) followed.value = new Set([...followed.value, candidate.mbid])
    picked.value = true
    emit('change')
  } catch (e) {
    problem.value =
      e instanceof ApiError && e.status === 409
        ? t('account.follow.tooMany')
        : e instanceof ApiError && e.status === 503
          ? t('account.watch.unavailable')
          : t('account.watch.failed')
  } finally {
    busy.value = null
  }
}

/** The meta line: MusicBrainz's own note first, because it is what tells two namesakes apart. */
function meta(candidate: MusicBrainzCandidate): string {
  return [candidate.disambiguation, candidate.type, candidate.country].filter(Boolean).join(' · ')
}
</script>

<template>
  <div class="space-y-3">
    <form role="search" @submit.prevent="schedule(query, 0)">
      <SearchInput
        v-model="query"
        :aria-label="t('account.watch.label')"
        autocomplete="off"
        :placeholder="t('account.watch.placeholder')"
      />
    </form>
    <p class="text-meta text-muted-foreground">{{ t('account.watch.privacy') }}</p>

    <p v-if="state === 'idle'" class="text-body text-muted-foreground">
      {{ t('account.watch.hint') }}
    </p>
    <p v-else-if="state === 'loading'" class="text-body text-muted-foreground" role="status">
      {{ t('account.watch.loading') }}
    </p>
    <p v-else-if="state === 'none'" class="text-body text-muted-foreground" role="status">
      {{ t('account.watch.none', { q: query.trim() }) }}
    </p>
    <ErrorLine v-else-if="state === 'unavailable'">{{ t('account.watch.unavailable') }}</ErrorLine>
    <ErrorLine v-else-if="state === 'failed'">{{ t('account.watch.failed') }}</ErrorLine>

    <section v-else class="space-y-3">
      <SectionLabel as="h3">{{ t('account.watch.candidates') }}</SectionLabel>
      <ul :class="CARD_LIST_CLASS">
        <li
          v-for="candidate in candidates"
          :key="candidate.mbid"
          class="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 py-3"
        >
          <div class="min-w-0">
            <p class="text-body font-medium">
              <RouterLink
                v-if="candidate.followSlug"
                class="hover:underline"
                :to="localePath(`/artists/${candidate.followSlug}`)"
              >
                {{ candidate.name }}
              </RouterLink>
              <template v-else>{{ candidate.name }}</template>
            </p>
            <p v-if="meta(candidate)" class="text-meta text-muted-foreground">
              {{ meta(candidate) }}
            </p>
          </div>
          <p
            v-if="done.has(candidate.mbid)"
            class="flex items-center gap-1 text-meta text-muted-foreground"
          >
            <Check aria-hidden="true" class="size-4" />
            {{
              followed.has(candidate.mbid) || candidate.followSlug
                ? t('account.watch.followed')
                : t('account.watch.watching')
            }}
          </p>
          <Button
            v-else
            :aria-label="
              candidate.followSlug
                ? undefined
                : t('account.watch.notifyLabel', { name: candidate.name })
            "
            :disabled="busy !== null"
            size="sm"
            variant="outline"
            @click="pick(candidate)"
          >
            <Plus data-icon="inline-start" />
            {{ candidate.followSlug ? t('account.follow.follow') : t('account.watch.notify') }}
          </Button>
        </li>
      </ul>
    </section>

    <p v-if="problem" class="text-meta text-destructive" role="alert">{{ problem }}</p>
    <p v-else-if="picked && !noHint" class="text-meta text-muted-foreground" role="status">
      <RouterLink :class="UNDERLINED_LINK_CLASS" :to="localePath('/following?tab=follows')">
        {{ t('account.watch.shown') }}
      </RouterLink>
    </p>
  </div>
</template>
