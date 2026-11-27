<script lang="ts" setup>
/**
 * One notification as a link to its event (#4078), in the bell's menu and on the notifications page
 * (#4169). A report note (#5308) links to the reported page, and is plain text once that page is
 * gone. The parent handles the click, which marks the entry read. `wrap` lets a long title wrap
 * where there is room; the menu truncates it.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import type { AccountNotification } from '@/api/types'
import { useLocalePath } from '@/composables/useLocalePath'
import { formatShortDate } from '@/lib/format'

const props = defineProps<{ entry: AccountNotification; wrap?: boolean }>()

const { t, locale } = useI18n()
const localePath = useLocalePath()

const report = computed(() => props.entry.kind === 'report')

const to = computed(() => {
  const entry = props.entry
  if (!report.value) return localePath(`/events/${entry.event}`)
  return entry.subjectSlug ? localePath(`/${entry.subjectKind}s/${entry.subjectSlug}`) : null
})

function line() {
  const entry = props.entry
  if (report.value) return t(`account.notifications.report.${entry.outcome}`, { name: entry.title })
  if (entry.kind === 'change') return t(`account.notifications.change.${entry.change}`)
  return t(`account.notifications.${entry.aboutKind}`, { name: entry.aboutName })
}

function detail() {
  const entry = props.entry
  if (report.value) return entry.reply ?? t('account.notifications.report.thanks')
  return `${formatShortDate(entry.date, locale.value)} · ${entry.title}`
}
</script>

<template>
  <component :is="to ? RouterLink : 'div'" :to="to ?? undefined" data-testid="notification">
    <span class="flex items-start gap-2">
      <!-- The dot marks unread; the sr-only word says it, so colour is not the only carrier. -->
      <span
        :class="entry.readAt ? 'invisible' : 'bg-primary'"
        aria-hidden="true"
        class="mt-1.5 size-2 shrink-0 rounded-full"
      />
      <span class="min-w-0">
        <span v-if="!entry.readAt" class="sr-only">{{ t('account.notifications.unread') }}: </span>
        <span
          :class="[entry.readAt ? 'font-normal' : 'font-medium', wrap ? 'break-words' : 'truncate']"
          class="block text-body"
        >
          {{ line() }}
        </span>
        <span
          :class="wrap ? 'break-words' : 'truncate'"
          class="block text-meta text-muted-foreground"
        >
          {{ detail() }}
        </span>
      </span>
    </span>
  </component>
</template>
