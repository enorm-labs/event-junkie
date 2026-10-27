<script lang="ts" setup>
/**
 * What the account follows (#4080), grouped by kind and sorted by name, with the bell and Follow on each row.
 * An unfollow keeps its row, now offering Follow again, so a slip can be undone; the page drops it once the visitor
 * leaves the tab. More than ten follows bring a search box over the names.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import BaseInput from '@/components/BaseInput.vue'
import FollowBell from '@/components/FollowBell.vue'
import FollowButton from '@/components/FollowButton.vue'
import SectionLabel from '@/components/SectionLabel.vue'
import type { FollowKind, Notify } from '@/api/account'
import { useLocalePath } from '@/composables/useLocalePath'

/** One followed entity, with the fields the generated type leaves optional checked. */
export interface Followed {
  kind: FollowKind
  slug: string
  name: string
  notify: Notify
  /** False after an unfollow on this visit to the tab, while the row stays. */
  following: boolean
}

const props = defineProps<{ follows: Followed[]; bells: boolean }>()
const emit = defineEmits<{ change: [item: Followed, following: boolean] }>()

const { t, locale } = useI18n()
const localePath = useLocalePath()

const KINDS: { kind: FollowKind; route: string; heading: string }[] = [
  { kind: 'artist', route: 'artists', heading: 'account.follow.followed.artists' },
  { kind: 'venue', route: 'venues', heading: 'account.follow.followed.venues' },
  { kind: 'promoter', route: 'promoters', heading: 'account.follow.followed.promoters' },
]
const SEARCH_ABOVE = 10

const query = ref('')
const searchable = computed(() => props.follows.length > SEARCH_ABOVE)
const needle = computed(() => query.value.trim().toLocaleLowerCase(locale.value))

const groups = computed(() =>
  KINDS.map(({ kind, route, heading }) => {
    const all = props.follows.filter((item) => item.kind === kind)
    const rows = all
      .filter((item) => item.name.toLocaleLowerCase(locale.value).includes(needle.value))
      .sort((a, b) => a.name.localeCompare(b.name, locale.value, { sensitivity: 'base' }))
    return { kind, route, heading, rows, count: all.filter((item) => item.following).length }
  }).filter((group) => group.rows.length),
)
</script>

<template>
  <div class="space-y-6">
    <p v-if="!follows.length" class="text-body text-muted-foreground">
      {{ t('account.follow.followed.empty') }}
    </p>

    <BaseInput
      v-if="searchable"
      v-model="query"
      :aria-label="t('account.follow.followed.search')"
      class="w-full sm:max-w-sm"
      :placeholder="t('account.follow.followed.search')"
      type="search"
    />

    <p
      v-if="follows.length && !groups.length"
      class="text-body text-muted-foreground"
      role="status"
    >
      {{ t('account.follow.followed.noMatch', { query: query.trim() }) }}
    </p>

    <section v-for="group in groups" :key="group.kind" class="space-y-4">
      <SectionLabel as="h2">{{ t(group.heading, { count: group.count }) }}</SectionLabel>
      <ul class="divide-y divide-border border-y border-border">
        <li
          v-for="item in group.rows"
          :key="item.slug"
          class="flex items-center justify-between gap-4 py-3"
        >
          <RouterLink
            class="min-w-0 text-body font-medium break-words hover:underline"
            :to="localePath(`/${group.route}/${item.slug}`)"
          >
            {{ item.name }}
          </RouterLink>
          <div class="flex shrink-0 items-center gap-1">
            <FollowBell
              v-if="bells && item.following"
              :initially="item.notify"
              :kind="item.kind"
              :name="item.name"
              :slug="item.slug"
            />
            <FollowButton
              no-hint
              :initially-following="true"
              :kind="item.kind"
              :slug="item.slug"
              @change="emit('change', item, $event)"
            />
          </div>
        </li>
      </ul>
    </section>
  </div>
</template>
