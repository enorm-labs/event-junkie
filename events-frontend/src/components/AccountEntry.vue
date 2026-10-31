<script lang="ts" setup>
/**
 * The header's way into an account (#3889, #3924). Signed out, a link to sign-in. Signed in, a menu
 * with the address, My calendar, the account page and sign-out; Following is a section link in
 * App.vue. Built as `LocaleMenu` is: an icon button and a popover of plain links, so each entry stays
 * an address. One icon at every width, so the phone has the same menu and the header keeps its width.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { UserRound, UserRoundCheck } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { prepareCsrf, signOut } from '@/api/account'
import { useLocalePath } from '@/composables/useLocalePath'
import { setSession, useSession } from '@/composables/useSession'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const localePath = useLocalePath()
const { account } = useSession()

const open = ref(false)
const busy = ref(false)
const problem = ref('')

const menuLabel = computed(() => t('common.nav.accountMenu'))
const signInLabel = computed(() => t('common.nav.signIn'))

/** One row of the menu, as `LocaleMenu` draws its links. */
const ROW =
  'block rounded-md px-2 py-1.5 text-body hover:bg-accent hover:text-accent-foreground focus-visible:bg-accent focus-visible:outline-hidden disabled:opacity-50'

/** The pages that show the account itself; signed out, they have nothing left to show. */
const ACCOUNT_PAGE = /^\/(de|en)\/(following|my-calendar|notifications|account)\/?$/

async function leave() {
  if (busy.value) return
  busy.value = true
  problem.value = ''
  try {
    await prepareCsrf()
    await signOut()
    setSession(null)
    open.value = false
    if (ACCOUNT_PAGE.test(route.path)) await router.push(localePath('/'))
  } catch {
    problem.value = t('account.page.failed')
  } finally {
    busy.value = false
  }
}

const MENU = [
  { path: '/my-calendar', label: 'pageTitle.myCalendar' },
  { path: '/account', label: 'pageTitle.account' },
]
</script>

<template>
  <!-- `title` is the hover tooltip only; `aria-label` wins for the accessible name. -->
  <Button
    v-if="!account"
    :aria-label="signInLabel"
    :title="signInLabel"
    as-child
    size="icon"
    variant="outline"
  >
    <RouterLink :to="localePath('/sign-in')">
      <UserRound />
    </RouterLink>
  </Button>
  <Popover v-else v-model:open="open">
    <PopoverTrigger as-child>
      <Button :aria-label="menuLabel" :title="menuLabel" size="icon" variant="outline">
        <UserRoundCheck />
      </Button>
    </PopoverTrigger>
    <PopoverContent align="end" class="w-auto max-w-xs min-w-48">
      <div class="space-y-2">
        <p class="truncate px-2 text-meta text-muted-foreground">
          {{ t('account.page.signedInAs', { email: account.email }) }}
        </p>
        <ul :aria-label="menuLabel">
          <li v-for="link in MENU" :key="link.path">
            <RouterLink :to="localePath(link.path)" :class="ROW" @click="open = false">
              {{ t(link.label) }}
            </RouterLink>
          </li>
          <!-- A row like the links, so the rows line up; a button, because it does not go anywhere. -->
          <li>
            <button
              :class="[ROW, 'w-full text-left']"
              :disabled="busy"
              type="button"
              @click="leave"
            >
              {{ t('account.page.signOut') }}
            </button>
          </li>
        </ul>
        <p v-if="problem" class="px-2 text-meta text-destructive" role="alert">{{ problem }}</p>
      </div>
    </PopoverContent>
  </Popover>
</template>
