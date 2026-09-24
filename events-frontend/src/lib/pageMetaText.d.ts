// `scripts/pageMetaText.ts` provides this module in every build. The shape is the two catalogue
// files, so a key renamed there fails the type check here.
declare module 'virtual:page-meta-text' {
  import type pageDescription from '@/i18n/messages/en/pageDescription.json'
  import type pageTitle from '@/i18n/messages/en/pageTitle.json'
  import type { Locale } from '@/i18n/locales'

  const text: Record<
    Locale,
    { pageTitle: typeof pageTitle; pageDescription: typeof pageDescription }
  >
  export default text
}
