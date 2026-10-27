<script lang="ts" setup>
/**
 * The account page's "Notifications" section (#3894, ADR-055): push on or off for this browser. Each browser
 * has its own switch, because a push subscription belongs to one browser. What a push is for is the bell on
 * each follow, on the Following page. Every bell starts off, so the first "on" offers to ring them all at once,
 * and the visitor chooses (ADR-055 rule 4).
 */
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import SectionLabel from '@/components/SectionLabel.vue'
import { Button } from '@/components/ui/button'
import { fetchFollows, prepareCsrf, setAllNotify } from '@/api/account'
import { ApiError } from '@/api/client'
import { useLocalePath } from '@/composables/useLocalePath'
import { usePush } from '@/composables/usePush'

const { t } = useI18n()
const localePath = useLocalePath()
const { state, busy, load, turnOn, turnOff } = usePush()
const problem = ref('')
/** Follows with the bell off, offered once right after notifications go on; null when there is no offer. */
const offer = ref<number | null>(null)
const rung = ref<number | null>(null)

async function enable() {
  await turnOn()
  if (state.value !== 'on') return
  try {
    const silent = (await fetchFollows()).filter((follow) => follow.notify !== 'instant').length
    offer.value = silent > 0 ? silent : null
  } catch {
    // Only an offer: without the list, the bells stay as they are.
  }
}

async function ringAll() {
  offer.value = null
  await prepareCsrf()
  rung.value = await setAllNotify('instant')
}

async function run(action: () => Promise<void>) {
  problem.value = ''
  try {
    await action()
  } catch (e) {
    problem.value =
      e instanceof ApiError && e.status === 409
        ? t('account.push.tooMany')
        : t('account.push.failed')
  }
}

onMounted(() => run(load))
</script>

<template>
  <section class="space-y-3">
    <SectionLabel as="h2">{{ t('account.push.title') }}</SectionLabel>
    <p class="text-body text-muted-foreground">
      {{ t('account.push.intro') }}
      <RouterLink
        class="underline underline-offset-2"
        :to="{ path: localePath('/following'), query: { tab: 'follows' } }"
      >
        {{ t('account.push.following') }}
      </RouterLink>
    </p>
    <p v-if="state === 'loading'" class="text-body text-muted-foreground">
      {{ t('common.states.loading') }}
    </p>
    <p v-else-if="state === 'homeScreen'" class="text-body">{{ t('account.push.homeScreen') }}</p>
    <p v-else-if="state === 'unsupported'" class="text-body">{{ t('account.push.unsupported') }}</p>
    <p v-else-if="state === 'unavailable'" class="text-body">{{ t('account.push.unavailable') }}</p>
    <p v-else-if="state === 'denied'" class="text-body">{{ t('account.push.denied') }}</p>
    <template v-else>
      <p class="text-body" role="status">
        {{ state === 'on' ? t('account.push.on') : t('account.push.off') }}
      </p>
      <Button
        :disabled="busy"
        data-testid="push-toggle"
        variant="outline"
        @click="run(state === 'on' ? turnOff : enable)"
      >
        {{ state === 'on' ? t('account.push.turnOff') : t('account.push.turnOn') }}
      </Button>
    </template>
    <div v-if="offer !== null" class="space-y-3 border border-border p-4" role="group">
      <p class="text-body">{{ t('account.push.ringAll', { count: offer }) }}</p>
      <div class="flex flex-wrap gap-3">
        <Button data-testid="ring-all" @click="run(ringAll)">{{
          t('account.push.ringAllYes')
        }}</Button>
        <Button variant="outline" @click="offer = null">{{ t('account.push.ringAllNo') }}</Button>
      </div>
    </div>
    <p v-if="rung !== null" class="text-body" role="status">
      {{ t('account.push.ringAllDone', { count: rung }) }}
    </p>
    <p v-if="problem" class="text-meta text-destructive" role="alert">{{ problem }}</p>
  </section>
</template>
