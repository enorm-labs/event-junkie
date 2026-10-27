<script lang="ts" setup>
/**
 * The bell on one follow (#3894): on, every browser with notifications on gets a push when a new date of it
 * is stored. It changes at once and goes back with a message if the BFF refuses, as FollowButton does.
 */
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Bell, BellOff } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { type FollowKind, type Notify, prepareCsrf, setNotify } from '@/api/account'

const props = defineProps<{
  kind: FollowKind
  slug: string
  name: string
  initially: Notify
}>()

const { t } = useI18n()
const on = ref(props.initially === 'instant')
const busy = ref(false)
const problem = ref('')

async function toggle() {
  if (busy.value) return
  const was = on.value
  busy.value = true
  problem.value = ''
  on.value = !was
  try {
    await prepareCsrf()
    await setNotify(props.kind, props.slug, was ? 'off' : 'instant')
  } catch {
    on.value = was
    problem.value = t('account.push.failed')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="flex items-center gap-2">
    <p v-if="problem" class="text-meta text-destructive" role="alert">{{ problem }}</p>
    <Button
      :aria-label="t('account.push.bell', { name })"
      :aria-pressed="on"
      :disabled="busy"
      :title="on ? t('account.push.bellOn') : t('account.push.bellOff')"
      size="icon"
      variant="ghost"
      @click="toggle"
    >
      <Bell v-if="on" aria-hidden="true" />
      <BellOff v-else aria-hidden="true" class="text-muted-foreground" />
    </Button>
  </div>
</template>
