# ADR-055: Web Push through a service worker that the visitor turns on, with no caching

## Status

**Accepted (2026-10-27). The site may send Web Push notifications to a signed-in visitor. A service worker makes this possible. The site registers it
only when the visitor turns on notifications. The worker handles push messages and notification clicks, and nothing else. It caches nothing.**

Decided in [#3885](https://github.com/enorm-labs/event-junkie/issues/3885), option 2, by the operator.
[#3894](https://github.com/enorm-labs/event-junkie/issues/3894) builds it. **Implemented.** A cluster sends pushes once its push Secret exists ([`docs/ops/SECRETS.md`](../ops/SECRETS.md)).

**Amends [#2727](https://github.com/enorm-labs/event-junkie/issues/2727).** #2727 decided "a web app manifest only, no service worker". That rule stays
for every visitor who does not turn on notifications. Offline caching (#2727 options 3 and 4) is still out of scope and needs its own decision.

## Context

Accounts can follow artists, venues, promoters and festivals (#3889) and mark events as interested or going (#3892). Today nothing tells a visitor
when a followed artist announces a Berlin date. The visitor must open the site.

**Mail was the first plan.** The digest of [#3891](https://github.com/enorm-labs/event-junkie/issues/3891) is parked, and mail now only signs in. The
recommendation in #3885 was "push after the digest shows demand". With the digest parked, that condition cannot occur.

**What is true about Web Push** (2026-10-27):

- It works without an app in Chrome, Firefox, Edge and Safari on macOS.
- On iPhone and iPad it works only in a site added to the Home Screen (iOS 16.4 and later). The manifest from #2727 is the prerequisite.
- It needs a service worker. A page cannot receive a push message.
- **Every message goes through the push service of the visitor's browser,** not ours: Google (Chrome, Android), Mozilla (Firefox), Apple (Safari) and
  Microsoft (Edge). The browser chooses the service. The service receives the subscription endpoint, the time of each message and an encrypted
  payload. It cannot read the payload (RFC 8291).

**Storage on the device.** A registered service worker is storage on terminal equipment under § 25 TDDDG. Registered only after the visitor asks for
notifications, it is arguably strictly necessary for a service the visitor requested (§ 25(2) no. 2). Registered on every visit, it would not be.

## Candidate options

1. **No Web Push.** Mail only. No new recipient, no worker.
2. **A service worker registered on opt-in, push only.** No caching. A minimal, encrypted payload.
3. **Option 2 plus offline caching.** Out of scope here. #2727 keeps that question open.

## Decision

**Option 2.** The reason that settled it: **it tells a visitor about a new date without an app.** It stores nothing for a visitor who does not ask
for it.

The rules:

1. **Registered on opt-in only.** The site registers the worker when the visitor presses "Turn on notifications" on the account page. It never registers
   it on page load. Turning notifications off unsubscribes and unregisters the worker on that device.
2. **Push and notification clicks only.** The worker has no `fetch` handler and does not use the Cache API, IndexedDB or any other storage. It does not
   change how a page loads.
3. **A minimal payload.** A title, one line and a link to a page on the site, encrypted. A fixed word names the kind of message. No artist list, no
   account data, no tracking parameter.
4. **Only what the visitor chose.** A message goes out for a follow set to _instant_. It also goes out for a change to an event the visitor marked. No
   announcement, no reminder to come back, no message the visitor did not choose.
5. **One subscription per browser.** The BFF stores the endpoint and its keys in the `accounts` schema, keyed to the account. A deleted account
   deletes its subscriptions. A push service that answers 404 or 410 deletes that subscription.
6. **The VAPID private key is a credential.** It lives in a Kubernetes Secret, never in the repository, and goes into `docs/CREDENTIALS.md`.

## Consequences

- **New recipients.** The four push services receive endpoints and message times. The privacy notices and `docs/LEGAL.md` §7 must name them. #3894 does
  that in the PR that sends the first message. The notice describes what runs, so not earlier.
- **Terms of Use.** Push was the point to revisit them (`docs/LEGAL.md` §13 row 15). The answer stayed none: `docs/LEGAL.md` §7.6 says why, and what
  would change it.
- **A new egress path.** The BFF connects out on TCP 443 to the push services. The chart denies all egress by default, so it needs a named
  NetworkPolicy.
- **The CSP needs no change.** `worker-src` falls back to `script-src 'self'`, and the worker is a same-origin file.
- **iPhone and iPad need "Add to Home Screen" first.** The account page must say so on iOS.
- **`docs/PRODUCT_OVERVIEW.md` changes with #3894.** It says "no service worker" today, and that stays true until the worker ships.

## When to revisit

- **The operator wants the site to work offline.** That is #2727's options 3 and 4 and a new decision.
- **A push service starts to read or log more than it does today,** or a browser stops encrypting the payload.
- **Few visitors turn notifications on** after a few months of accounts. Then the worker and the egress path cost more than they give.

## References

- [#3885](https://github.com/enorm-labs/event-junkie/issues/3885) — the decision · [#3894](https://github.com/enorm-labs/event-junkie/issues/3894) —
  the build · [#2727](https://github.com/enorm-labs/event-junkie/issues/2727) — manifest only · [#398](https://github.com/enorm-labs/event-junkie/issues/398)
  — the epic
- [ADR-053](ADR-053_VISITORS_SIGN_IN_BY_EMAIL_LINK_IN_THE_BFF.md) · [ADR-054](ADR-054_TRANSACTIONAL_MAIL_THROUGH_SCALEWAY_TEM.md)
- [RFC 8030: Web Push](https://www.rfc-editor.org/rfc/rfc8030) · [RFC 8291: message encryption](https://www.rfc-editor.org/rfc/rfc8291) ·
  [RFC 8292: VAPID](https://www.rfc-editor.org/rfc/rfc8292)
- [WebKit: Web Push for Web Apps on iOS and iPadOS](https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/)
- [§ 25 TDDDG](https://www.gesetze-im-internet.de/ttdsg/__25.html)
