import {
  createRouter,
  createWebHistory,
  type RouteLocationNormalized,
  RouterView,
} from 'vue-router'
import { h } from 'vue'
import { applyPageMeta } from '../composables/usePageMeta'
import { staticPageMeta } from '../lib/pageMeta'
import {
  isLocale,
  type Locale,
  LOCALES,
  rememberLocale,
  resolveLocale,
  stripLocale,
} from '../i18n/locales'
import { updateSeoTags } from '../lib/seoTags'
import { i18n, setI18nLocale } from '../i18n'
import { localisedView } from '../views/localisedView'
import HomeView from '../views/HomeView.vue'

declare module 'vue-router' {
  interface RouteMeta {
    // Per-view page title and description, as message keys. Detail views set both from loaded
    // data instead (see usePageMeta), so theirs are intentionally unset.
    titleKey?: string
    descriptionKey?: string
  }
}

/**
 * Pass-through parent for the `/:locale` segment. It renders only its child, so the locale lives
 * in the URL without adding a layout level — the app shell is still App.vue.
 */
const LocaleShell = { render: () => h(RouterView) }

/** The locale of the route being navigated to. Always present on `/:locale/*` routes. */
export function localeOf(route: RouteLocationNormalized): Locale {
  const value = route.params.locale
  return isLocale(value) ? value : resolveLocale()
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      // Path-prefixed locales rather than a stored preference: a locale kept only in storage makes
      // every shared link a coin flip for whoever receives it, and is invisible to crawlers.
      // See docs/adr/ADR-013_LOCALISATION.md §Decision 2.
      // The matcher is built from LOCALES so a locale can never be routable without being
      // published — a `/de` route that renders English would be worse than no `/de` at all.
      path: `/:locale(${LOCALES.join('|')})`,
      component: LocaleShell,
      children: [
        {
          path: '',
          name: 'home',
          meta: { descriptionKey: 'pageDescription.home' },
          component: HomeView,
        },
        {
          path: 'calendar',
          name: 'calendar',
          meta: { titleKey: 'pageTitle.calendar', descriptionKey: 'pageDescription.calendar' },
          // Lazy-loaded so FullCalendar's weight does not affect first paint elsewhere (see ADR-011).
          component: () => import('../views/CalendarView.vue'),
        },
        {
          path: 'events',
          name: 'events',
          meta: { titleKey: 'pageTitle.events', descriptionKey: 'pageDescription.events' },
          component: () => import('../views/EventsView.vue'),
        },
        {
          path: 'events/:slug',
          name: 'event',
          component: () => import('../views/EventDetailView.vue'),
        },
        {
          path: 'venues',
          name: 'venues',
          meta: { titleKey: 'pageTitle.venues', descriptionKey: 'pageDescription.venues' },
          component: () => import('../views/VenuesView.vue'),
        },
        {
          path: 'venues/:slug',
          name: 'venue',
          component: () => import('../views/VenueDetailView.vue'),
        },
        {
          path: 'artists/:slug',
          name: 'artist',
          component: () => import('../views/ArtistDetailView.vue'),
        },
        {
          path: 'promoters/:slug',
          name: 'promoter',
          component: () => import('../views/PromoterDetailView.vue'),
        },
        // The five long-form pages have one component per language rather than one component
        // reading translated prose — see views/localisedView.ts for why, and note that this is the
        // exception to the rule that user-facing text lives in the message catalogue.
        {
          path: 'about',
          name: 'about',
          meta: { titleKey: 'pageTitle.about', descriptionKey: 'pageDescription.about' },
          // route level code-splitting
          // this generates a separate chunk (About.[hash].js) for this route
          // which is lazy-loaded when the route is visited.
          component: localisedView({
            en: () => import('../views/AboutView.en.vue'),
            de: () => import('../views/AboutView.de.vue'),
          }),
        },
        // Legal pages, nested under /legal/* so later additions (accessibility statement, data
        // sources) have an obvious home. Lazy-loaded like every other non-home route: they are read
        // rarely and should not weigh on first paint.
        {
          path: 'legal/imprint',
          name: 'imprint',
          meta: { titleKey: 'pageTitle.imprint', descriptionKey: 'pageDescription.imprint' },
          component: localisedView({
            en: () => import('../views/legal/ImprintView.en.vue'),
            de: () => import('../views/legal/ImprintView.de.vue'),
          }),
        },
        {
          path: 'legal/privacy',
          name: 'privacy',
          meta: { titleKey: 'pageTitle.privacy', descriptionKey: 'pageDescription.privacy' },
          component: localisedView({
            en: () => import('../views/legal/PrivacyView.en.vue'),
            de: () => import('../views/legal/PrivacyView.de.vue'),
          }),
        },
        {
          path: 'legal/notices',
          name: 'notices',
          meta: { titleKey: 'pageTitle.notices', descriptionKey: 'pageDescription.notices' },
          component: localisedView({
            en: () => import('../views/legal/NoticesView.en.vue'),
            de: () => import('../views/legal/NoticesView.de.vue'),
          }),
        },
        // Venue-facing rather than visitor-facing: it publishes the opt-out route that
        // docs/SCRAPING_POSITION.md §5 defines, and a commitment an operator cannot find is not
        // one. Under /legal/* because that is where the site's promises live, not in Project.
        {
          path: 'legal/for-venues',
          name: 'forVenues',
          meta: { titleKey: 'pageTitle.forVenues', descriptionKey: 'pageDescription.forVenues' },
          component: localisedView({
            en: () => import('../views/legal/ForVenuesView.en.vue'),
            de: () => import('../views/legal/ForVenuesView.de.vue'),
          }),
        },
      ],
    },
    {
      // Anything without a locale prefix — including `/` — gets one and is redirected.
      //
      // The guard against `already` matters: without it, a path that is prefixed but matches no
      // route (`/en/nonsense`) would fall through to here and be prefixed again, producing
      // `/en/en/nonsense` and then looping. Sending it to that locale's home is a deliberate
      // choice for now; a real 404 view is tracked separately.
      path: '/:pathMatch(.*)*',
      redirect: (to) => {
        const [first] = to.path.split('/').filter(Boolean)
        const already = isLocale(first)
        const locale = already ? first : resolveLocale()
        // `to.path` is `/` for the bare root, which would make `/en/` — a second URL for the same
        // page as `/en`, which is what every in-app link produces. Normalise to the shorter form.
        const rest = to.path === '/' ? '' : to.path
        return {
          path: already ? `/${locale}` : `/${locale}${rest}`,
          query: to.query,
          hash: to.hash,
        }
      },
    },
  ],
  // Legal pages are linked from the footer, so they are always reached from the bottom of a
  // scrolled page; without this the browser keeps the old offset and the imprint opens mid-document.
  scrollBehavior(to) {
    return to.hash ? { el: to.hash, behavior: 'smooth' } : { top: 0 }
  },
})

// Apply the URL's locale before the view renders, so the first paint is already in the right
// language rather than flipping after mount.
router.beforeEach((to) => {
  const locale = localeOf(to)
  setI18nLocale(locale)
  rememberLocale(locale)
})

// Static views get their title and description from route meta. Detail views have neither key and
// supply their own from the loaded entity (see usePageMeta) — which happens after this, so their
// component overwrites what is applied here rather than racing it.
router.afterEach((to) => {
  applyPageMeta(
    staticPageMeta(
      to.meta.titleKey ? i18n.global.t(to.meta.titleKey) : null,
      to.meta.descriptionKey ? i18n.global.t(to.meta.descriptionKey) : null,
    ),
  )
  // Canonical, hreflang and og:locale follow the resolved route rather than the requested one, so
  // a redirect (`/venues` → `/en/venues`) annotates the destination and never the URL that was
  // typed. `to.path` excludes the query on purpose — see updateSeoTags.
  updateSeoTags(localeOf(to), stripLocale(to.path))
})

export default router
