<script lang="ts" setup>
/**
 * The header's bell, signed in only (#4078): new dates of what the account follows, unread until
 * opened. The count is asked on each page load and route change, and when the tab comes back into
 * view, and when the notifications page has marked something read. No polling. Built as
 * `AccountEntry` is: an icon button and a popover of plain links, the last one to the page (#4169).
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute } from 'vue-router'
import { Bell } from '@lucide/vue'
import {
  fetchNotifications,
  fetchUnreadCount,
  markAllNotificationsRead,
  markNotificationRead,
  prepareCsrf,
} from '@/api/account'
import type { AccountNotification } from '@/api/types'
import NotificationEntry from '@/components/NotificationEntry.vue'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { useLocalePath } from '@/composables/useLocalePath'
import { NOTIFICATIONS_READ } from '@/lib/notifications'

const { t } = useI18n()
const route = useRoute()
const localePath = useLocalePath()

const open = ref(false)
const count = ref(0)
const entries = ref<AccountNotification[] | null>(null)
const problem = ref('')
let csrfReady = false
// Opening an entry navigates at once, and the route change asks for the count. It waits for the
// mark, or the server would still count the entry as unread.
let writing: Promise<unknown> = Promise.resolve()

const badge = computed(() => (count.value > 9 ? '9+' : String(count.value)))
const label = computed(() =>
  count.value > 0
    ? t('account.notifications.labelUnread', { count: count.value })
    : t('account.notifications.label'),
)

/** One row of the menu, as `AccountEntry` draws its links. */
const ROW =
  'block rounded-md px-2 py-1.5 hover:bg-accent hover:text-accent-foreground focus-visible:bg-accent focus-visible:outline-hidden'

async function refresh() {
  try {
    await writing
    count.value = await fetchUnreadCount()
  } catch {
    // A missed count leaves the last one; the next page load asks again.
  }
}

async function load() {
  problem.value = ''
  try {
    entries.value = await fetchNotifications()
  } catch {
    problem.value = t('account.notifications.failed')
  }
}

function write(call: () => Promise<void>): Promise<void> {
  const done = (async () => {
    if (!csrfReady) {
      await prepareCsrf()
      csrfReady = true
    }
    await call()
  })()
  writing = done.catch(() => undefined)
  return done
}

/** Opening an entry reads it. The link navigates at once; the mark follows in the background. */
function opened(entry: AccountNotification) {
  open.value = false
  if (entry.readAt || !entry.id) return
  entry.readAt = new Date().toISOString()
  count.value = Math.max(0, count.value - 1)
  const id = entry.id
  write(() => markNotificationRead(id)).catch(refresh)
}

async function readAll() {
  try {
    await write(markAllNotificationsRead)
    const now = new Date().toISOString()
    entries.value?.forEach((entry) => (entry.readAt ??= now))
    count.value = 0
  } catch {
    problem.value = t('account.notifications.failed')
  }
}

const onVisible = () => {
  if (document.visibilityState === 'visible') refresh()
}

watch(open, (isOpen) => {
  if (isOpen) load()
})
watch(() => route.fullPath, refresh)
onMounted(() => {
  refresh()
  document.addEventListener('visibilitychange', onVisible)
  window.addEventListener(NOTIFICATIONS_READ, refresh)
})
onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisible)
  window.removeEventListener(NOTIFICATIONS_READ, refresh)
})
</script>

<template>
  <Popover v-model:open="open">
    <PopoverTrigger as-child>
      <!-- `title` is the hover tooltip only; `aria-label` carries the count for a screen reader. -->
      <Button
        :aria-label="label"
        :title="label"
        class="relative"
        data-testid="notification-bell"
        size="icon"
        variant="outline"
      >
        <Bell />
        <span
          v-if="count > 0"
          aria-hidden="true"
          class="absolute -top-1.5 -right-1.5 min-w-4 rounded-full bg-primary px-1 text-meta text-primary-foreground"
          data-testid="notification-badge"
          >{{ badge }}</span
        >
      </Button>
    </PopoverTrigger>
    <PopoverContent align="end" class="w-80">
      <div class="space-y-2">
        <div class="flex items-center justify-between gap-2 px-2">
          <p class="text-meta text-muted-foreground">{{ t('account.notifications.title') }}</p>
          <Button v-if="count > 0" size="sm" type="button" variant="ghost" @click="readAll">
            {{ t('account.notifications.markAllRead') }}
          </Button>
        </div>
        <p v-if="problem" class="px-2 text-meta text-destructive" role="alert">{{ problem }}</p>
        <p
          v-else-if="entries && entries.length === 0"
          class="px-2 py-1.5 text-body text-muted-foreground"
        >
          {{ t('account.notifications.empty') }}
        </p>
        <ul
          v-else-if="entries"
          :aria-label="t('account.notifications.label')"
          class="max-h-96 overflow-y-auto"
        >
          <li v-for="entry in entries" :key="entry.id">
            <NotificationEntry :class="ROW" :entry="entry" @click="opened(entry)" />
          </li>
        </ul>
        <div class="border-t pt-2">
          <RouterLink
            :class="ROW"
            :to="localePath('/notifications')"
            class="text-body"
            data-testid="notification-show-all"
            @click="open = false"
          >
            {{ t('account.notifications.showAll') }}
          </RouterLink>
        </div>
      </div>
    </PopoverContent>
  </Popover>
</template>
