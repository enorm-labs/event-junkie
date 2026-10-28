<script setup lang="ts">
/**
 * "Save search" on the events list (#3890). The name is suggested from the filter labels and can be
 * changed. One genre or one district alone saves with a single press, named after it. Signed out,
 * it leads to sign-in and back to the list; the filters are not carried, because the sign-in only
 * returns to a path (#3889).
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { Bookmark } from '@lucide/vue'
import BaseInput from '@/components/BaseInput.vue'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { prepareCsrf, saveSearch } from '@/api/account'
import { ApiError } from '@/api/client'
import { useLocalePath } from '@/composables/useLocalePath'
import { useSession } from '@/composables/useSession'
import { savedQuery, singleFilter } from '@/lib/savedSearch'

type FilterValue = string | number | boolean | readonly string[] | undefined

const props = defineProps<{
  filters: Readonly<Record<string, FilterValue>>
  range: { from?: string; to?: string }
  /** The active filters as the filter bar names them, from `useFilterLabels`. */
  labels: string[]
}>()

const MAX_NAME = 80

const { t } = useI18n()
const localePath = useLocalePath()
const { account } = useSession()

const saving = computed(() => savedQuery(props.filters, props.range))
const single = computed(() =>
  singleFilter(props.filters, props.range) && props.labels.length === 1 ? props.labels[0] : null,
)
const suggested = computed(() => props.labels.join(', ').slice(0, MAX_NAME))

const open = ref(false)
const name = ref('')
const status = ref<'idle' | 'busy' | 'saved' | 'failed' | 'tooMany'>('idle')

watch(open, (isOpen) => {
  if (isOpen) {
    name.value = suggested.value
    status.value = 'idle'
  }
})
// A changed filter is another search; an old "Saved." would be about the previous one.
watch(saving, () => {
  status.value = 'idle'
})

async function save(chosen: string) {
  const query = saving.value?.query
  if (!query || !chosen.trim() || status.value === 'busy') return
  status.value = 'busy'
  try {
    await prepareCsrf()
    await saveSearch(chosen.trim(), query)
    status.value = 'saved'
    open.value = false
  } catch (e) {
    status.value = e instanceof ApiError && e.status === 409 ? 'tooMany' : 'failed'
  }
}
</script>

<template>
  <span v-if="saving" class="inline-flex flex-wrap items-center gap-x-2">
    <RouterLink
      v-if="!account"
      :title="t('account.search.signIn')"
      :to="{ path: localePath('/sign-in'), query: { next: localePath('/events') } }"
      class="inline-flex items-center gap-1 text-primary hover:underline"
    >
      <Bookmark aria-hidden="true" class="size-4" />
      {{ t('account.search.save') }}
    </RouterLink>
    <button
      v-else-if="single"
      :disabled="status === 'busy'"
      class="inline-flex items-center gap-1 text-primary hover:underline"
      data-testid="save-search"
      type="button"
      @click="save(single)"
    >
      <Bookmark aria-hidden="true" class="size-4" />
      {{ t('account.search.follow', { name: single }) }}
    </button>
    <Popover v-else v-model:open="open">
      <PopoverTrigger as-child>
        <button
          class="inline-flex items-center gap-1 text-primary hover:underline"
          data-testid="save-search"
          type="button"
        >
          <Bookmark aria-hidden="true" class="size-4" />
          {{ t('account.search.save') }}
        </button>
      </PopoverTrigger>
      <PopoverContent align="start" class="w-80">
        <form class="space-y-3" @submit.prevent="save(name)">
          <label class="block text-body font-medium" for="saved-search-name">
            {{ t('account.search.nameLabel') }}
          </label>
          <BaseInput
            id="saved-search-name"
            v-model="name"
            :maxlength="MAX_NAME"
            class="w-full"
            name="name"
            required
          />
          <p v-if="saving.datesDropped" class="text-meta text-muted-foreground">
            {{ t('account.search.datesDropped') }}
          </p>
          <Button :disabled="status === 'busy' || !name.trim()" size="sm" type="submit">
            {{ t('account.search.submit') }}
          </Button>
        </form>
      </PopoverContent>
    </Popover>
    <!-- Announced when it changes, so a screen reader hears the outcome of the press. -->
    <span class="text-muted-foreground" role="status">
      <template v-if="status === 'saved'">
        {{ t('account.search.saved') }}
        <RouterLink
          :to="{ path: localePath('/following'), query: { tab: 'follows' } }"
          class="text-primary hover:underline"
        >
          {{ t('account.search.open') }}
        </RouterLink>
      </template>
      <span v-else-if="status === 'failed'" class="text-destructive">
        {{ t('account.search.failed') }}
      </span>
      <span v-else-if="status === 'tooMany'" class="text-destructive">
        {{ t('account.search.tooMany') }}
      </span>
    </span>
  </span>
</template>
