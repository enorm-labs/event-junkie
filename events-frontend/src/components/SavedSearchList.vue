<script setup lang="ts">
/**
 * The saved searches on the Following page's follows tab (#3890, #4099): one row each, with how many
 * events match from today, a link to the list with its filters, and Rename and Delete. The matches
 * stay out of the feed, so a genre does not flood it.
 */
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import BaseInput from '@/components/BaseInput.vue'
import SectionLabel from '@/components/SectionLabel.vue'
import { Button } from '@/components/ui/button'
import type { SavedSearch } from '@/api/account'
import { useLocalePath } from '@/composables/useLocalePath'
import type { SavedSearches } from '@/composables/useSavedSearches'
import { listQuery } from '@/lib/savedSearch'

const props = defineProps<{ saved: SavedSearches }>()
const { searches, counts } = props.saved

const { t } = useI18n()
const localePath = useLocalePath()

const editing = ref<string | null>(null)
const draft = ref('')
const problem = ref('')

function startRename(search: SavedSearch) {
  editing.value = search.id
  draft.value = search.name
}

async function rename(search: SavedSearch) {
  const name = draft.value.trim()
  if (!name) return
  problem.value = ''
  try {
    await props.saved.rename(search, name)
    editing.value = null
  } catch {
    problem.value = t('account.search.failed')
  }
}

async function remove(search: SavedSearch) {
  problem.value = ''
  try {
    await props.saved.remove(search)
  } catch {
    problem.value = t('account.search.failed')
  }
}
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
