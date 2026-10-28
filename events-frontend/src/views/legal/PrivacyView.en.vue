<script lang="ts" setup>
/**
 * Privacy notice under Art. 13 GDPR — English version. `PrivacyView.de.vue` is the authoritative
 * one (LEGAL.md §6.1); **change both files or neither**, and take shared facts from `@/lib/legal`
 * rather than typing them twice.
 *
 * Structured against the twelve mandatory items in docs/LEGAL.md §7.2 — missing one is the usual
 * defect. It describes no processing that does not happen: no consent withdrawal, no analytics. Imaginary processing is as inaccurate as omitting real processing.
 *
 * **Two claims here are coupled to deployed configuration** (§7.5.1, §7.3a). §2's **14 days** is
 * `ZO_COMPACT_DATA_RETENTION_DAYS`, and §5's "no image request reaches a third party" is
 * `images.serving.enabled`. Change either value and this notice becomes false.
 */
import { useI18n } from 'vue-i18n'

import LegalPage from '@/components/LegalPage.vue'
import ProvisionalNotice from '@/components/ProvisionalNotice.vue'
import { CONTROLLER, SUPERVISORY_AUTHORITY } from '@/lib/legal'

const { t } = useI18n()
</script>

<template>
  <LegalPage
    intro="What data Event Junkie processes, why, and what you can do about it."
    show-authoritative-version
    show-review-date
    title="Privacy"
  >
    <ProvisionalNotice />

    <section>
      <h2>The short version</h2>
      <p>
        Event Junkie runs no analytics and embeds no third-party fonts, maps, social widgets or
        advertising. It sets cookies only when you sign in, and nothing on this site needs an
        account. Nothing you do here is tracked or profiled. What remains is the unavoidable
        minimum: a web server records requests so the site can be delivered and defended, and your
        browser remembers which language, colour scheme and list view you chose. If you ask a map
        what is near you, your position stays in your browser. If you create an account, I keep your
        e-mail address until you delete it (section 6). If you turn on notifications, they pass
        through your browser maker's push service, encrypted (section 6).
      </p>
      <p>
        <strong>Images come from Event Junkie too.</strong> Event Junkie downloads them once and
        serves them itself, so viewing a page sends your browser to nobody else. Section 5 says what
        follows from that.
      </p>
    </section>

    <section>
      <h2>1. Controller</h2>
      <p>The controller responsible for data processing on this site is:</p>
      <p>{{ CONTROLLER.name }}</p>
      <p>{{ CONTROLLER.careOf }}</p>
      <p>{{ CONTROLLER.street }}</p>
      <p>{{ CONTROLLER.city }}</p>
      <p>{{ t('legal.country') }}</p>
      <p>
        Email:
        <a :href="`mailto:${CONTROLLER.email}`">{{ CONTROLLER.email }}</a>
      </p>
      <p>
        No data protection officer has been appointed. Neither Art. 37 GDPR nor § 38 BDSG requires
        one here: the site is run by one person, data is not processed on a large scale, and there
        is no systematic monitoring. Use the address above for anything concerning your data.
      </p>
    </section>

    <section>
      <h2>2. Server log files</h2>
      <p>
        Delivering a web page necessarily means your browser sends a request, and the servers
        involved record it. The following may be processed: date and time of the request, the page
        or file requested, the HTTP status and amount of data transferred, the referring page, and
        your browser and operating system identification. Your IP address is not among them: the web
        server uses a log format that omits it, so no address is written in the first place.
      </p>
      <p>
        <strong>Purpose:</strong> delivering the site, keeping it available and secure, defending
        against attacks and abuse, and diagnosing faults.
      </p>
      <p>
        <strong>Legal basis:</strong> Art. 6 (1) (f) GDPR. My legitimate interest is operating a
        functioning, secure website. Recital 49 GDPR recognises that interest. I do not use this
        data to identify you or to build a profile, and it is not combined with anything else.
      </p>
      <p>
        <strong>Retention:</strong> log data is kept for <strong>14 days</strong> and then deleted
        automatically. Two independent mechanisms bound it, and the shorter one usually wins: on the
        server itself entries rotate by volume, so a fixed amount is held and the oldest is
        overwritten. A line often disappears sooner that way. The log store deletes everything older
        than 14 days regardless. Entries are kept longer where a specific security incident requires
        it. This page always states the period actually configured.
      </p>
      <p>
        <strong>Backups:</strong> the database is backed up. Those backups are normally kept for
        <strong>30 days</strong> and then deleted automatically, and in every case they are deleted
        <strong>within 35 days</strong>. The two figures differ because two independent mechanisms
        delete them: a daily sweep on the server, which keeps a backup for as long as the others
        need it to remain restorable, and a rule on the storage service, which applies regardless,
        including while the server is down. This period is separate from the one above: data already
        deleted from the live system can still exist in a backup for up to 35 days. Backups are used
        solely to recover from a failure. They are not searched and not analysed.
      </p>
    </section>

    <section>
      <h2>3. Local storage on your device</h2>
      <p>
        Without a sign-in, this site sets <strong>no cookies</strong>. It stores exactly three
        values in your browser's local storage, and each only records a setting you made yourself:
      </p>
      <ul>
        <li>
          <code>theme</code>, holding <code>light</code> or <code>dark</code>, so your display
          preference survives a reload
        </li>
        <li>
          <code>locale</code>, holding <code>de</code> or <code>en</code>, so your next visit starts
          in the language you last chose
        </li>
        <li>
          <code>view</code>, holding <code>poster</code> or <code>compact</code>, so the event list
          appears as you last set it: with pictures or compact
        </li>
      </ul>
      <p>
        These values never leave your device, are not transmitted to Event Junkie or to anyone else,
        and are not readable by any third party. All three are strictly necessary to provide the
        setting you explicitly chose, so under § 25 (2) 2 TDDDG they require no consent. That is why
        you are not being asked to dismiss a banner. You can clear them at any time through your
        browser settings.
      </p>
      <!-- LEGAL.md §7.4: the two cookies exist only on the sign-in and account pages. -->
      <h3>Cookies when you sign in</h3>
      <p>
        When you open the sign-in page or the account page, Event Junkie sets two cookies, and only
        these:
      </p>
      <ul>
        <li>
          <code>XSRF-TOKEN</code>, as soon as you open one of these pages. It holds a random value
          and stops another site from requesting a sign-in link or changing your account in your
          name. It expires after 30 days.
        </li>
        <li>
          <code>__Host-ej_session</code>, as soon as you sign in with the link or a passkey. It
          holds a random identifier of your session and keeps you signed in. It expires 30 days
          after its last use, and signing out deletes it.
        </li>
      </ul>
      <p>
        Both are strictly necessary for the sign-in you ask for, so under § 25 (2) 2 TDDDG they
        require no consent. They are not used for tracking and go to nobody but Event Junkie.
      </p>
      <!-- LEGAL.md §7.4b: the push worker exists only in a browser that turned notifications on (ADR-055). -->
      <h3>Notifications, when you turn them on</h3>
      <p>
        Only when you press <strong>Turn on notifications</strong> on your account page does your
        browser ask whether this site may send you notifications, and you decide. If you allow it,
        Event Junkie installs a small script in your browser, a service worker, and your browser
        stores a subscription for this site. The script shows a notification that arrives and opens
        its link when you click it. It does nothing else: it stores nothing and does not change how
        a page loads. Both are strictly necessary for the notifications you ask for, so under § 25
        (2) 2 TDDDG they require no consent. Turning notifications off removes both.
      </p>
      <!-- LEGAL.md §7.4a: the position is used in the browser and nowhere else. -->
      <h3>Your location, for “near me”</h3>
      <p>
        The events map can show what is on near you, and the venues map which venues are near you.
        Only when you choose
        <strong>Use my location</strong> does your browser ask whether to share your position, and
        you decide. If you allow it, the position is used <strong>only in your browser</strong>, to
        measure the distance to each venue. It is not sent to Event Junkie or to anyone else, it
        appears in no address and no log, and it is not stored: it is gone when you reload the page
        or close the tab. On the events map you can pick a point or a venue instead, with the same
        result.
      </p>
      <p>
        Because the position never leaves your device, I hold no copy of it, and there is nothing
        for me to delete or disclose.
      </p>
    </section>

    <section>
      <!-- §7.3: artist names are personal data where the artist is a natural person. Naming this
           and offering a removal route is the single most likely real-world complaint to arrive. -->
      <h2>4. Event, artist and venue information</h2>
      <p>
        Event Junkie collects publicly available information about events in Berlin from the
        websites of venues, promoters and ticket sellers, and presents it in one place. Where a
        performing artist is a natural person, their name and their billing in a line-up are
        personal data.
      </p>
      <p>
        <strong>Where this comes from.</strong> It does not come from you. It comes from the public
        sources named above, so Art. 14 GDPR governs it rather than Art. 13. Telling every named
        person individually would take disproportionate effort. Art. 14 (5) (b) GDPR accepts making
        the information public instead, which is what this page does.
      </p>
      <p>
        Artist profiles are checked against MusicBrainz, an open music database. Where they match,
        the official links and the picture come from there and from Wikidata. Only the artist's
        stage name is sent. For bands, orchestras and choirs, the short description comes from
        Wikipedia. Wikidata also says whether an artist performs as a comedian or cabaret artist,
        and a night its venue gives no category is then listed as comedy. Where MusicBrainz does not
        know a name, the importer asks Discogs (Zink Media, LLC, USA) and store only the link to the
        Discogs page on a match; again only the stage name is sent. This website uses the Discogs
        API but is not affiliated with, sponsored or endorsed by Discogs. “Discogs” is a trademark
        of Zink Media, LLC.
      </p>
      <p>
        The line-ups and set times of the weekends at Sisyphos come from sisy.fan, an unofficial
        timetable run by fans, which its developer allows Event Junkie to use. The importer only
        fetches its page; nothing about you is sent to it.
      </p>
      <p>
        For some venues with no website of their own, the events come from the public posts of their
        Instagram business account. The importer reads them through Meta's official interface (Meta
        Platforms Ireland Ltd.) as Event Junkie's own account. It reads the text, the link, the date
        and the images of a post. A language model drafts the date, the title and the line-up from
        them (section 5), and I check every draft before an event appears. The post's text, and the
        text read off its images, are stored only until that check, for 14 days at most, and are
        never shown on this site. The images are not stored. The event links the post. Nothing about
        you is sent.
      </p>
      <p>
        <strong>Purpose:</strong> informing the public about publicly announced cultural events.
      </p>
      <p>
        <strong>Legal basis:</strong> Art. 6 (1) (f) GDPR. My legitimate interest is providing an
        events guide. The information concerned has already been published by the organiser for the
        express purpose of promoting the event, and is limited to that professional context. Event
        Junkie stores no contact details, no private addresses and no information about anyone's
        private life.
      </p>
      <p>
        <strong>Retention:</strong> event data is not deleted after a fixed period. Past events are
        kept as an archive for as long as the calendar is operated; there is no automatic deletion
        by age. The criterion is therefore the operation of the calendar rather than the passage of
        time. Your name is removed independently of that as soon as you object to the processing
        under Art. 21 GDPR. The email address in the next paragraph is all it takes.
      </p>
      <p>
        <strong>Images:</strong> they come from two places. Venues, promoters and artists publish
        images, with individual events and of themselves. Photographs of venue buildings also come
        from open archives such as Wikimedia Commons and Flickr, where a photographer published them
        under a licence that permits it. Event Junkie downloads both kinds and stores a copy on its
        own servers at Hetzner, rather than asking the provider for the file again on every page
        view. The legal basis is Art. 6 (1) (f) GDPR. The legitimate interest is delivering the site
        without involving a third party, and placing less load on a venue's server. The copy is
        deleted as soon as the venue opts out or a rights holder asks for it, and it is not fetched
        again. For an archive photograph the rights holder is the photographer, not the venue, and
        the credit under the picture names them.
      </p>
      <p>
        <strong>Translations:</strong> descriptions are machine translated, so that the site can
        show them in both languages. The text is sent to a service provider for that, named in
        section 5. A machine-translated description says so on the event page, and the original
        stays authoritative.
      </p>
      <p>
        <strong>Checking event data:</strong> an event's title, subtitle and description are checked
        by machine, to find errors made during the import. An example is a wrong type of event, or a
        title stored as a performing artist. The result is only a suggestion, and I decide whether
        it is applied. The texts are sent to the same service provider as for translation (section
        5). The legal basis is Art. 6 (1) (f) GDPR. The legitimate interest is correct information
        about the events.
      </p>
      <p>
        <strong>If you are an artist</strong> and would like your name, or a description that names
        you, removed or corrected, write to
        <a :href="`mailto:${CONTROLLER.email}`">{{ CONTROLLER.email }}</a>
        and I will deal with it promptly. You do not need to give a reason, and you do not have to
        raise it in public.
      </p>
    </section>

    <section>
      <h2>5. Who else is involved</h2>
      <p>
        I sell nothing, and I share nothing for advertising. Data is disclosed to three service
        providers, each a processor bound by a contract under Art. 28 GDPR:
      </p>
      <ul>
        <li>
          <strong>Hosting:</strong> Hetzner Online GmbH, Gunzenhausen, Germany. Servers are located
          in Germany; no transfer outside the EU is involved.
        </li>
        <li>
          <strong>Sending e-mail:</strong> Scaleway SAS, Paris, France. When you ask for a sign-in
          link, Scaleway delivers the mail to your address. For that, Scaleway receives your e-mail
          address and the text of the mail. Servers are located in the EU; no transfer outside the
          EU is involved.
        </li>
        <li>
          <strong>Machine translation and checking event data:</strong> Anthropic PBC, San
          Francisco, USA. An event's description is sent there so that it can be translated. For the
          check (section 4), its title and subtitle go as well. Where these texts name performing
          artists, those names go with them, and the names of the line-up and the venue are sent
          explicitly as well, so that the translation leaves them unchanged. For events from
          Instagram posts (section 4), the post's text and images go there, with the venue's name
          and the post's date. Where a post names people, their names go with it, and an image can
          show a person. Nothing else reaches this service: no log data, and nothing about you.
        </li>
      </ul>
      <p>
        <strong>Transfer to the USA.</strong> Anthropic processes these texts and images on my
        instruction only. The basis is the Data Processing Addendum, which is part of the contract
        through the Commercial Terms of Service and includes the European Commission's standard
        contractual clauses (Art. 46 (2) (c) GDPR). The same contract rules out using the
        transmitted texts and images to train models.
      </p>
      <p>
        There is no content delivery network, no edge provider and no traffic proxy in front of the
        site. Your request reaches Event Junkie's servers in Germany directly, so no third country
        is involved in delivering this site to you. Translation, checking and drafting above are the
        only path on which data leaves the EU, and they carry event texts and flyers rather than
        anything about your visit. The one exception is notifications, if you turn them on: section
        6 says what reaches your browser's push service.
      </p>
      <p>
        <strong>That holds for images as well.</strong> They come from Event Junkie's servers,
        because Event Junkie downloads each one and serves it itself (section 4). Your browser
        therefore contacts no venue, promoter or ticket seller, and none of them learns your IP
        address when you look at a page. Where Event Junkie may not store an image, it shows none at
        all rather than loading it from the provider.
      </p>
      <p>
        If you contact me by email, or open an issue on GitHub, that data is processed to handle
        your request (Art. 6 (1) (f), or Art. 6 (1) (b) where it concerns a contract). GitHub is
        operated by a US company; choosing to write there rather than by email is yours.
      </p>
      <p>
        <strong>Retention:</strong> I keep an email until your request is dealt with, and delete it
        <strong>one year after that at the latest</strong>. The year covers follow-up questions
        about the same request.
      </p>
    </section>

    <!-- LEGAL.md §7.1: an account is optional, and the account page is its deletion route. -->
    <section id="account">
      <h2>6. Your account</h2>
      <p>
        An account is optional. You sign in without a password: you enter your e-mail address, and
        Event Junkie sends you a link from <code>noreply@notify.event-junkie.de</code>. It works
        once, for 15 minutes. The first time you use one, your account is created.
      </p>
      <p>
        <strong>What is stored:</strong> your e-mail address, the language you signed in with, when
        you created the account and when you last signed in. Each sign-in adds a session with its
        start, its last use and its expiry. Of the link and the session, I store only a hash, not
        the value itself. When you ask for a link, I store it with the address, the language and the
        time, even if you never use it.
      </p>
      <p>
        <strong>What you follow:</strong> when you follow an artist, a venue or a promoter, I store
        that and when. Your Following page is made from it. You see the list there and can unfollow
        each entry. Only you can see it. If an entry leaves the calendar, I delete the follow too.
      </p>
      <p>
        <strong>Saved searches:</strong> when you save a search, I store its filters, the name you
        give it and when you saved it. The page suggests a name from the filters, and you can change
        it. Your Following page lists your saved searches. You can rename or delete each one there.
        Only you can see them.
      </p>
      <p>
        <strong>Your plans:</strong> when you mark an event "Interested" or "Going", I store that
        and when. Your Following page and My calendar are made from it, and the event cards show it
        to you. Only you can see it. Nobody else sees who plans to go, and no event shows a count.
        Past events stay as your history until you withdraw them or delete the account. If an event
        leaves the calendar, I delete your plan for it too.
      </p>
      <p>
        <strong>What is new:</strong> when something you follow gets a new date, I store a note for
        your account: which event, which artist, venue or promoter it came from, when I made the
        note and when you opened it. The bell in the header lists the newest 20 and counts those you
        have not opened. Only you can see them. I delete each note after <strong>30 days</strong>,
        read or not, and sooner if its event leaves the calendar.
      </p>
      <p>
        <strong>Notifications:</strong> if you turn notifications on in a browser, I store that
        browser's subscription: an address at its push service and two keys, with the time you
        turned it on and when the last notification went out. The bell on an artist, a venue or a
        promoter you follow decides what a notification is about: a new date of it. A notification
        carries a title, one line and a link, nothing else, and it is encrypted so that only your
        browser can read it. The subscription is deleted when you turn notifications off, when the
        push service reports it gone, and with your account.
      </p>
      <p>
        <strong>The push service:</strong> a notification goes through the push service of your
        browser's maker: Google for Chrome and Android, Mozilla for Firefox, Apple for Safari and
        Microsoft for Edge. Your browser chooses the service, not Event Junkie. It receives the
        subscription address and the time of each notification, but cannot read the content. All
        four are US companies, so this data reaches a third country.
      </p>
      <p>
        <strong>Passkeys:</strong> if you add a passkey on your account page, your device makes a
        key pair. The private key stays on your device or in your password manager, which may sync
        it to your other devices. Your device unlocks it with your fingerprint, your face or a PIN,
        and none of these ever reaches Event Junkie. I store the public key, an identifier of the
        passkey, a counter, the name you give it, when you added it and when you last used it. I do
        not store which make of device or password manager made it. Your device keeps the passkey
        with a random identifier of your account, and with your e-mail address so that it can show
        you which account it is for. You can rename or delete each passkey on your account page.
        When you delete your account, all its passkeys are deleted too. A passkey you delete here
        stays on your device until you remove it there, but you can no longer sign in with it.
      </p>
      <p>
        <strong>Purpose and legal basis:</strong> the account is a service you ask for yourself. The
        legal basis is Art. 6 (1) (b) GDPR.
      </p>
      <p>
        <strong>Protection against abuse:</strong> one address gets at most three links an hour. So
        that nobody can trigger any number of mails from one connection, the server also counts the
        requests per IP address, at most 20 an hour, and passkey sign-ins per IP address, at most 30
        an hour. It keeps these counts in memory only. They are stored nowhere and are gone after an
        hour at most. The legal basis is Art. 6 (1) (f) GDPR, my legitimate interest in protecting
        the mail service and the sign-in from abuse.
      </p>
      <p>
        <strong>Retention:</strong> your account stays until you delete it. If you do not use it for
        <strong>24 months</strong>, I delete it, and you get a mail one month before. Every sign-in
        counts as use, and so does every visit while you are signed in. A session ends 30 days after
        its last use, or when you sign out. A link expires after 15 minutes. Expired links and
        sessions are deleted daily, and notes under the bell after 30 days.
      </p>
      <p>
        <strong>Do it yourself:</strong> on your account page you can download everything stored as
        a file (Art. 15 and 20 GDPR), sign out on all devices and delete the account. Deletion takes
        effect at once and takes every session with it. Section 7 covers backups.
      </p>
    </section>

    <section>
      <h2>7. Your rights</h2>
      <p>Under the GDPR you have the right to:</p>
      <ul>
        <li>access the personal data I hold about you (Art. 15)</li>
        <li>have inaccurate data corrected (Art. 16)</li>
        <li>have data erased (Art. 17)</li>
        <li>have processing restricted (Art. 18)</li>
        <li>receive your data in a portable format (Art. 20)</li>
      </ul>
      <p>
        <strong>Erasure and backups.</strong> An erasure under Art. 17 takes effect immediately in
        the live system. Backups are not edited individually. Extracting one person from a backup
        would destroy its purpose, and would only be technically possible by accepting new risks.
        The data therefore expires with the backup itself, so within 35 days at most. If a backup
        has to be restored in the meantime, I re-apply the erasure afterwards.
      </p>
      <p>
        <strong>Right to object (Art. 21 GDPR).</strong> Because I rely on legitimate interests
        under Art. 6 (1) (f), you have the right to object at any time, on grounds relating to your
        particular situation, to processing of your personal data. If you object, I will stop
        processing unless I can demonstrate compelling legitimate grounds that override your
        interests, rights and freedoms. An objection is free and informal, and an email is enough.
      </p>
      <p>
        <strong>Right to complain.</strong> You may lodge a complaint with a supervisory authority,
        in particular in the member state of your habitual residence, your place of work, or the
        place of the alleged infringement. The authority responsible for me is the
        <a :href="SUPERVISORY_AUTHORITY.url" rel="noopener" target="_blank">
          {{ SUPERVISORY_AUTHORITY.name }}</a
        >.
      </p>
    </section>

    <section>
      <h2>8. Is providing data required?</h2>
      <p>
        You are neither legally nor contractually obliged to provide personal data. Technically,
        however, a web page cannot be delivered without your device's IP address, so browsing this
        site without that being processed is not possible. Everything else is voluntary. An account
        needs an e-mail address; without one, I cannot send you a sign-in link.
      </p>
    </section>

    <section>
      <h2>9. Automated decision-making</h2>
      <p>
        There is none. I do not use automated decision-making or profiling within the meaning of
        Art. 22 GDPR.
      </p>
    </section>

    <section>
      <h2>10. Changes to this notice</h2>
      <p>
        This notice describes what the system actually does, so it changes when the system does. The
        review date at the top of this page tells you when it was last checked.
      </p>
    </section>
  </LegalPage>
</template>
