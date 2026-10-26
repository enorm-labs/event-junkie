<script setup lang="ts">
/**
 * "Interested" and "Going" on the event page (#3892). Private: only the account sees them. A press
 * on the chosen one withdraws it. Signed out, both lead to sign-in and back to this event.
 */
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { CalendarCheck, Star } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import type { RsvpStatus } from '@/api/account'
import { useLocalePath } from '@/composables/useLocalePath'
import { useRsvps } from '@/composables/useRsvps'
import { useSession } from '@/composables/useSession'

const props = defineProps<{ slug: string }>()

const { t } = useI18n()
const localePath = useLocalePath()
const { account } = useSession()
const { statuses, choose } = useRsvps()

const OPTIONS: { status: RsvpStatus; icon: typeof Star }[] = [
  { status: 'interested', icon: Star },
  { status: 'going', icon: CalendarCheck },
]

const problem = ref('')

async function press(status: RsvpStatus) {
  problem.value = ''
  try {
    await choose(props.slug, statuses.value[props.slug] === status ? null : status)
  } catch {
    problem.value = t('account.rsvp.failed')
  }
}
</script>

<template>
  <div :aria-label="t('account.rsvp.label')" class="flex flex-wrap items-center gap-2" role="group">
    <template v-if="account">
      <Button
        v-for="option in OPTIONS"
        :key="option.status"
        :aria-pressed="statuses[slug] === option.status"
        :variant="statuses[slug] === option.status ? 'default' : 'outline'"
        :data-testid="`rsvp-${option.status}`"
        @click="press(option.status)"
      >
        <component :is="option.icon" aria-hidden="true" />
        {{ t(`account.rsvp.${option.status}`) }}
      </Button>
    </template>
    <template v-else>
      <Button v-for="option in OPTIONS" :key="option.status" as-child variant="outline">
        <RouterLink
          :title="t('account.rsvp.signIn')"
          :to="{
            path: localePath('/sign-in'),
            query: { next: localePath(`/events/${slug}`) },
          }"
        >
          <component :is="option.icon" aria-hidden="true" />
          {{ t(`account.rsvp.${option.status}`) }}
        </RouterLink>
      </Button>
    </template>
    <span class="text-body text-destructive" role="status">{{ problem }}</span>
  </div>
</template>
