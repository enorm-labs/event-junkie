<script setup lang="ts">
/**
 * The events list's calendar subscription as one menu (#2887): Google Calendar, the system's calendar
 * app, Outlook.com, and the address to copy for any other app.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { CalendarPlus, ChevronDown, ExternalLink, Link as LinkIcon } from '@lucide/vue'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { calendarSubscription } from '@/lib/calendarSubscription'
import { copyLink } from '@/lib/share'

const props = defineProps<{
  /** The site-relative calendar path, from `calendarPath`. */
  path: string
  /** The calendar's name, for Outlook.com. */
  name: string
  /** What the subscription holds, as the trigger's tooltip. */
  description: string
}>()

const { t } = useI18n()

const links = computed(() => calendarSubscription(window.location.origin, props.path, props.name))

const status = ref('')

async function copy(): Promise<void> {
  status.value =
    (await copyLink(links.value.https)) === 'copied'
      ? t('events.calendar.copied')
      : t('events.calendar.copyFailed', { url: links.value.https })
}
</script>

<template>
  <span class="inline-flex flex-wrap items-center">
    <DropdownMenu>
      <DropdownMenuTrigger as-child>
        <button
          :title="description"
          class="inline-flex items-center gap-1 text-primary hover:underline"
          data-testid="calendar-subscribe"
          type="button"
        >
          <CalendarPlus aria-hidden="true" class="size-4" />
          <span class="sm:hidden">{{ t('events.calendar.triggerShort') }}</span>
          <span class="hidden sm:inline">{{ t('events.calendar.trigger') }}</span>
          <ChevronDown aria-hidden="true" class="size-4" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start">
        <DropdownMenuItem as-child>
          <a :href="links.google" rel="noopener noreferrer" target="_blank">
            <ExternalLink aria-hidden="true" />
            {{ t('events.calendar.google') }}
          </a>
        </DropdownMenuItem>
        <DropdownMenuItem as-child>
          <a :href="links.webcal">
            <CalendarPlus aria-hidden="true" />
            {{ t('events.calendar.app') }}
          </a>
        </DropdownMenuItem>
        <DropdownMenuItem as-child>
          <a :href="links.outlook" rel="noopener noreferrer" target="_blank">
            <ExternalLink aria-hidden="true" />
            {{ t('events.calendar.outlook') }}
          </a>
        </DropdownMenuItem>
        <DropdownMenuItem @select="copy">
          <LinkIcon aria-hidden="true" />
          {{ t('events.calendar.copy') }}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
    <span :class="status && 'ms-2'" class="text-muted-foreground" role="status">{{ status }}</span>
  </span>
</template>
