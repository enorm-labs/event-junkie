# ADR-053: Visitors sign in by e-mail link in the BFF, with no password and no identity provider

## Status

**Accepted (2026-10-24). A visitor signs in with a one-time link sent by e-mail. Spring Security 7 in `events-bff` does it, with no password and no
identity provider. A passkey is the second method. Sessions, tokens and account data live in a new `accounts` schema in the existing PostgreSQL.**

Decided in [#3884](https://github.com/enorm-labs/event-junkie/issues/3884).

**Rule 1 amended (2026-10-24, #3887): the BFF issues and uses the link in its own two endpoints, over the same token table, not through
`oneTimeTokenLogin()`.** The reactive DSL passes its token service only an address and an expiry, so the locale cannot travel with the token. A rejected
request also falls through to a 404. Everything else in rule 1 stands: 15 minutes, single use, the hash only, the same answer for every address. The sign-in shipped in [#3887](https://github.com/enorm-labs/event-junkie/issues/3887).

**Rule 7 settled (2026-10-27, #3888): passkeys run on `webauthn4j-core` behind our own endpoints.** spring-security#18393 was still open, with no
milestone. User verification is required at each sign-in, because a passkey replaces the mail.

**Does not supersede anything.** [ADR-023](ADR-023_OPERATOR_AUTHENTICATION.md) decides operator access and stays as it is. It names end-user identity as a
separate question. This ADR answers that question. [ADR-005](ADR-005_MIGRATIONS_OWNED_BY_IMPORTER.md) stays: the importer also owns the `accounts`
migrations.

## Context

[#397](https://github.com/enorm-labs/event-junkie/issues/397) decided **account first** on 2026-10-24. Following an artist, a saved search, a notification
and an RSVP all need a signed-in visitor. So the site needs sign-in before any of the epic [#398](https://github.com/enorm-labs/event-junkie/issues/398) can
ship.

**What is true today**, checked rather than assumed:

|                  |                                                                                                                                                                         |
| ---------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Spring Security  | Not a dependency in any module (ADR-023)                                                                                                                                |
| BFF replicas     | Two (`bff.replicaCount: 2` in `values.yaml`). A value in one pod's memory is not in the other pod                                                                       |
| BFF writes       | None. `values.yaml` calls the BFF "stateless and read-only". The code enforces that, not a database grant                                                               |
| Database user    | One. `database.existingSecret` gives the BFF and the importer the same user (`_helpers.tpl`)                                                                            |
| Response caching | `CacheControlFilter` marks every successful GET `public`. `ResponseCache` keeps responses in the BFF's memory                                                           |
| Mail             | Role mailboxes on Hetzner Webhosting S, under the account-level AVV (LEGAL.md §7). Sending is set up in [#3886](https://github.com/enorm-labs/event-junkie/issues/3886) |

**What Spring Security 7.1.2 supports on WebFlux**, from its reference documentation and its tracker:

- **One-time token login: yes.** `ServerHttpSecurity.oneTimeTokenLogin()` exists. The only token service it ships is `InMemoryReactiveOneTimeTokenService`.
  The default lifetime is five minutes.
- **Passkeys (WebAuthn): no.** The reference documents passkeys for servlet applications only. The request for reactive support,
  [spring-security#18393](https://github.com/spring-projects/spring-security/issues/18393), is open since 2026-01-07 and has no milestone.

**The constraints any option had to satisfy:**

- **ADR-015's footprint bound.** A second JVM and a second database compete with the observability stack on the same node. ADR-023 rejected Keycloak on that
  ground.
- **One processor, Hetzner** ([ADR-012](ADR-012_CLOUD_PLATFORM.md), AGENTS.md § Privacy). A new third party that learns who signs in needs a DPA, a transfer
  check and a privacy-notice entry.
- **No third-party resource in the browser** (AGENTS.md § Privacy). Each one transmits the visitor's IP.
- **Two BFF replicas.** A token issued by one pod must work on the other.

## Candidate options

1. **Keycloak, Zitadel or Authentik.** Login, passkeys and recovery are solved. Each one adds a runtime and a database to the node. Each one also brings its
   own login pages, which then need our design and both locales.
2. **Spring Authorization Server.** It makes the site an OAuth provider. One SPA talks to one BFF, so no third party needs tokens from us.
3. **Username and password in Spring Security.** It works. We then own hashing, the reset flow, breach checks and defence against credential stuffing. The
   reset flow needs mail, so mail is needed anyway.
4. **"Sign in with Google" or Apple.** No credential to keep. Each provider learns who uses the site and when. Each one is a processor or a joint controller.
5. **An e-mail link, then passkeys, in the BFF.** No credential to leak or reset. The first link is also the double opt-in for the address.
6. **Option 5 in a separate accounts service.** The BFF stays read-only. But every personal request needs the session in the BFF. The "Following" page
   filters events by follows. So the BFF either calls the service on each request, or reads its session table, or trusts a JWT. The first couples their
   uptime. The second shares one database, so the split exists only on paper. The third cannot revoke a session before it expires. The service also adds a
   JVM with two replicas, against ADR-015's bound.

## Comparison

|                    | New runtime | New processor   | Credential we keep | Recovery          | Works on WebFlux today   |
| ------------------ | ----------- | --------------- | ------------------ | ----------------- | ------------------------ |
| Identity provider  | JVM + DB    | None if hosted  | Its own            | Its own           | Yes, through OIDC        |
| Authorization Srv. | None        | None            | Ours               | Ours to write     | Yes                      |
| Passwords          | None        | None            | Password hashes    | Mail link         | Yes                      |
| Social login       | None        | **One per IdP** | None               | The IdP's         | Yes                      |
| **E-mail link**    | **None**    | **None**        | **None**           | **The same link** | **Link yes, passkey no** |
| Separate service   | JVM         | None            | None               | The same link     | Link yes, passkey no     |

## Decision

**Option 5.** The reason that settled it: **a password protects an account that the e-mail address can already reset.** The mailbox is the real credential
in every option with a reset flow. The e-mail link uses that credential directly and adds nothing to leak.

Option 6 is rejected because the risk it addresses is the BFF's write access, not where the code lives. Rule 5 removes that risk with a database role.

The rules:

1. **Sign-in.** `oneTimeTokenLogin()` with our own `ReactiveOneTimeTokenService` on PostgreSQL, because the in-memory one breaks across two replicas. A
   token is valid 15 minutes and works once. The database holds its SHA-256 hash, never the token. A request for an unknown address answers the same as one
   for a known address. The first valid link creates the account.
2. **Session.** An opaque random token in a `__Host-` cookie: `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`. Our own `ServerSecurityContextRepository`
   stores the session on PostgreSQL by the token's hash. No JWT, and nothing in `localStorage`. Sign-out deletes the row. "Sign out everywhere" deletes all
   rows of the account.
3. **CSRF.** Spring Security's CSRF protection stays on, with the cookie-to-header token for the SPA.
4. **Caching.** Personal endpoints live under `/api/me/**`. `CacheControlFilter` gives them `private, no-store`, and `ResponseCache` never stores them. A
   test asserts both.
5. **Schema and role.** A new `accounts` schema. The importer owns its migrations, as ADR-005 requires for every schema. The importer reads the rows for
   notification jobs. **The BFF gets its own database user** before it writes anything. It may read `events` and read and write `accounts`, nothing else. The
   pod that faces the internet then cannot change event data.
6. **Module.** The code is an `account` module in `events-bff`. `ModularityTests` checks its boundaries (ADR-006). It can move to its own service later if
   the triggers under _When to revisit_ occur.
7. **Passkeys are the second method**, in [#3888](https://github.com/enorm-labs/event-junkie/issues/3888). Spring Security has no reactive support
   (spring-security#18393), so `Passkeys.kt` uses `webauthn4j-core` behind endpoints of our own. The relying party is the cluster's origin. User
   verification is required, attestation is `none`, and the stored row drops the AAGUID. The e-mail link stays the recovery route, and no account has
   a passkey only.
8. **Abuse.** A rate limit per address and per IP on the request for a link. No CAPTCHA from a third party. A self-hosted proof of work (ALTCHA) is the
   fallback if the rate limit is not enough.

## Consequences

- **The BFF is no longer read-only.** It writes accounts, tokens and sessions. The comment on `bff.replicaCount` and every document that calls the BFF
  stateless must change in #3887.
- **A second database user and a second Secret.** Today the BFF shares the importer's user. Rule 5 needs a new role, its grants and a hand-made Secret on
  both clusters (docs/ops/SECRETS.md). The chart then needs a separate `existingSecret` for the BFF. This lands in #3887, before the first write.
- **Sign-in depends on mail delivery.** A mail outage or a spam filter locks visitors out. Mail monitoring ([`docs/ops/EMAIL.md`](../ops/EMAIL.md) §7a) now
  watches a product path, not only the role mailboxes.
- **We own two security components**: the token service and the session repository. Each one needs tests for expiry, single use and deletion.
- **Personal data begins.** E-mail addresses of visitors are a new category in LEGAL.md §7.3a. #3887 updates LEGAL.md and both privacy notices in the same
  PR. The session cookie is strictly necessary under § 25(2) TDDDG, so no consent banner follows from it.
- **A native mobile app would need tokens**, not a cookie. Nothing plans one today.

## When to revisit

- **spring-security#18393 ships.** Then passkeys use the framework, and any `webauthn4j-core` code from #3888 goes.
- **A second client needs tokens**, such as a native app or the public API of [#402](https://github.com/enorm-labs/event-junkie/issues/402). Then option 2
  comes back into question.

## References

- [#3884](https://github.com/enorm-labs/event-junkie/issues/3884) — the decision issue
- [#397](https://github.com/enorm-labs/event-junkie/issues/397) — account first · [#398](https://github.com/enorm-labs/event-junkie/issues/398) — the epic
- [ADR-005](ADR-005_MIGRATIONS_OWNED_BY_IMPORTER.md) · [ADR-012](ADR-012_CLOUD_PLATFORM.md) · [ADR-015](ADR-015_OBSERVABILITY_STACK.md) ·
  [ADR-023](ADR-023_OPERATOR_AUTHENTICATION.md)
- [Spring Security: One-Time Token Login, reactive](https://docs.spring.io/spring-security/reference/reactive/authentication/onetimetoken.html)
- [Spring Security: Passkeys, servlet only](https://docs.spring.io/spring-security/reference/servlet/authentication/passkeys.html)
- [spring-security#18393 — Reactive WebAuthn support](https://github.com/spring-projects/spring-security/issues/18393)
