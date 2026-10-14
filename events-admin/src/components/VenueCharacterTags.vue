<script setup lang="ts">
import { Check, Trash2, X } from '@lucide/vue'
import { onMounted, reactive, ref } from 'vue'

import {
  type AdminVenue,
  clearCharacterTag,
  fetchCharacterTags,
  isHttpsUrl,
  setCharacterTag,
  VENUE_CHARACTER_TAGS,
} from '@/api/venues'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'

const props = defineProps<{ venue: AdminVenue }>()

/** The source URL the importer stores, by tag slug. */
const stored = reactive(new Map<string, string>())
/** What each URL field holds. */
const drafts = reactive<Record<string, string>>({})
/** A row's own message: a refused URL or the importer's answer. */
const rowErrors = reactive<Record<string, string>>({})
const loading = ref(true)
const error = ref<string | null>(null)
/** The tag whose request runs. One at a time. */
const busy = ref<string | null>(null)
/** The tag whose clearing waits for a confirmation. */
const confirming = ref<string | null>(null)

const message = (e: unknown) => (e instanceof Error ? e.message : String(e))

onMounted(async () => {
  try {
    for (const { tag, sourceUrl } of await fetchCharacterTags(props.venue.id)) {
      stored.set(tag, sourceUrl)
    }
  } catch (e) {
    error.value = message(e)
  } finally {
    for (const { slug } of VENUE_CHARACTER_TAGS) drafts[slug] = stored.get(slug) ?? ''
    loading.value = false
  }
})

const changed = (slug: string) => drafts[slug]!.trim() !== (stored.get(slug) ?? '')

async function save(slug: string) {
  const url = drafts[slug]!.trim()
  delete rowErrors[slug]
  if (!isHttpsUrl(url)) {
    rowErrors[slug] = 'Enter the https:// URL of the venue’s own page that states the tag.'
    return
  }
  busy.value = slug
  try {
    const saved = await setCharacterTag(props.venue.id, slug, url)
    stored.set(slug, saved.sourceUrl)
    drafts[slug] = saved.sourceUrl
  } catch (e) {
    rowErrors[slug] = message(e)
  } finally {
    busy.value = null
  }
}

async function clear(slug: string) {
  delete rowErrors[slug]
  busy.value = slug
  try {
    await clearCharacterTag(props.venue.id, slug)
    stored.delete(slug)
    drafts[slug] = ''
    confirming.value = null
  } catch (e) {
    rowErrors[slug] = message(e)
  } finally {
    busy.value = null
  }
}
</script>

<template>
  <p v-if="loading" class="text-sm text-muted-foreground">Loading the tags…</p>
  <p v-else-if="error" class="text-sm text-destructive" role="alert">{{ error }}</p>
  <ul v-else class="flex flex-col divide-y" data-testid="character-tags">
    <li
      v-for="tag in VENUE_CHARACTER_TAGS"
      :key="tag.slug"
      class="flex flex-col gap-1.5 py-3"
      :data-tag="tag.slug"
    >
      <form class="flex items-end gap-2" novalidate @submit.prevent="save(tag.slug)">
        <label class="flex min-w-0 flex-1 flex-col gap-1.5" :for="`tag-${tag.slug}`">
          <span class="flex flex-col">
            <span class="text-sm font-medium">{{ tag.label }}</span>
            <span class="font-mono text-xs text-muted-foreground">
              {{ stored.has(tag.slug) ? tag.slug : tag.slug + ' · not set' }}
            </span>
          </span>
          <Input
            :id="`tag-${tag.slug}`"
            v-model="drafts[tag.slug]"
            :aria-invalid="rowErrors[tag.slug] ? true : undefined"
            :disabled="busy !== null"
            class="h-8 font-mono text-xs"
            type="url"
          />
        </label>
        <Button
          :aria-label="`Save ${tag.label}`"
          :disabled="busy !== null || !drafts[tag.slug]?.trim() || !changed(tag.slug)"
          size="sm"
          type="submit"
        >
          Save
        </Button>
        <Button
          v-if="stored.has(tag.slug)"
          :aria-label="`Clear ${tag.label}`"
          :disabled="busy !== null"
          size="sm"
          type="button"
          variant="outline"
          @click="confirming = tag.slug"
        >
          <Trash2 aria-hidden="true" />
        </Button>
      </form>
      <p v-if="rowErrors[tag.slug]" class="text-xs text-destructive" role="alert">
        {{ rowErrors[tag.slug] }}
      </p>
      <div
        v-if="confirming === tag.slug"
        :aria-label="`Confirm clearing ${tag.label}`"
        class="flex flex-col gap-2 rounded-md border border-destructive/40 bg-muted/50 p-3 text-sm"
        data-testid="confirm-clear"
        role="group"
      >
        <p>
          Remove <strong>{{ tag.label }}</strong> from {{ venue.name }}? The public site stops
          showing it.
        </p>
        <div class="flex flex-wrap gap-2">
          <Button
            :disabled="busy !== null"
            size="sm"
            variant="destructive"
            @click="clear(tag.slug)"
          >
            <Check aria-hidden="true" />
            Clear
          </Button>
          <Button :disabled="busy !== null" size="sm" variant="outline" @click="confirming = null">
            <X aria-hidden="true" />
            Cancel
          </Button>
        </div>
      </div>
    </li>
  </ul>
</template>
