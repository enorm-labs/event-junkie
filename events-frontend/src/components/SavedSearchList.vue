<script setup lang="ts">
/**
 * The saved searches on the Following page (#3890): one row each, with how many events match from
 * today and a link to the list with its filters. The matches stay out of the feed above, so a
 * genre does not flood it. The counts come from the public list, one small request per search.
 */
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import BaseInput from '@/components/BaseInput.vue'
import SectionLabel from '@/components/SectionLabel.vue'
import { Button } from '@/components/ui/button'
import {
  deleteSearch,
  fetchSearches,
  prepareCsrf,
  renameSearch,
  type SavedSearch,
} from '@/api/account'
import { searchEvents } from '@/composables/useEvents'
import { useLocalePath } from '@/composables/useLocalePath'
import { listQuery, searchParams } from '@/lib/savedSearch'

const emit = defineEmits<{ loaded: [count: number] }>()

const { t } = useI18n()
const localePath = useLocalePath()

const searches = ref<SavedSearch[]>([])
const counts = ref<Record<string, number>>({})
const editing = ref<string | null>(null)
const draft = ref('')
const problem = ref('')

async function load() {
  try {
    searches.value = await fetchSearches()
  } catch {
    // A help beside the follows, not the page: without it the page still works.
    searches.value = []
  }
  emit('loaded', searches.value.length)
  for (const search of searches.value) void count(search)
}

async function count(search: SavedSearch) {
  try {
    const page = await searchEvents({ ...searchParams(search.query), size: 1 })
    counts.value = { ...counts.value, [search.id]: page.totalElements ?? 0 }
  } catch {
    // The row stays, without its count.
  }
}

function startRename(search: SavedSearch) {
  editing.value = search.id
  draft.value = search.name
}

async function rename(search: SavedSearch) {
  const name = draft.value.trim()
  if (!name) return
  problem.value = ''
  try {
    await prepareCsrf()
    await renameSearch(search.id, name)
    search.name = name
    editing.value = null
  } catch {
    problem.value = t('account.search.failed')
  }
}

async function remove(search: SavedSearch) {
  problem.value = ''
  try {
    await prepareCsrf()
    await deleteSearch(search.id)
    searches.value = searches.value.filter((s) => s.id !== search.id)
    emit('loaded', searches.value.length)
  } catch {
    problem.value = t('account.search.failed')
  }
}

onMounted(load)
</script>

<template>
  <section v-if="searches.length" class="space-y-4">
    <SectionLabel as="h2">{{ t('account.search.list', { count: searches.length }) }}</SectionLabel>
    <p v-if="problem" class="text-body text-destructive" role="alert">{{ problem }}</p>
    <ul class="divide-y divide-border border-y border-border">
      <li
        v-for="search in searches"
        :key="search.id"
        class="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 py-3"
      >
        <form
          v-if="editing === search.id"
          class="flex min-w-0 flex-1 flex-wrap items-center gap-2"
          @submit.prevent="rename(search)"
        >
          <BaseInput
            v-model="draft"
            :aria-label="t('account.search.renameLabel', { name: search.name })"
            class="min-w-0 flex-1"
            maxlength="80"
            required
          />
          <Button size="sm" type="submit">{{ t('account.search.submit') }}</Button>
          <Button size="sm" type="button" variant="ghost" @click="editing = null">
            {{ t('account.search.cancel') }}
          </Button>
        </form>
        <div v-else class="min-w-0">
          <RouterLink
            :to="{ path: localePath('/events'), query: listQuery(search.query) }"
            class="text-body font-medium hover:underline"
          >
            {{ search.name }}
          </RouterLink>
          <p class="text-meta text-muted-foreground">
            {{ t('account.search.kind') }}
            <template v-if="counts[search.id] !== undefined">
              · {{ t('account.search.upcoming', { count: counts[search.id] }) }}
            </template>
          </p>
        </div>
        <div v-if="editing !== search.id" class="flex gap-2">
          <Button size="sm" variant="ghost" @click="startRename(search)">
            {{ t('account.search.rename') }}
          </Button>
          <Button size="sm" variant="outline" @click="remove(search)">
            {{ t('account.search.delete') }}
          </Button>
        </div>
      </li>
    </ul>
  </section>
</template>
