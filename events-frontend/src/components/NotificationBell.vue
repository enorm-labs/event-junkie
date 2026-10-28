<script lang="ts" setup>
/**
 * The header's bell, signed in only (#4078): new dates of what the account follows, unread until
 * opened. The count is asked on each page load and route change, and when the tab comes back into
 * view. No polling. Built as `AccountEntry` is: an icon button and a popover of plain links.
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
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { useLocalePath } from '@/composables/useLocalePath'
import { formatShortDate } from '@/lib/format'

const { t, locale } = useI18n()
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

function line(entry: AccountNotification) {
  return t(`account.notifications.${entry.aboutKind}`, { name: entry.aboutName })
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
})
onBeforeUnmount(() => document.removeEventListener('visibilitychange', onVisible))
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
        <ul v-else-if="entries" :aria-label="t('account.notifications.label')">
          <li v-for="entry in entries" :key="entry.id">
            <RouterLink
              :class="ROW"
              :to="localePath(`/events/${entry.event}`)"
              data-testid="notification"
              @click="opened(entry)"
            >
              <span class="flex items-start gap-2">
                <!-- The dot marks unread; the sr-only word says it, so colour is not the only carrier. -->
                <span
                  :class="entry.readAt ? 'invisible' : 'bg-primary'"
                  aria-hidden="true"
                  class="mt-1.5 size-2 shrink-0 rounded-full"
                />
                <span class="min-w-0">
                  <span v-if="!entry.readAt" class="sr-only"
                    >{{ t('account.notifications.unread') }}:
                  </span>
                  <span
                    :class="entry.readAt ? 'font-normal' : 'font-medium'"
                    class="block truncate text-body"
                  >
                    {{ line(entry) }}
                  </span>
                  <span class="block truncate text-meta text-muted-foreground">
                    {{ formatShortDate(entry.date, locale) }} · {{ entry.title }}
                  </span>
                </span>
              </span>
            </RouterLink>
          </li>
        </ul>
      </div>
    </PopoverContent>
  </Popover>
</template>
