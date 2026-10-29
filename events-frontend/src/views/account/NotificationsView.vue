<script lang="ts" setup>
/**
 * Every notification of the account (#4169), under day headings, 50 at a time; the bell lists only
 * the newest 20. All or only the unread ones (#4170), kept in the URL so a reload keeps it. Private,
 * so never indexed. Signed out, it sends the visitor to sign-in and back.
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import ListPage from '@/components/ListPage.vue'
import NotificationEntry from '@/components/NotificationEntry.vue'
import SectionLabel from '@/components/SectionLabel.vue'
import { Button } from '@/components/ui/button'
import {
  fetchNotificationPage,
  markAllNotificationsRead,
  markNotificationRead,
  prepareCsrf,
} from '@/api/account'
import { ApiError, describeError } from '@/api/client'
import type { AccountNotification } from '@/api/types'
import { useLocalePath } from '@/composables/useLocalePath'
import { useSession } from '@/composables/useSession'
import { berlinDateOf, formatLongDate, todayIso, yesterdayIso } from '@/lib/format'
import { NOTIFICATIONS_READ } from '@/lib/notifications'

const PAGE = 50

const { t, locale } = useI18n()
const route = useRoute()
const router = useRouter()
const localePath = useLocalePath()
const { account, ready } = useSession()

const unreadOnly = computed(() => route.query.unread === '1')
const entries = ref<AccountNotification[] | null>(null)
const next = ref<string | null>(null)
const loadingMore = ref(false)
const problem = ref('')
let csrf: Promise<void> | null = null

/** The entries by the Berlin day they arrived, newest day first, as the list is already sorted. */
const days = computed(() => {
  const groups: { day: string; label: string; entries: AccountNotification[] }[] = []
  const today = todayIso()
  const yesterday = yesterdayIso()
  for (const entry of entries.value ?? []) {
    const day = entry.createdAt ? berlinDateOf(entry.createdAt) : today
    let group = groups[groups.length - 1]
    if (group?.day !== day) {
      const label =
        day === today
          ? t('account.notifications.page.today')
          : day === yesterday
            ? t('account.notifications.page.yesterday')
            : formatLongDate(day, locale.value)
      group = { day, label, entries: [] }
      groups.push(group)
    }
    group.entries.push(entry)
  }
  return groups
})

const anyUnread = computed(() => entries.value?.some((entry) => !entry.readAt) ?? false)

async function load() {
  problem.value = ''
  entries.value = null
  next.value = null
  try {
    const page = await fetchNotificationPage({ limit: PAGE, unread: unreadOnly.value })
    entries.value = page.entries
    next.value = page.next
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) await toSignIn()
    else problem.value = describeError(e, 'errors.subject.notifications')
  }
}

async function more() {
  if (!next.value || loadingMore.value) return
  loadingMore.value = true
  try {
    const page = await fetchNotificationPage({
      limit: PAGE,
      before: next.value,
      unread: unreadOnly.value,
    })
    entries.value = [...(entries.value ?? []), ...page.entries]
    next.value = page.next
  } catch (e) {
    problem.value = describeError(e, 'errors.subject.notifications')
  } finally {
    loadingMore.value = false
  }
}

/** Marks on the server, then tells the bell to count again. */
async function write(call: () => Promise<void>) {
  csrf ??= prepareCsrf()
  await csrf
  await call()
  window.dispatchEvent(new Event(NOTIFICATIONS_READ))
}

/** Opening an entry reads it, as in the bell: the link navigates at once, the mark follows. */
function opened(entry: AccountNotification) {
  if (entry.readAt || !entry.id) return
  entry.readAt = new Date().toISOString()
  const id = entry.id
  write(() => markNotificationRead(id)).catch(() => undefined)
}

async function readAll() {
  try {
    await write(markAllNotificationsRead)
    const now = new Date().toISOString()
    entries.value?.forEach((entry) => (entry.readAt ??= now))
  } catch {
    problem.value = t('account.notifications.failed')
  }
}

function setUnread(only: boolean) {
  if (only === unreadOnly.value) return
  void router.replace({ query: { ...route.query, unread: only ? '1' : undefined } })
}

function toSignIn() {
  return router.replace({
    path: localePath('/sign-in'),
    query: { next: route.fullPath },
  })
}

watch(unreadOnly, () => {
  if (account.value) void load()
})

onMounted(async () => {
  await ready()
  if (!account.value) await toSignIn()
  else await load()
})
</script>

<template>
  <ListPage :title="t('pageTitle.notifications')">
    <template #header>
      <p class="text-body text-muted-foreground">{{ t('account.notifications.page.intro') }}</p>
      <div class="flex flex-wrap items-center justify-between gap-2">
        <div :aria-label="t('account.notifications.page.filter')" class="flex gap-2" role="group">
          <Button
            :aria-pressed="!unreadOnly"
            :variant="unreadOnly ? 'outline' : 'default'"
            size="sm"
            type="button"
            @click="setUnread(false)"
          >
            {{ t('account.notifications.page.all') }}
          </Button>
          <Button
            :aria-pressed="unreadOnly"
            :variant="unreadOnly ? 'default' : 'outline'"
            size="sm"
            type="button"
            @click="setUnread(true)"
          >
            {{ t('account.notifications.page.unread') }}
          </Button>
        </div>
        <Button v-if="anyUnread" size="sm" type="button" variant="ghost" @click="readAll">
          {{ t('account.notifications.markAllRead') }}
        </Button>
      </div>
    </template>

    <p v-if="problem" class="text-body text-destructive" role="alert">{{ problem }}</p>

    <p v-if="entries === null && !problem" class="text-body text-muted-foreground">
      {{ t('common.states.loading') }}
    </p>

    <p
      v-else-if="entries && entries.length === 0"
      class="text-body text-muted-foreground"
      data-testid="notifications-empty"
    >
      {{
        unreadOnly ? t('account.notifications.page.noneUnread') : t('account.notifications.empty')
      }}
    </p>

    <template v-else-if="entries">
      <section v-for="group in days" :key="group.day" class="space-y-2">
        <SectionLabel as="h2">{{ group.label }}</SectionLabel>
        <ul class="max-w-2xl">
          <li v-for="entry in group.entries" :key="entry.id">
            <NotificationEntry
              :entry="entry"
              class="block rounded-md px-2 py-2 hover:bg-accent hover:text-accent-foreground focus-visible:bg-accent focus-visible:outline-hidden"
              wrap
              @click="opened(entry)"
            />
          </li>
        </ul>
      </section>
      <Button v-if="next" :disabled="loadingMore" variant="outline" @click="more">
        {{ t('common.actions.showMore') }}
      </Button>
    </template>
  </ListPage>
</template>
