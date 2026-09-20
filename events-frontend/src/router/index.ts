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
    // Per-view title and description as message keys; detail views set both from loaded data
    // (usePageMeta).
    titleKey?: string
    descriptionKey?: string
  }
}

/**
 * Pass-through parent for the `/:locale` segment; the app shell is still App.vue.
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
      // Path-prefixed locales rather than a stored preference (ADR-013 §Decision 2): a locale only in
      // storage makes every shared link a coin flip and is invisible to crawlers. Built from LOCALES so
      // a locale can never be routable without being published.
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
          // Lazy-loaded so FullCalendar's weight does not affect first paint elsewhere (ADR-011).
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
          path: 'promoters',
          name: 'promoters',
          meta: { titleKey: 'pageTitle.promoters', descriptionKey: 'pageDescription.promoters' },
          component: () => import('../views/PromotersView.vue'),
        },
        {
          path: 'promoters/:slug',
          name: 'promoter',
          component: () => import('../views/PromoterDetailView.vue'),
        },
        // The five long-form pages have one component per language (views/localisedView.ts), the
        // exception to user-facing text living in the message catalogue.
        {
          path: 'about',
          name: 'about',
          meta: { titleKey: 'pageTitle.about', descriptionKey: 'pageDescription.about' },
          component: localisedView({
            en: () => import('../views/AboutView.en.vue'),
            de: () => import('../views/AboutView.de.vue'),
          }),
        },
        // Legal pages under /legal/*, so later additions have a home. Lazy-loaded: read rarely.
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
        // Venue-facing: publishes the opt-out route docs/SCRAPING_POSITION.md §5 defines. Under /legal/*
        // because that is where the site's promises live.
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
      // Anything without a locale prefix, `/` included, gets one and is redirected. The `already` guard
      // matters: without it `/en/nonsense` would become `/en/en/nonsense` and loop. The locale home
      // stands in for a 404 view for now.
      path: '/:pathMatch(.*)*',
      redirect: (to) => {
        const [first] = to.path.split('/').filter(Boolean)
        const already = isLocale(first)
        const locale = already ? first : resolveLocale()
        // `to.path` is `/` for the bare root, which would make `/en/`, a second URL for `/en`.
        const rest = to.path === '/' ? '' : to.path
        return {
          path: already ? `/${locale}` : `/${locale}${rest}`,
          query: to.query,
          hash: to.hash,
        }
      },
    },
  ],
  // Legal pages are reached from the footer of a scrolled page; without this the imprint opens
  // mid-document. A history traversal returns to where the visitor left: a scrollBehavior switches
  // the browser's restoration off, and the views repaint from useAsync's cache in the same tick, so
  // the document has its height (#1111).
  scrollBehavior(to, _from, savedPosition) {
    if (savedPosition) return savedPosition
    return to.hash ? { el: to.hash, behavior: 'smooth' } : { top: 0 }
  },
})

// Apply the URL's locale before the view renders, so the first paint is in the right language.
router.beforeEach((to) => {
  const locale = localeOf(to)
  setI18nLocale(locale)
  rememberLocale(locale)
})

// Static views get their title and description from route meta; detail views supply their own
// from the loaded entity after this, so they overwrite rather than race.
router.afterEach((to) => {
  applyPageMeta(
    staticPageMeta(
      to.meta.titleKey ? i18n.global.t(to.meta.titleKey) : null,
      to.meta.descriptionKey ? i18n.global.t(to.meta.descriptionKey) : null,
    ),
  )
  // Canonical, hreflang and og:locale follow the resolved route, so a redirect annotates the
  // destination. `to.path` excludes the query on purpose (updateSeoTags).
  updateSeoTags(localeOf(to), stripLocale(to.path))
})

export default router
