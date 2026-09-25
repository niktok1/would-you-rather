# CLAUDE.md — Would You Rather

This file is the authoritative source of truth for this project. Claude Code reads it
at the start of every session. If anything in a chat conversation contradicts this file,
**this file wins.** When a decision changes, update this file in the same commit.

---

## 1. What this project is

A cross-platform "Would You Rather" game. Solo play. Questions are fetched from a server.
Players can submit their own questions, track stats, and earn points.

**Targets:** Android, iOS, Desktop (JVM), and Web. Web (js + wasmJs) was moved **into scope**
— the JetBrains KMP wizard generated it and it was kept, so every new client module must
declare `js`/`wasmJs` targets and every client dependency must resolve for them. The practical
consequence to remember: **SQLDelight has no wasmJs driver**, which is why the local cache is
currently an interface with an in-memory implementation (see §4).

The **moderation app** (`:app:adminApp`, §3, §8d *Moderation*) is a second client, for whoever holds
the server's admin token, and targets desktop (JVM) and web only: a moderator works at a computer.

**Learning project.** Built end-to-end with Claude as an exercise in production workflow.
Not connected to any employer or company infrastructure (see §7).

---

## 2. Core principle: Kotlin everywhere

One language across the entire stack — client, shared logic, and server. A data model is
defined **once** and used on both sides of the wire.

**Library selection rule:** prefer libraries that are (a) Kotlin-first, (b) multiplatform-
native, (c) JetBrains-official or the established KMP community standard. Do not introduce a
platform-specific or Java-only library when a multiplatform Kotlin equivalent exists. Do not
add a new dependency without recording it in §4 and in `gradle/libs.versions.toml`.

Four Java libraries are in the tree by deliberate exception, all server-only where no Kotlin
equivalent exists: HikariCP (connection pooling), the PostgreSQL JDBC driver, `java-jwt`
(pulled in by Ktor's own `ktor-server-auth-jwt`), and Flyway (schema migrations, §8b; approved
2026-09-24), with the `flyway-database-postgresql` module Flyway needs to run on PostgreSQL. H2 is
a fifth, used only as the local development database.

One client library is an exception of the same kind: Google Play services' Block Store
(`play-services-auth-blockstore`, *approved 2026-09-25*), Android only, where a phone keeps the
recovery secret (§8a, *Recovery*). Nothing multiplatform does its job: it is the store Android keeps
across a reinstall and hands on to the phone that replaces this one, by device-to-device transfer and,
where the backup is end-to-end encrypted, from the cloud. It stays in Android source sets and never
reaches common code: `:core:network`'s Android source set alone depends on it, for
`AndroidRecoverySecretStorage`, behind a seam (`BlockStore`) that keeps Play services out of its host
tests. Its `Task`s are awaited by hand rather than through `kotlinx-coroutines-play-services`, which
would be one more dependency for a few lines.

---

## 3. Module structure

Dependencies point **inward**. `:core` and `:core:domain` have the fewest dependencies;
nothing they depend on may depend back on them.

Module names follow the wizard's layout — `:core` rather than `:contract`, `:app:shared`
rather than `:composeApp` — because renaming would rewrite the Xcode project's framework
paths for no functional gain. Gradle nesting under `:core` is grouping only and implies no
dependency.

```
:core                DTOs, API request/response models, route + query-param constants.
                     kotlinx.serialization. PURE Kotlin. No platform code, no Ktor, no
                     domain logic. explicitApi() enforced.
                     Depended on by BOTH :server and :core:network.

:core:domain         Domain models, repository/cache ports, use cases. PURE Kotlin.
                     Depends on NOTHING else in the project — not even :core.
                     The innermost layer. explicitApi() enforced.

:core:network        Ktor client, the Json config, platform token storage, API classes,
                     the server environments (WyrEnvironment, §8e).
                     Depends on :core and :core:domain.

:core:data           Repository implementations, local cache, DTO<->domain mapping.
                     Depends on :core:domain, :core, and :core:network.

:app:shared          Compose Multiplatform UI shared across all client platforms:
                     screens, theme, ViewModels, DI wiring.
                     Depends on :core:domain, :core:data, :core:network.

:app:androidApp      Android Application/Activity, manifest, Android-only wiring, and one
                     product flavor per server environment (§8e).
:app:desktopApp      JVM main() entry point.
:app:webApp          js + wasmJs browser entry point, and the build-time environment (§8e).
app/iosApp           Xcode project consuming the Shared framework (not a Gradle module).

:app:adminApp        The moderation app (§8d, Moderation): its Compose UI, theme, ViewModel and
                     DI wiring, and its own entry points, a desktop window (jvm) and a page
                     (js + wasmJs). Depends on :core:domain, :core:data, :core:network.
                     Never on :app:shared, the game.

:server              Ktor server. Routes, auth, persistence (Exposed). Depends on :core.
```

**Entry-point rule:** `:app:shared` holds everything identical across platforms. A platform
entry point holds ONLY what cannot be expressed in common code: OS lifecycle binding, platform
permissions, framework/manifest config, and platform-specific DI wiring. If code can live in
shared, it lives in shared.

The moderation app is one module, entry points included: nothing else consumes its UI and it has no
Android or iOS build, so a shared module under platform modules would buy it nothing. The rule holds
inside it: its `jvmMain` and `webMain` hold only `main()` and the name of the environment.

**Architecture constraints (do not violate):**
- `:core` must never import anything platform-specific or any business logic.
- `:core:domain` must never depend on data, network, or any framework.
- Mapping between DTOs (`:core`) and domain models (`:core:domain`) happens in `:core:data`.
  Domain code never sees a DTO; UI never sees a DTO.
- `:server` depends on `:core` only. It must not depend on `:core:domain` — which is why
  scoring lives in `:server`, not in the shared domain (see §8c).

---

## 4. Approved libraries

All versions are pinned in `gradle/libs.versions.toml`. That catalog is the enforcement
mechanism; this table is the rationale.

| Concern            | Library                | Notes                                            |
|--------------------|------------------------|--------------------------------------------------|
| UI                 | Compose Multiplatform  | One UI for Android/iOS/desktop/web               |
| Server             | Ktor (server)          | Kotlin-native server                             |
| Rate limiting      | Ktor RateLimit plugin  | Official Ktor plugin; in memory, per instance    |
| HTTP client        | Ktor (client)          | Same family as the server                        |
| Serialization      | kotlinx.serialization  | Backbone of :core                                |
| Async              | Coroutines + Flow      | Official                                         |
| Local cache        | SQLDelight             | **Declared, not yet wired — see below**          |
| Recovery secret    | Block Store            | Android only (play-services-auth-blockstore), §2 |
| Server persistence | Exposed                | JetBrains Kotlin SQL framework, pairs with Ktor  |
| Schema migrations  | Flyway                 | Server-only Java exception (§2); runs at boot    |
| Schema diffing     | exposed-migration-jdbc | JetBrains; test scope only (drift test, drafts)  |
| Dependency inj.    | Koin                   | Pure Kotlin, no codegen, KMP standard            |
| Date/time          | kotlinx-datetime       | Replaces platform date APIs                      |
| Connection pool    | HikariCP               | Server-only Java exception (§2)                  |
| Server DB driver   | PostgreSQL JDBC        | Server-only Java exception (§2)                  |
| Local dev DB       | H2 (in-memory)         | Dev/test only. Never production                  |
| Lint               | ktlint (Gradle plugin) | Style pinned in `.editorconfig`                  |

Server database engine: **PostgreSQL** (via Exposed). Hosting: **Render** — see §8.

**SQLDelight status.** It is in the catalog and remains the intended local cache for Android,
iOS, and desktop, but it is **not wired up**. It has no wasmJs driver, and web is an in-scope
target (§1), so the cache must become a per-platform split rather than one shared
implementation. Until that lands, `QuestionCache` (a port in `:core:domain`) is satisfied by
`InMemoryQuestionCache` on every platform, so the queue does not survive an app restart.

**Exposed 1.x notes** — two breaking changes from 0.x that will bite again:
- Packages are `org.jetbrains.exposed.v1.core` / `.v1.jdbc`, not `org.jetbrains.exposed.sql`.
- `where { }` takes a receiverless lambda, so comparison operators must be imported as
  top-level functions (`import org.jetbrains.exposed.v1.core.eq`). The `SqlExpressionBuilder`
  members are deprecated **as errors**.

**Transaction isolation** — decided 2026-09-23: every server transaction runs at **READ
COMMITTED**, PostgreSQL's default, set on the pool in `DatabaseFactory.poolConfig`. Four rules
follow, and code that breaks one loses updates or shows numbers that disagree, silently rather than
failing:
- A counter is an SQL increment (`total_points = total_points + n`), never a read then a write.
  A burst on one row then just queues on its lock; `PlayerStoreTest` pins 8 at once.
- Any other read-then-write is a compare-and-set: the `UPDATE`'s `WHERE` repeats what the read
  relied on, and 0 rows updated means another transaction won (`PlayerStore.startNextCycle`,
  `ModerationStore.decide`, and a retirement or restoration, `ModerationStore.move`). Where the
  `WHERE` can hold the whole check, nothing need be read first (`SessionStore.rotate`, whose second
  racer re-checks it against the first's commit).
  Or the read takes the row lock (`SELECT ... FOR UPDATE`), so a concurrent writer waits and then
  reads the row as committed (`VoteStore.cast`, which branches on more than one outcome, and
  `SkipStore.skip`). A write that rests on a row it does not write locks that row the same way:
  every vote, skip and like first locks its question's row if servable
  (`QuestionStore.lockIfServable`), so a retirement's update waits for them to commit, and one
  waiting behind a retirement re-reads the row and finds it retired. That orders every vote, skip
  and like of one question, one at a time.
  A read of rows a racing writer is about to add has no row to lock, so it locks a parent row that
  every such writer locks first (`SubmissionStore.submit` counts an author's pending questions
  under the author's `players` row).
  The one exception is a value copied from another row, which may be a plain read where a stale
  copy is provably harmless, with the proof at the read (`VoteStore.currentCycle`: the feed moves
  the cycle on only once the answer's question is already answered or skipped in the one read).
- Uniqueness is a constraint (the `Votes`, `Skips` and `Likes` primary keys), never a prior
  `SELECT`. A violation is never caught and carried on from: PostgreSQL aborts a transaction at its
  first error. It propagates, and Exposed rolls back and reruns the whole transaction, which then
  sees the committed row (`Seed.questionsIfEmpty`, which `SeedTest` races). Two first votes, skips
  or likes of one question no longer reach their key together, since the question's lock orders
  them (above), but the key is still what holds one per player, and a violation there would take
  the same path. A plain read before such an insert only spares a certain violation, and needs no
  lock when finding the row writes nothing (a like already held).
- Numbers that must agree with one another are read in one statement, which sees one committed
  state; two statements can straddle another transaction's commit (the tally in `VoteStore`,
  `StatsStore.of`, a question's like count beside `likedByMe` in `LikeStore.likesOf`, a question's
  tally and like count in the moderator's list, `ModerationStore.questions`).

REPEATABLE_READ was dropped because it refuses the second of two concurrent writes to a row
(SQLState 40001) and Exposed makes only 3 attempts with no delay, so a burst on one row, such as
many likes paying one author, failed requests.

Adding a library = update this table AND the version catalog in the same change, and confirm
it satisfies the §2 selection rule.

---

## 5. Code conventions

- Kotlin official style. `ktlint` enforced; CI fails on violations. `.editorconfig` pins the
  style (`ktlint_official`, 120 columns) so it cannot drift between machines and CI.
- One public class/interface per file unless tightly coupled (sealed hierarchies, private
  composables belonging to one screen).
- Package by feature inside each module, not by layer.
- Explicit visibility on public API surfaces of `:core` and `:core:domain` — both enforce
  `explicitApi()`, so this is a compile error, not a review note.
- No `!!`. Handle nullability explicitly.
- Suspending functions over callbacks. Expose `Flow` for streams, not custom listeners.
- DTOs are immutable `data class`es with `@Serializable`. Domain models are separate types.
- Base package: `io.ntole.wyr`, then the module, then the feature —
  e.g. `io.ntole.wyr.core.question`.
- Never catch `CancellationException` into an error state. `runApi` in `:core:data` rethrows it
  deliberately; anything that swallows it breaks structured concurrency.
- Client failures are classified once each. `WyrHttpClient` turns only an HTTP error response
  into `ApiException` and lets everything else through untouched; `runApi` alone decides
  `NETWORK`. Wrapping earlier is what once made offline, a dead refresh and cancellation all
  `UNKNOWN`.
- Two ktlint rules are suppressed and both are documented at the suppression site: PascalCase
  `@Composable`/`@Test` function names (`.editorconfig`), and `MainViewController` on iOS.

**Wire enum rule (load-bearing — do not break):** any enum on the wire whose value set may grow
server-side MUST have an `UNKNOWN` member, every property of that type MUST declare `UNKNOWN` as
its default, and the client `Json` instance MUST set `coerceInputValues = true`. Without all
three, adding an enum value server-side makes already-installed clients fail deserialization
outright. Enums that are structurally closed (e.g. `OptionSide` — a question has exactly two
sides) are exempt and must stay closed.

A **list** of such an enum needs more, because `coerceInputValues` only coerces a property's own
value, never an element of a list: one unknown element fails the whole payload. So every list
property of a growable enum MUST be declared with a serializer that decodes an unknown element as
`UNKNOWN` (`QuestionCategoryListSerializer` in `:core`, applied with `@Serializable(with = ...)`),
and MUST default to an empty list, which the client reads as it reads `UNKNOWN`. An unknown element
becomes `UNKNOWN` rather than being dropped: the server decodes with the same serializer, and a
dropped element would let a request naming a category the server does not know through as if it
had named only the rest. `WyrJsonTest` pins it.

The client half lives in `WyrJson` (`:core:network`); the server half is `encodeDefaults = true`
in `ServerJson` (`:server`), because the client can only coerce into a default that is actually
present in the payload. Both are cross-module obligations: changing either is a contract change.

---

## 5b. Design system & theming

**Single source of truth rule:** every color, spacing, radius, and type value lives in ONE
theme definition. No hardcoded hex, dp, or sp literals in screen/component code — pull from
the theme. Changing the palette or adding a new theme must mean editing one place, never
hunting through UI files.

Implemented as `WyrTheme` in `:app:shared` (`io.ntole.wyr.theme`): `WyrColors` + `WyrDimens` +
`WyrTypeScale`, exposed through `LocalWyrColors`/`LocalWyrDimens` and read via
`WyrThemeAccessors`. The theme also mirrors its palette into a Material 3 `ColorScheme` so stock
Material components inherit it instead of falling back to Material defaults. Adding a theme =
adding another `WyrColors` value.

The moderation app has a theme of its own, `AdminTheme` in `:app:adminApp` (`io.ntole.wyr.admin.theme`),
since it may not depend on `:app:shared` (§3): Material 3's default light and dark schemes and type
scale, with `AdminDimens` and `AdminType` beside them. The same rule holds in its screens: no hex, dp
or sp literal outside that file.

**Visual direction:** playful & bold, theme-aware (full light + dark support).

**Brand option colors — constant across all modes** (these are the identity):
- `optionA` = `#D4537E` (pink), text-on = `#FFFFFF`
- `optionB` = `#EF9F27` (amber), text-on = `#412402`
- The two answer choices always use these, and side is positional — `optionA` is whichever
  option arrived in the `optionA` field. A warm/warm pairing for now; swappable later.

**Mode-dependent tokens:**

| Token            | Light      | Dark       |
|------------------|------------|------------|
| page background  | `#FFF7FA`  | `#161417`  |
| surface          | `#FFFFFF`  | `#221F23`  |
| primary text     | `#412402`  | `#F3EDEF`  |
| heading accent   | `#993556`  | `#ED93B1`  |
| OR pill text/bg  | `#993556` on `#FBEAF0` | `#F4C0D1` on `#3A2330` |
| muted (pts)      | `#888780`  | `#888780`  |

**Open check (not blocking):** verify every text/background pair meets WCAG AA contrast — the
amber block (`#412402` on `#EF9F27`) and `muted` `#888780` on both backgrounds are the ones to
confirm. Not yet done.

---

## 6. Git workflow

- Trunk-based with short-lived feature branches: `feat/...`, `fix/...`, `chore/...`.
- Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`).
- Small, focused commits. One logical change each.
- Never commit secrets, keystores, `local.properties`, or `.env`. They go in `.gitignore`.
- `main` must always build on all active targets.

---

## 7. Identity & environment isolation

This project must never be attributed to any employer identity.

- This repo uses a **personal** git identity, set locally (not the global config):
  `user.name = Nikola`, `user.email = nikola.tokicg6@gmail.com`.
- `user.useConfigOnly = true` is set locally, so git **refuses to commit** rather than silently
  falling back to the global work identity. Do not unset it.
- Remote uses a personal GitHub account, authenticated with a credential scoped to that
  account only (separate SSH key or PAT).
- Never run a company-account `gh auth` or push to a company remote from this repo.
- Verify before the first push: `git config user.email` returns the personal address.

---

## 8. Hosting & deploy

- **Host: Render.** Chosen for flat, predictable per-service pricing (not usage-metered) and
  push-to-deploy from GitHub. Declared in `render.yaml`.
- `:server` deploys as **two Render web services** from one `render.yaml`, both built from the root
  `Dockerfile`, both tracking `main`, health check `/health` (*decided 2026-09-24*):
  - **dev**, `wyr-server-dev`: `autoDeployTrigger: checksPass`, so every commit on `main` deploys
    once its CI checks pass. No database: without `DATABASE_URL` it runs on in-memory H2, so its
    data resets on every deploy, restart and free-tier spin-down (a paid Postgres for dev comes
    later). Its own `JWT_SECRET` and `ADMIN_TOKEN`, so nothing from one environment works on the
    other.
  - **prod**, `wyr-server` on `wyr-postgres`, at https://wyr-server.onrender.com: `autoDeployTrigger:
    "off"`. It deploys **only by hand**, with Render's *Manual Deploy → Deploy a specific commit*,
    and only a commit that is green in CI and already live on dev. That is the one exception to
    "never hand-deploy": promoting to production is a person's decision. First deployed 2026-09-24
    (`4cdc819`).
  - Not `commit` for either, which is what the deprecated `autoDeploy: true` means: it deploys every
    commit whether or not CI is green.
- PostgreSQL (prod only) is a **Render managed Postgres** instance in the **same region** as the web
  service (use the internal connection URL, never the external one).
- Connection string and all secrets come from Render **environment variables** — never
  committed. `ServerConfig` reads them all, with dev-only defaults, and logs a loud warning
  when running on a dev default.
- `DATABASE_URL` arrives in `postgres://` form, which JDBC rejects; `ServerConfig` translates it.
- `ADMIN_TOKEN` is the moderator's credential (§8d, *Moderation*), declared `sync: false` in
  `render.yaml` and set by hand in the dashboard, never committed. It has **no default**: unset or
  blank turns moderation off, so the admin routes are 404, every submission stays pending, and the
  boot log warns. A token shorter than 32 characters boots with a warning, since guesses are limited
  only per address (§8b), so a caller with many addresses gets many more, and one holding whitespace
  or anything but visible ASCII fails at boot, since no request header could carry it. Generate one
  with `openssl rand -hex 32`. It is read at boot, so rotating it is changing the variable and
  restarting the service, and the old token is dead from then on. A browser on an
  `ALLOWED_WEB_ORIGINS` origin may send its header (CORS). The moderation app (§8d) takes it typed
  and holds it in memory only.
- `CLIENT_IP_HEADER` (`render.yaml`: `CF-Connecting-IP`) names the request header the per-address
  rate limits (§8b) take the client's address from (`clientAddress`). Every request to a Render web
  service passes through Cloudflare, which sets `CF-Connecting-IP` to the address that reached it
  and overwrites any a client sent, so the key does not depend on how many of Render's proxies stand
  behind Cloudflare. `X-Forwarded-For` is never read, and naming it (or `Forwarded`) fails at boot:
  each proxy appends to it, its leftmost entry is the client's to write, and live Render services
  have been reported with one Render proxy behind Cloudflare and with two, so a count of entries
  from the right would let a client pick its own address whenever the count was one too high. A
  request without the header, or with more than one value, keys by the socket peer. Unset, the
  default, trusts no header and keys by the socket peer, which on a laptop is the client and on
  Render is the proxy, one budget for everyone: the server warns at boot when `RENDER` is `true` and
  no header is set. To be checked once deployed (NEXT-SESSION.md). No forwarded-header plugin:
  `ktor-server-forwarded-header` would be a new dependency for the one line this needs.
- The Docker build sets `WYR_SERVER_ONLY=1`, which makes `settings.gradle.kts` skip the app
  modules. Without it the Android Gradle plugin fails at configuration time for want of an SDK.
- Free tier caveats to design around: free web services spin down after ~15 min idle (cold
  start on next request) and share 750 instance hours a month across the workspace; only one free
  Postgres may exist per workspace; and free Postgres expires 30 days after creation, then 14 days'
  grace before Render deletes it. So `wyr-postgres` (created 2026-09-24) must move to a paid instance
  type by about 2026-10-24 to keep production's data: an in-place change of instance type, a few
  minutes unavailable.
- The paths: push to `main` → CI (ktlint + tests) → Render builds and publishes **dev**; then, by
  hand, Manual Deploy that same commit to **prod**. Never deploy prod a commit CI has not passed or
  dev has not run.

## 8a. Authentication — resolved

**Zero-click, server-issued guest sessions with a custom Kotlin implementation.** No third-party
auth SDK, satisfying §2.

- `POST /v1/auth/guest` mints the player server-side and returns a signed access JWT plus an
  opaque refresh token, and the player's recovery secret (*Recovery*, below). Nothing is asked of the
  player.
- Identity is **server-issued**, which is the whole point: a client-supplied device id would be
  forgeable and would let one device stuff the ballot.
- The access token travels in `Authorization: Bearer`, never in a request body, so `VoteRequest`
  does not change when auth evolves.
- Only a SHA-256 hash of the refresh token is stored. Refresh tokens **rotate on every use**, with a
  **grace window** (*decided 2026-09-24*) that has **no time bound** (*decided 2026-09-24*, replacing
  the 10 minutes built first). The token a rotation displaces is kept as its session's previous one
  (*Sessions*, below), with the expiry it had and when it was displaced, and a refresh presenting it
  still succeeds until the next rotation displaces it, never past the token's own expiry.
  `REFRESH_GRACE_SECONDS` can bound it in time: unset, the default, sets no bound; 0 turns the grace
  off; any other number of seconds accepts the previous token only that long after the rotation that
  displaced it. It rotates by the same rule, so the token it presented is dead afterwards and the one
  it displaced becomes the previous one; a refresh with the current token displaces the previous one
  for good the same way. A token therefore works twice at most, once while current and once more as
  the previous one; anything else is 401 `INVALID_REFRESH_TOKEN`.
  - *Why:* a refresh the server ran whose answer never arrived (a dropped connection, a Render cold
    start, the client's 5-minute refresh timeout, the app killed mid-refresh) leaves the client with
    only the token the server rotated out. Without the grace its next refresh is refused and session
    recovery replaces the player with a fresh guest, their points gone. With it, the client's next
    refresh sends that token again and keeps the player. Nothing resends a refresh that failed on the
    network: the call fails as `NETWORK`, and the token goes again only with a later call's 401, which
    for a player who put the app away comes whenever they are back. The 10-minute bound replaced
    that player all the same, which is why the user dropped it (§8b, *Refresh answers lost past the
    grace*).
  - *The cost* is a copy's. A refresh token copied to a second device, or stolen, keeps working beside
    the original once used, for as long as the two take turns refreshing, as two clients each
    refreshing once per access token do: each refresh leaves the other's token in the previous slot,
    where nothing times it out. One drops out when the other refreshes twice in a row, and it need not
    be the copy. A copy of a token its player's own refresh already rotated out still gets in once if
    it comes before their next refresh, which for a player away from the app can be as late as the
    token's own expiry. A time bound parts the two only when one waits in the previous slot longer
    than the bound: the 10 minutes did whenever one refreshed more than 10 minutes after the other,
    and a bound under half the access token's 15 minutes always would. Accepted for guest accounts,
    whose only credential this is (the user's decision, 2026-09-24); account linking changes the
    picture. `ServerConfig.refreshGraceSeconds` says so.
  - *The rotation* is one `UPDATE` of the session whose `WHERE` is the whole check, nothing read
    before it (`SessionStore.rotate`, §4): the presented hash as the current one and unexpired,
    or as the previous one, unexpired and, under a bound, displaced within it. Of two refreshes racing
    with the current token, the second waits on the first's row lock, finds the token the previous one
    by then and goes through too, the first's new token becoming the previous one; of two racing with
    the previous token, exactly one goes through. `SessionStoreTest` races both and pins every rule.
    It stamps every rotation, bound or not: a bound set later reads the stamp, and so does a rollback
    to a build from before this rule, whose unset `REFRESH_GRACE_SECONDS` means 10 minutes. A build
    from before V2 (any before `08397e4`) rotates the players row, the mirror (*Sessions*), without
    touching the previous token's columns, so after a rollback to one they can name a token displaced
    several rotations back, which with no bound works again once this rule is back, until that
    player's next refresh or its own expiry. A database V2 has only just given those columns holds
    nothing in them, so the first deploy of V2 needs nothing more. But before rolling forward again
    after such a rollback, while the old build still runs, clear them: `UPDATE players SET
    previous_refresh_token_hash = NULL, previous_refresh_token_expires_at = NULL,
    previous_refresh_token_rotated_at = NULL`. That costs only a lost answer from before the rollback
    whose player has not been back since. A bound set for the roll-forward is no substitute: a stale
    token not yet displaced works again once it is unset.
- **Sessions** (*decided 2026-09-25*): a player's refresh tokens live in `sessions`, **one
  refresh-token family per device**, each rotating on its own row by the rules above, so a refresh on
  one device never touches another's tokens, and nothing caps how many a player has. A mint opens a
  player's first session and each recovery another (`SessionStore.open`, *Recovery*, below). V4
  opened one for every player who held a refresh token, under the player's own id and with their
  previous token and its grace, so every client kept refreshing across the deploy. No row is deleted:
  an expired session is dead where it lies.
  - *The mirror.* A build from before sessions (the V2 and V3 builds among them) looks a refresh token
    up in the players row alone, so the players row keeps its refresh-token columns as a copy of the
    session opened or rotated last, previous token and stamp included, marked as this build's copy
    (`players.mirrored_refresh_token_hash`). Every open and rotation writes it, under the player's row
    lock, which a rotation takes after its session's: every write here that locks a session it did not
    insert itself locks it before its player. It costs a read and an `UPDATE` per refresh. The columns
    stay until no rollback target predates sessions; dropping them then is a migration of its own
    (§8b, *Rollbacks*).
  - *A rollback* to such a build refreshes, for each player, the device that opened or refreshed a
    session last, with the grace this build gave its token, and refuses every other device, whose
    client throws the session away and opens another. A phone that keeps a recovery secret recovers
    before it mints, and the build before answers the recovery with a bare 404, which mints nothing
    (*Recovery*): so that phone fails every call until the roll-forward, and so does a phone first
    installed meanwhile with a restored secret. Desktop, web and a phone with no secret replace their
    player with a fresh guest. The build before never reads or writes the recovery secret, so once
    rolled forward a phone that kept its secret recovers its player. The guests it mints have no
    session, no mark and no secret.
  - *Rolling forward* needs nothing by hand. A mirror whose current token is not its mark was moved
    by a build without sessions: during a rollback, or while the build before still serves as a
    deploy's new instance starts, which the first deploy of V4 goes through too. A refresh no
    session takes is tried there by the same rules and, spent, folded back into the session the mark
    names, the device the build without sessions went on refreshing, or into a new session for a
    guest minted there (`SessionStore.foldMirror`). The mark is read first so that session is locked
    before the player, and the player's update is a compare-and-set on it (§4). Two refreshes racing
    with such a token behave as two with a session's token. Until the fold, the session the mark
    names spends none of its own tokens (`notMovedOnWithoutSessions`, in the rotation's `UPDATE`):
    the device went on with the mirror's, and by that build's rules the session's were displaced, so
    a stale copy of one stays dead rather than work a third time and rewrite the mirror over the
    device's token. A device whose answer from that build was lost still holds the session's current
    token, which is the mirror's previous one, and the fold spends it there, under the grace this
    build gives it. `SessionStoreTest` pins the mirror, a build without sessions refreshing from it,
    both folds and their races.
  - *What a rollback still costs:* every device but the one used last, as above: a phone keeping a
    secret cannot play until the roll-forward, and any other device loses its player to a fresh
    guest. And, for a player with more than one session, the chain a build without sessions moved in
    the mirror, if another of their sessions refreshes first once rolled forward, since a session's
    rotation always rewrites the mirror: that device is refused, and a phone recovers with its
    secret, any other device minting a guest. A rollback past V2 still needs the mirror's previous
    token cleared before rolling forward (*The rotation*, above).
- **Recovery** (*decided 2026-09-25*, phase 1 of making a guest durable, §8b *Provider linking*): a
  guest's account survives a reinstall and a phone restore with no click. The mint answers a
  `GuestSessionDto`, the session's fields with the player's **recovery secret** beside them: 256
  random bits in base64url, kept as a refresh token is, by its SHA-256 alone
  (`players.recovery_secret_hash`, unique), and carried by no other answer, since a refresh and a
  recovery answer a plain `SessionDto`. A client from before recovery reads the mint as the session
  it always did.
  - `POST /v1/auth/recover` with a `RecoverRequest` opens a new session for the secret's player
    (`SessionStore.recover`), answered with that `SessionDto` alone; every other session of the player
    lives on. A recovery does **not rotate the secret**, since the copy a restored phone holds may lag:
    it recovers again as often as it is presented, and **whoever holds it owns the account until it is
    replaced** (the user's decision). A secret no player holds, never issued or replaced since, is 401
    `INVALID_RECOVERY_SECRET`, alike for every one and never 404; a malformed body or a blank secret is
    400 `VALIDATION_FAILED`. It is looked up by its hash through the unique index, as a refresh token is.
  - `POST /v1/me/recovery-secret` (bearer) issues the session player a new one, as a
    `RecoverySecretDto`, and kills the one before (`PlayerStore.replaceRecoverySecret`); the sessions
    that one opened live on. For a guest from before V4, who has none, and for a client that could not
    keep the one it was given. 401 `UNAUTHORIZED` without a session or for a player the server no
    longer has.
  - Both are rate-limited (§8b), and neither the secret nor its hash is ever logged: the refusal's
    line names the limit and a client address, and `RateLimitTest` scans a whole flow's log for both.
    Nor does a `toString` show it: the three DTOs that carry it say only whether it is there
    (`WyrJsonTest`). Each environment's server keeps secrets of its own (§8e): a DEV secret recovers
    nobody on PROD, and DEV's in-memory database forgets every secret at each restart, where a client
    meets `INVALID_RECOVERY_SECRET`. `RecoveryFlowTest` pins the routes and `SessionStoreTest` the
    store.
  - *The client* (`DefaultSessionRepository`, built): a device with no session recovers with the
    secret it keeps before it mints a guest, and so does a dead session before `withSessionRecovery`
    opens another in its place, which makes a dead session cost nothing where a secret is kept. A
    secret the server does not know (`INVALID_RECOVERY_SECRET`: DEV after a restart, say) is dropped,
    and a guest minted, whose secret is kept before its session. Any other failure of a recovery mints
    nothing and fails the call, and the next call tries again: offline, a 5xx, a 429, or the 404 of a
    build from before recovery, since a mint would keep its own secret in place of the one that
    recovers the account. A store that cannot be read counts as empty there, so a phone without Play
    services plays on as a guest; but that guest's secret is not kept, since the store may hold one
    it failed to read only for a moment, as a restored phone's may while Play services starts, and
    the guest's would replace it for good. A later launch asks for the guest's secret only if the
    store then reads as empty (below).
  - *A session with no secret kept* (a guest from before recovery, a secret the store could not
    keep, or a guest minted while it could not be read) asks `POST /v1/me/recovery-secret` for one,
    once a launch, and only while the store holds none and can be read: a new secret kills the one
    before, wherever it is kept. So a secret held for another player is left alone, since on iOS it
    is the account this person's other iPhones share; this device's guest then stays bound to it,
    and recovers as that account should its session die. A request that fails is made again at the
    next launch, whatever failed it: offline, a 5xx, a 429, or the bare 404 of a server without
    recovery, as production answers until it is promoted, so a guest from before V4 gets its secret
    once the server can give one. A secret the store could not keep counts, and after three
    (`MAX_FAILED_SECRET_REQUESTS`) the install asks no more for that player. The count is kept
    beside the session, in the token storage (`RecoverySecretStore`), so it goes where the session
    goes and never with the secret, and a reinstall starts it again.
  - `clear()`, the console's *New guest*, drops the secret with the session, since it would otherwise
    recover the player being cleared away, and fails as NETWORK when it cannot, unless the store
    cannot read the secret back either, as without Play services, where nothing could recover with
    it. `RecoverySecretFlowTest` pins every case.
  - *The console* shows whether a secret is kept (`SessionDiagnostics.recoverySecret`: kept, none,
    unreadable, or not kept on this platform), never the secret, and *Reinstall (keep secret)* does
    what deleting the app and installing it again does: `clearKeepingSecret()` drops the session and
    the count of failed requests, keeps the secret, and the next session opened recovers the player.
    Its log line ends `recovered` when the player came back, and `was=` the one before when not, as on
    desktop and web, where it is *New guest*.
  - *Where it is kept* (*decided 2026-09-25*), bound in each platform's `platformModule`:
    - *Android* (built): Block Store (§2, `AndroidRecoverySecretStorage` in `:core:network`), which
      keeps its entries across the app being uninstalled and installed again only while Google's
      Backup services are on (Settings > Google > Backup, per Google's Block Store guide), and moves
      them to a new phone set up from this one by device-to-device transfer. Its cloud copy is asked
      for only while the backup is end-to-end encrypted (`isEndToEndEncryptionAvailable`; a phone
      that cannot say counts as not), so a phone restored from any other cloud backup mints a guest.
      Block Store decides that at each store, so a secret stored while the backup was not encrypted
      (before a screen lock was set, say) is stored again into it by the first launch that finds it
      encrypted (`backUp`), and never stored again out of it, which would delete the cloud's copy at
      the next sync. Without Play services every call throws, and the phone plays as a guest. The
      session store stays out of both the cloud backup and a device-to-device transfer, so a new
      phone gets only the secret, and recovers with it into a session of its own:
      `data_extraction_rules.xml` (Android 12 and later) and `backup_rules.xml`
      (`fullBackupContent`, Android 11 and earlier, for both) in `:app:androidApp` exclude
      `AndroidTokenStorage`'s `wyr.auth.xml`, which holds the session and the count of failed
      requests for a secret. `allowBackup` stays on, with nothing else in it yet.
    - *iOS* (built, compiled only: §9): a generic-password Keychain item per environment
      (`IosRecoverySecretStorage` in `:core:network`; service `io.ntole.wyr.recovery`, the key as its
      account), synced through iCloud Keychain (`kSecAttrSynchronizable`), so one person's iPhones
      share one account, each with a session of its own. It is readable once the phone has been
      unlocked after starting (`kSecAttrAccessibleAfterFirstUnlock`, never a `ThisDeviceOnly` class,
      which would not sync), and the Keychain keeps it when the app is deleted. No test runs it: the
      simulator's test binary is no signed app, so the app on a phone is the first to call it. The
      one item holds one secret, whoever's: an iPhone that opens the app before the item has synced
      to it (a new iPhone opened before iCloud Keychain catches up, or iCloud Keychain off and turned
      on later) reads none, mints a guest and writes the guest's secret into the same item, and once
      the two meet iCloud Keychain keeps one of them on every iPhone. Should it keep the guest's, the
      other iPhone's player has no secret left anywhere: that iPhone plays on as its player, holding
      the guest's secret, which it never replaces since the store holds one, and a reinstall or a
      dead session there turns it into the guest. An item per player would avoid it, a change of
      design not yet made.
    - *Desktop and web* keep none: they bind no `RecoverySecretStorage` (`dataModule`), so they mint as
      before and never ask for a secret.
- Provider linking (Play Games Services on Android, Game Center on iOS) is phase 2 (§8b, *Provider
  linking*); recovery alone already carries a phone's guest across a reinstall.
- On the client, `SessionStore` is the only copy of the credentials: Ktor's bearer cache is off
  (`cacheTokens = false`), so a session change applies to the very next request. A dead session
  is replaced through `withSessionRecovery` in `:core:data`, which opens at most one session for it:
  its own player's, through the recovery secret, where the device keeps one (*Recovery*), and else a
  fresh guest's.
- *Clients sharing one store* (browser tabs, desktop instances) can both refresh one token, and the
  server lets both through, so the store may end up holding the displaced token of the two. The
  server takes that one once more, but a previous token survives only one refresh: the clients share
  an access token too, so they may well refresh at once again, and of two refreshes with the previous
  token one is refused, which replaces the player if it comes before the other's answer is stored
  (`DefaultSessionRepository.resetIfStill`); under a time bound it also dies with the bound. So a
  refresh that finds the store moved on to another session of the same player refreshes once more as
  the stored one, which the server takes either way, and keeps that answer, the latest rotation
  (`refreshAs` in `WyrHttpClient`, `SharedSessionStoreTest`); the once more is never repeated. A
  session of another player, or none, is the data layer's change and is kept as it was. A settling
  refresh that reached the server but whose answer was lost leaves the store holding the other
  client's session beside a fresh access token. If that session's token was the current one, the
  settling refresh made it the previous one, which the next refresh can still spend; if it was
  already the previous one, the usual case, the settling refresh spent it, and the next refresh, once
  that access token expires (15 minutes by default), is refused (§8b, *Refresh answers lost past the
  grace*). The lost-answer case needed no client change, bound or none: the store still holds the
  token it sent, and the next 401 sends it again. Nor did session recovery: it acts only on a
  refusal, which the grace makes rarer.
- Every client request is bounded (`HttpTimeout` in `WyrHttpClient`: 60 s), past a Render cold
  start. Android and desktop also give up on a connect after 30 s; iOS applies only the 60 s socket
  timeout, which covers connecting, and a browser only the request timeout. A request that spends
  the refresh token takes `refreshTimeout()` instead, 5 minutes: a timeout abandons a refresh as a
  cancellation does, and with it a token the server has already rotated, which the next call's
  refresh can still send once more (the grace, above) unless it was already the previous one; a time
  bound on the grace must outlast the 5 minutes for that. It stays bounded because every call
  rejected meanwhile waits on it, uncancellably.
- `TokenStorage.write` returns only once the session would survive the app being killed, for the
  same reason: after a refresh, the rotated token is the one sure to work, the token sent working
  once more at most, and not at all if it was already the previous one. It suspends so a blocking
  write can leave the caller's thread (Android `commit()`s on `Dispatchers.IO`, one change at a time
  and in the order asked, never `apply()`s), and a write asked for lands even if its caller is
  cancelled meanwhile. A write that cannot be made durable fails the call as `NETWORK`: the data
  layer writes the session through `runApi`, so no bare storage exception reaches a ViewModel.

**Known limitation, by design for now:** a guest account lives only while some store holds a live
session of it or its recovery secret. An Android or iOS guest survives a reinstall and a move to a
new phone through the secret (*Recovery*), none of which has yet run on a phone (NEXT-SESSION.md);
one whose every copy of the secret is lost is gone all the same, as is an Android guest reinstalled
with Google's Backup services off, where Block Store keeps nothing across it. A phone whose secret
store could not be read when the app first started there plays as a new guest, and recovers the
player only once that guest's session dies or the app is installed again, which leaves the guest
behind. An iPhone that mints before the Keychain item has synced to it can take another iPhone's
secret from it for good (*Where it is kept*, iOS). A desktop or web guest stays bound to its one
storage: lose it and the account — and its points — are gone. A copy of the secret in the wrong
hands owns the account until it is replaced. Session storage is ordinary preference storage
(SharedPreferences / NSUserDefaults / JVM Preferences / localStorage), not Keychain or
EncryptedSharedPreferences. All of it must be revisited before real accounts exist.

## 8b. Open decisions (resolve before relevant work)

- **Provider linking** — *decided 2026-09-25: two phases.* Phase 1, free and zero-click, is the
  recovery secret (§8a, *Recovery*): built, on the server and in the Android and iOS clients, and
  yet to run on a phone. Phase 2, next, is a silent Play Games Services v2 link on Android, and Game
  Center on iOS later; either needs OAuth client credentials, and Apple a paid developer account
  too. Passkeys have no desktop-JVM story, so desktop would need a browser handoff. The decisions of
  2026-09-25, every one the research's default: phase 1 now and the store providers' links later; a
  long-lived recovery secret, whose holder owns the account until it is replaced; a sessions table
  first, one refresh-token family per device, with no cap per player (§8a, *Sessions*); the session
  store out of Android's cloud backup and its device-to-device transfer, so only the secret moves
  between phones; Block Store's cloud copy only where end-to-end encryption is available, else a
  same-device reinstall only, and that only with Google's Backup services on (§8a); the iOS Keychain
  item synced through iCloud Keychain; desktop and web guest-only; and Block Store approved as a
  library exception (§2).
- **SQLDelight cache** — see §4. Needs a per-platform split because of web. Lower priority now
  that the endless feed (§8d) makes the server the source of truth for what a player has answered:
  the client keeps no record of what it served, so a persisted queue would only save one fetch
  after a restart.
- **Question submission + moderation** — the game rules are settled in §8d, and so is the
  moderator model (an admin token). The submission contract is settled and built on the server:
  `SubmitQuestionRequest`, `SubmissionDto`, `SubmissionListDto` and `QuestionStatus` in `:core`.
  No author travels on the wire: a submission's author is whoever its bearer token names, and a
  player lists only their own. The client and the console submit and list them. The moderation
  contract is settled and built on the server too: the admin routes, `ApproveSubmissionRequest`,
  `RejectSubmissionRequest`, the question list's `AdminQuestionPageDto` and `AdminQuestionDto`
  (paged by `WyrApi.Query.CURSOR`), `RetireQuestionRequest` and `RestoreQuestionRequest`, the
  `X-Admin-Token` header (`WyrApi.Headers`), `QuestionStatus.RETIRED` and the error codes `FORBIDDEN`,
  `ALREADY_DECIDED` and `WRONG_STATUS`. The moderator's client (`ModerationApi` calls every admin
  route) and the moderation app are built on it (§8d, *Moderation*).
- **A rejection reason is one line** — *provisional — user decision.* §8d asks for a short reason;
  the server also holds it to one line, as it does an option: no control character, nor U+2028 or
  U+2029 (`checkedRejection`). Chosen as the stricter reading, since a reason is shown to its author
  as a line of text; allowing line breaks later breaks no client. The options: keep it, or allow
  line breaks in a reason.
- **Retiring a question** — *provisional — user decision.* The user asked for a way to take an
  approved question out of play and put it back (§8d, *Moderation*); the details are this build's.
  Built: a moderator retires an approved question, a seed included, and restores a retired one.
  Retired, it is served to nobody and due for nobody, and a vote, skip, like or unlike of it is 404;
  nothing it earned is taken back, so its answers' points stay and its likes stay held and paid, and
  nobody can unlike it until it is restored. Restored, it is due for every player who has not
  answered or skipped it in their current cycle. It is stored as `questions.retired_at` beside an
  `APPROVED` status, and sent as `QuestionStatus.RETIRED`, so a rollback to the build before (§8b,
  *Rollbacks*) reads every row and only serves retired questions again. Every vote, skip and like
  locks its question's row (`QuestionStore.lockIfServable`, §4), so nothing lands on a question
  after its retirement commits, at the cost of one question's votes, skips and likes queueing one at
  a time. Each waits holding one of the pool's 5 connections (2 on H2), so as many writes to one
  question at once hold every connection and delay every other request, the feed, stats and
  refreshes included, for as long as the queue takes, up to Hikari's 30 s wait for a connection; a
  question is answerable by id whether served or not, so a few scripted guests within their 120
  votes a minute can do that on purpose. The options: keep it; let an unlike through on a retired
  question, so a player can still take a like back (and its author's point with it); take back what
  a retired question earned (every total would move, and §8c's sum would need the retired questions
  left out on both sides); a plain servability read instead of the lock, so writes to one question
  never queue, where a vote in flight as the retirement commits may still land, and the retirement's
  answer not count it; a shared lock on PostgreSQL (`FOR SHARE`), which lets writes to one question
  run side by side and still holds a retirement back, but needs SQL per engine, since H2 refuses it;
  or `RETIRED` stored as a status of its own once no build before this one is a rollback target,
  which drops the column.
- **Skips under a category filter** — *provisional — user decision.* A request filtered to one
  or more categories with nothing due in any of them, while other questions still are, serves
  those categories again (§8d, *Categories*), and that includes questions skipped this cycle, which
  §8d, *Skipping*, says come back only in the next one. Built that way because it is the Categories
  rule as written, which predates recorded skips, and it changes neither rule; a filter of several
  categories carries it over unchanged. The options: keep it; serve again only the filter's
  answered questions, and its skipped ones only when it has nothing else; or answer an empty
  batch, which the client reads as out of questions. The dev console's Category row sends the
  categories selected, so this is reachable from there. `SkipStoreTest` pins what is built.
- **Retrying a submission** — *decided 2026-09-24: keep it simple.* A submission carries no
  attempt id, so one sent again after its response was lost is stored twice, both pending; the
  moderator rejects the copy, and the 20-pending cap bounds how many there can be. Nothing resends
  a submission today (`withSessionRecovery` retries only after a 401, which stored nothing). An
  optional `attemptId` with a default can be added later without breaking a client.
- **Rate limiting** *(built)* — Ktor's RateLimit plugin (`installRateLimits`, in
  `io.ntole.wyr.server.plugins`), with a budget for each group of routes (`RouteLimit`), spent apart
  from every other group's. The budgets are `RateLimits.DEFAULT`, each count overridable by its
  `RATE_LIMIT_*` variable (`RateLimits.fromEnvironment`; one that is not a whole number of at least 1
  fails at boot, naming it). Each is a fixed window (`RequestBudget`): it starts at a key's first
  request and refills whole when its period ends, so up to twice a budget can pass in moments where
  one window ends and the next begins, while over any longer span the average holds:
  - *Per client address* (on Render, Cloudflare's `CF-Connecting-IP`: `CLIENT_IP_HEADER`, §8), for
    a caller with no session to name: guest minting 10 an hour, refreshes 30 a minute, recoveries 10
    an hour, right secret or wrong (a restored phone recovers once; no guess at 256 bits comes near,
    so the budget is what bounds the sessions one secret's holder can open), the admin
    routes 60 a minute together, and on top of that, admin requests with a wrong or missing token 10
    a minute. A request with the right token spends none of that
    last budget, but once an address has spent it, every admin request from the address is refused
    until the budget is back, the right token's too (`LockingOut`): were that one let in, its 200
    among the 429s would give it away, and guessing would be bounded by nothing. So a guesser
    behind the moderator's address can lock the moderator out, a minute at a time. It is asked
    first, so guesses refused by it spend none of the moderator's 60.
  - *Per player*, so players behind one address do not share a budget: the feed, votes and skips
    120 a minute each (the console's *Answer N* sends at most 50 votes in a row), likes 60 a minute,
    submissions 30 an hour (the 20-pending cap still applies), `GET /v1/me` and
    `GET /v1/me/questions` 120 a minute each, and a new recovery secret 10 an hour (a client asks only
    when it holds none it trusts). The key is the player id in the bearer token, which the
    limiter verifies itself (`verifiedPlayerId`): it runs before authentication, so no principal is
    there yet. A request without a token this server signed spends its address's budget of the group
    instead, and then gets its 401, so a forged token naming a player cannot spend that player's
    budget. A token that has only expired, as every player's does in its turn, still names its
    player for a refresh token's lifetime (`TokenService.expiredTokenVerifier`), so the request that
    finds it expired spends that player's budget and reaches its 401, which is what the client
    refreshes on; keyed by address, it would be answered 429 once the address's budget was spent,
    and the client would not refresh.
  - `/health` is in no group, so Render's checks are never refused.

  The limiter runs before anything else of the route, so a refused request never reaches the
  database and rotates no refresh token. It answers 429 `RATE_LIMITED` (the `ErrorDto`, from
  `StatusPages`) with `Retry-After` in whole seconds, at least 1, and logs one INFO line naming the
  limit and, for a player, their id; never a token or any header's value. `RateLimitTest` pins every
  group, the keys and the ordering. On the client a 429 is `DomainError.RATE_LIMITED`, and the wait
  its `Retry-After` names travels with it, as `ApiException.retryAfter` and then
  `WyrException.retryAfter` (whole seconds only; an HTTP date or none is null), so a screen can say
  how long to wait without reading the diagnostic message. CORS exposes the header
  (`Access-Control-Expose-Headers`), without which a browser page's script could never read it
  (`CorsTest`). Nothing retries it (`withSessionRecovery` retries only after a 401), and the console
  logs it as an `err` entry.

  What remains: farming is bounded, not gone. A player can still earn 120 points a minute by
  re-answering, on average, and up to 240 where two windows meet (*decided 2026-09-23:* a re-answer
  keeps paying every time, inside its cycle or not), and a script gets 10 fresh guests an hour per
  address, 20 where two windows meet, each with budgets of its own, so liking one author's questions
  is bounded per address and per hour, not per author (*Likes from fresh guests*, below:
  accepted). Counts are in memory and per instance: right for the one Render instance, but a second
  would grant every budget again, so running two needs a shared store first (Render Key Value, say).
  A restart, which a deploy or a free instance's spin-down is, resets them.
- **Likes from fresh guests** — *decided 2026-09-24: no like limitations.* A like pays its author
  once per player (§8d, *Likes*), and guests cost nothing to mint (§8a), so a script minting guests
  could pay one author a point per guest for each of their questions. The user accepted that: every
  like held pays, whoever holds it, and the only bound is guest minting's per-address budget.
- **Refresh answers lost past the grace** — *decided 2026-09-24: no time bound.* The grace (§8a) had
  a 10-minute bound, and nothing resends a refresh that failed on the network, so a player whose
  refresh answer was lost and who came back later (the app backgrounded after a refresh timed out,
  or killed mid-refresh) became a fresh guest. The user dropped the bound: a displaced token now
  works until the next rotation, short of its own expiry, and `REFRESH_GRACE_SECONDS` still sets one
  where it is wanted. That also saves the rarer cases that left the stored token the previous one
  beside a fresh access token, refreshed only once that expired, past the 10 minutes: a refresh
  abandoned at its timeout that the server ran only after the next one, and a settling refresh
  (`refreshAs`) whose answer was lost when the stored token was the current one. What remains is a
  refresh that spends the previous token and whose answer is lost too, which leaves the client a
  spent token, refused at its next refresh: two lost answers in a row, or a settling refresh whose
  answer is lost when the stored token was already the previous one, its usual case (§8a, *Clients
  sharing one store*). What it costs is §8a's *The cost*.
- **WCAG AA contrast audit** — see §5b. Paused along with UI polish (§8d).

`RANDOM` was an open item and is resolved: it is a content category (the absurd questions), not a
"surprise me" filter, and it stays in `QuestionCategory` as-is. The unfiltered feed already mixes
every category.

Isolation for hot counters was an open item and is resolved: transactions run at READ COMMITTED,
under the rules in §4.

Schema migrations were an open item and are resolved (*decided 2026-09-24*, replacing the interim
policy of shipping a schema change as a fresh database, which ended with the first deploy):
**Flyway** runs the scripts in
`server/src/main/resources/db/migration` at every boot, through the server's own pool and before the
seed (`Migrations`, `DatabaseFactory.init`). Nothing builds a table any other way in production;
`SchemaUtils.create` is left to the store tests.
- *One set of scripts* serves H2 and PostgreSQL. Identifiers are unquoted and in lower case, so each
  engine folds them as Exposed's own statements for it do. Should a change ever need different SQL
  per engine, split the location by vendor (`classpath:db/migration/{vendor}`) then, not before.
- *The baseline.* Every server before this one built its database with
  `SchemaUtils.create(*appTables)`, which keeps no history. V1 is the statements
  `SchemaUtils.createStatements(*appTables)` generates for PostgreSQL from the same definitions, only
  whitespace added, and H2's differ from them only in case. So a database built before migrations
  already holds exactly V1, and it is recorded at V1 without running it: its history reads
  `1 BASELINE`, where an empty database runs V1 and reads `1 SQL`. The Render production database
  is the first kind: `4cdc819` built it on 2026-09-24, the first deploy, and `Tables.kt` changed no
  column, key or index between then and V1, so it holds exactly V1, and the first migrating build to
  boot on it records `1 BASELINE`. §8 records no such deploy yet, so its next Manual Deploy may
  baseline it and run V2 (the refresh-token grace window, §8a), V3 (a question's `retired_at`, §8d
  *Moderation*) and V4 (sessions and the recovery secret, §8a) in one boot, or run what it lacks on an
  earlier baseline, V2 or V3; either way its history then reads `1 BASELINE`, `2 SQL`, `3 SQL`,
  `4 SQL`.
  `Migrations.migrate` takes the baseline itself (`baselineVersion` 1), and only for a database
  holding every table V1 builds (`TABLES_BEFORE_MIGRATIONS`) and no history table; Flyway's
  `baselineOnMigrate` is off. Any other database with tables and no history fails the boot, rather
  than being recorded at V1 whatever it holds. `MigrationsTest` pins all three, the data kept through
  every later script, and the paths production can take (recorded at V1 by the boot that runs the
  later scripts, or by an earlier boot, then migrated by a later build, or each later script run by
  the boot of the build that brought it). `SchemaDriftTest` pinned,
  while V1 was the only script, that V1 builds exactly what `SchemaUtils.create` built, every table,
  column, key, index and constraint name included, on H2 and on PostgreSQL.
- *Before a migrating build first boots on any other database it did not build*, compare that
  database's schema with one this build migrated, read-only (`pg_dump --schema-only` of each, then a
  diff); the production database needs no such check, being provably V1 (above). The baseline checks
  only that V1's tables are there, and `SchemaUtils.create` never added a column or
  an index to a table that already existed, so a database an older build first built could lack one
  and still be recorded at V1, to fail only once a later script or query needs it. And never let
  `WYR_TEST_JDBC_URL` name the production database: the test suite and `:server:pendingMigration`
  both wipe the database it names with Flyway's clean.
- *The drift test* is what stops a forgotten migration. The store tests build their tables straight
  from the definitions in `Tables.kt`, so a definition changed without a script passes them and fails
  only on the live database. `SchemaDriftTest` migrates an empty database and fails on anything
  exposed-migration (`MigrationUtils`, JetBrains' `exposed-migration-jdbc`, test scope only) would
  still change to match `appTables`, and on any difference from the schema `SchemaUtils.create`
  builds, names included, which exposed-migration does not compare. On H2 it leaves out the drops
  exposed-migration drafts for the indexes H2 makes by itself for foreign keys.
- *Adding a migration.* Change the definition in `Tables.kt`, then run
  `./gradlew :server:pendingMigration`: it migrates an empty H2 database, and the one
  `WYR_TEST_JDBC_URL` names if set, which it wipes, and prints what exposed-migration would still run
  on each to match the definitions. That is a draft. Without `WYR_TEST_JDBC_URL`, as on this machine,
  which has no PostgreSQL, it is H2's alone, in H2's names and types (a binary column is H2's
  `VARBINARY`, which PostgreSQL refuses; its own is `bytea`), so what PostgreSQL needs differently
  shows first in the server-postgres CI job. Write the draft as the next `V<n>__<what_it_does>.sql`
  beside V1, in lower case and unquoted so it runs on both engines, one `ALTER TABLE` per column
  (H2 takes no list of `ADD` clauses, as PostgreSQL does), and make it right for the rows already
  there: a new NOT NULL column needs a default or a backfill, and a drop takes its data with it.
  `SchemaDriftTest` then holds the script to the definitions on H2 and, in CI, on PostgreSQL. In
  `MigrationsTest`, add the script's row to `BASELINED_HISTORY` and what it does to rows already
  there to `afterLaterScripts` (a column added empty goes in `ADDED_COLUMNS`). Its database built
  before migrations is V1 run alone with the history dropped (V2 moved it there), since the
  definitions describe the latest script, and it reads rows with `SELECT *`, from the tables there
  are, since the definitions name columns and a table V1 lacks. It writes that database's rows
  through the stores only where what they run there names V1's columns alone: a `selectAll` of a
  table a later script changed fails on V1 (V3 moved the seed's check to the id alone), and a mint
  now opens a session, in a table V1 lacks, so its player is inserted as a build before sessions
  minted one (`playerAsMintedBefore`, V4).
- *A script that has shipped never changes*: Flyway refuses to boot on a changed checksum. A script
  whose name Flyway cannot read fails the boot rather than being skipped (`validateMigrationNaming`),
  and clean is refused outright (`cleanDisabled`); the test harness alone turns it on, to wipe the
  external test database, history included (`ExternalTestDatabase.clean`).
- *Rollbacks.* An older build boots on a database a newer one migrated: Flyway ignores a script it
  has no copy of (`MigrationConfigurationTest`). So every migration must leave a schema the build
  before it can still run on: add before use, drop only once no build a rollback could return to
  reads it. A new `QuestionStatus` is a migration too, although no column changes:
  `questions.status` is read strictly, unlike a category, so no build may write a new status until
  the build a rollback would return to can read it. That is why V3 keeps a retired question
  `APPROVED` beside `questions.retired_at` rather than giving it a status of its own: a build before
  it reads every row and only serves retired questions again (§8d, *Retiring*). Moving what a table
  holds is one as well: V4 moved refresh tokens into `sessions` and keeps the `players` columns every
  build before it refreshes from, V3's included, as a mirror of the session written last (§8a,
  *Sessions*), so a rollback keeps each player's latest device, and rolling forward folds back
  whatever the older build wrote there.
- *Several instances booting at once* (Render starts a deploy's new instance before it stops the old
  one): on PostgreSQL each script runs under Flyway's advisory lock, so one boot migrates while the
  rest wait, up to 50 tries a second apart, and then find nothing to do. Every boot that finds a
  database built before migrations baselines it, and Flyway accepts a second baseline that finds the
  marker written. `baselineOnMigrate` stays off because it is not safe here: it asks whether the
  history exists and then whether the schema is empty with no lock held, so a boot that migrated an
  empty database between another's two questions sent that one to the baseline, to fail on the
  history the first had written, on PostgreSQL as on H2. `Migrations.migrate` reads every table in
  one statement instead, so a boot that sees V1's tables sees the history written before them.
  `MigrationsTest` boots four at once on PostgreSQL, and on H2 runs a second boot whole at each
  point where the first reads the schema outside Flyway's lock (`InterleavingDataSource`). The four
  at once leave H2 out: its DDL commits as it goes, which releases Flyway's lock there mid-script,
  and it never needs the lock, since the server's H2 is in memory and belongs to one process. The
  seed runs once the migration has committed, and tolerates a racing boot by itself: the second
  insert fails on the key and Exposed's rerun finds the seeds (`SeedTest` stages it on H2).

## 8c. Scoring rules — flat

Server-authoritative, in `io.ntole.wyr.server.vote.Scoring`. The client displays what the server
returns and never recomputes points, so the two cannot disagree.

- Every answer earns `Scoring.POINTS_PER_ANSWER`, which is **1 point**, whichever side it picks
  (§8d). There is no majority bonus and no streak.
- Every like a question holds earns its author `Scoring.POINTS_PER_LIKE`, which is **1 point**,
  their own likes included, paid when the like is added and taken back when it is removed (§8d,
  *Likes*). A seed has no author and pays nobody. So a player's total is always what their answers
  earned plus a point for each like their questions hold, which `GET /v1/me` reports in one read.
  Retiring a question takes nothing back (§8d, *Moderation*): its answers' points stay, and so do
  its likes, held and paid and counted in its author's likes received, so the sum holds over every
  question, retired or not.
- `PlayerStore.addPoints` adds in SQL (`total_points = total_points + n`), never as a read then a
  write, so two votes by one player landing together cannot lose a point, nor a burst of likes for
  one author.
- The reveal's "with the crowd" verdict is `VoteOutcome.agreedWithMajority` on the client (an
  exact tie counts as agreeing). It is display only: no points depend on it, so the server keeps
  no copy of the rule.

Deliberately lives in `:server` and not `:core:domain`, so `:server` needs no dependency on the
client's domain module and the §3 graph stays intact.

## 8d. Game mechanics — decided 2026-09-23

Settled with the user. Each rule says whether it is built. The branch that builds a rule updates
it here (and §8c, for scoring) in the same commit.

**Principle.** This is not a guess-the-majority game. Rewarding majority picks teaches players to
answer what they think is popular instead of what they actually prefer. A mode that explicitly
rewards reading the crowd may come later as a separate, opt-in mode, never as the default.

**Current focus.** UI polish is paused. Functionality ships behind a plain engineering dev
console, the default root screen of a LOCAL or DEV build, where `PlayScreen` stays as a frozen second
tab; a PROD build shows `PlayScreen` alone (§8e).
The console is built, in `io.ntole.wyr.dev` (`:app:shared`). A feature adds its section there,
and every request shows in its HTTP trace (`HttpTrace` in `:core:network`, never headers or bodies).

- **Scoring** *(built; see §8c)*: every answer earns exactly **1 point**, whichever side
  it picks. There is no majority bonus and no streak: the streak is removed from the server, the
  contract, and the domain. The reveal still shows the split and whether the player sided with
  the majority, as information only.
- **Endless feed** *(built; cycles decided 2026-09-23)*: the game never ends, and it runs in
  **cycles**. Every question comes back **exactly once per cycle**, and every cycle is a **new
  random order**; this replaced looping least-recently-answered first. A question is *due* while
  the player has neither answered nor skipped it in their current cycle, and a batch holds only
  due questions, never topped up with ones done this cycle. Once nothing is due the cycle is
  finished: the next request starts the next cycle and serves the whole pool again, so a batch is
  never empty while the pool is not. `GET /v1/questions` requires a bearer token and is per-player.
  Built in `QuestionStore.feed` (each batch chosen in one statement, its categories read in one
  more), on `players.current_cycle` and `votes.answered_in_cycle`. Starting a cycle is a
  compare-and-set on the cycle read (`PlayerStore.startNextCycle`), so two requests that both find
  it finished start it once. The second can still serve again a question answered in the new cycle
  meanwhile (the feed's KDoc has the case), but only two overlapping requests from one player get
  there, and the client sends one at a time.
  `DefaultQuestionRepository` keeps no record of what it served beyond one refill: it drops only
  questions still queued and those handed out since the refill went out, the one then on screen
  included.
  - *Categories:* a cycle is **per player, not per category**. The categories a request is
    filtered to are one pool, the questions filed under any of them: while anything in it is due,
    only what is due is served. A request whose pool has nothing due, while other questions still
    are, serves the pool's questions again, in random order and `answeredBefore` on those answered,
    and leaves the cycle alone: starting the next one would cut short the player's pass over the
    rest. The cycle starts only once nothing at all is due, whichever categories are asked for, and
    a request that finds no questions starts nothing. Questions skipped this cycle are served again
    this way too, so a skip does not hold through a category filter: *provisional — user decision*
    (§8b). The client selects any number (`QuestionRepository.setCategories`, from the console's
    Category row), none for every category; a change drops the queue, and New guest keeps the
    selection.
  - `answeredBefore` means the player has a vote on the question, from any cycle.
- **Categories** *(decided 2026-09-24; built)*: a
  question is filed under **any number of categories, at least one**. A player may pick **several**
  categories to play, and a question matches when it is filed under **any** of them; none picked
  means every category. The author picks one or more when submitting, and the moderator may change
  them (*Moderation*). Built in `question_categories`, one row per question and category, written
  in the question's own transaction: `QuestionDto.categories` and `SubmissionDto.categories` carry
  every one, each once, in `QuestionCategory` declaration order, and a list's unknown names decode
  as `UNKNOWN` (§5). A batch reads its questions' categories in one more statement
  (`QuestionStore.categoriesOf`), never one per question. The feed takes a filter of any number of
  categories, `?category=` repeated, and none is every category; one that names no real category is
  400. The filter, and the due count beside it (`QuestionStore.dueCount`), is an `EXISTS` on that
  table, never a join, so a question in several of the categories asked for is served and counted
  once. A submission names one or more (*Submitting*). On the client a question holds every one
  (`Question.categories`, a set that is never empty, mapped in `QuestionMapper`): a name this build
  cannot read is `Category.OTHER` beside the rest, and an empty list is `OTHER` alone. A player's
  selection is a set too (`QuestionRepository.categories`, empty for every category, never `OTHER`),
  and every refill sends all of it; the console's Category row toggles each category, and *All*
  empties it. Selecting all of `Category.selectable` is not selecting none: a question filed only
  under categories this build cannot name is in none of them. A client submits under a set of one
  or more, never `OTHER` (*Submitting*).
- **Re-answering** *(built)*: a question can be answered again, whether or not the feed has
  served it again. It earns the point again **every time**, inside its cycle or not (farming is
  bounded by rate limiting, 120 votes a minute per player on average, §8b), and the player may
  change their pick. Every answer, first or not, counts for the player's current cycle. The tally
  always holds **one vote per player per question**, their latest. Built in `VoteStore.cast`, which
  moves the player's vote.
- **Retry safety** *(built)*: every vote carries a client-generated idempotency key. A repeat
  of the key last recorded for that question is replayed: nothing is written, it pays nothing, and
  it reports the stored side with the current tally and total, not the result first returned. Any
  other key is a fresh answer. Only the latest key per question is kept, so an older key arriving
  after a newer answer is a fresh answer too: it pays again and moves the vote back to its side.
  The app never resends an older attempt after a newer one, so only a duplicate from the network
  or a modified client can do that. Built in `VoteStore.cast` and in `AttemptId`, made once per
  tap: the Play tab resends a vote lost to `NETWORK` as the same attempt, as `withSessionRecovery`
  does its retry.
- **Stats** *(built)*: `GET /v1/me` reports the session player's total points, answers given
  (every paid answer, re-answers included and replays not, in `players.answers_given`, an SQL
  increment beside the points), distinct questions answered, current cycle, and how many questions
  are still due in it, counted by the feed's own predicate (`QuestionStore.dueCount`), and the
  likes received: how many likes the questions the player submitted hold now, their own included
  (`LikeStore.receivedBy`, *Likes*). Built in `StatsStore.of`, as one statement, so the total always
  agrees with the answers given and the likes received (§8c). It only reads, and the cycle starts
  lazily on the next feed request, so between the answer that finishes a cycle and that request it
  reports the finished cycle with nothing due.
- **Skipping** *(built; decided 2026-09-23)*: allowed, earns nothing, and never touches the
  tally. The server **records the skip for the player's current cycle only**, so the question is
  no longer due in that cycle and comes back in the **next** one, except through a category filter
  with nothing due in it (*Categories* above; provisional, §8b). A player is therefore never
  stuck at the end of a cycle on a question they keep skipping. Built as `POST /v1/skips` in
  `SkipStore.skip`, on `skips.skipped_in_cycle`, which the feed's due predicate compares with the
  cycle as it does the vote's. The console's Skip sends it through `SkipQuestion`, then loads the
  next question even when the skip failed.
- **Own questions** *(built; decided 2026-09-24)*: an author is served their own questions
  **like any other player** and may answer, skip and like them; the user chose the simpler logic.
  `QuestionStore.servable` is the one predicate the feed, the due count and votes, skips and likes
  (`QuestionStore.lockIfServable`) read, and it asks only that a moderator approved the question and
  has not retired it (*Moderation*).
- **Likes** *(built)*: any player may like any question, **their own
  included**, at any time (before or after answering), once each, and may unlike it. Each like
  currently held is **+1 point to the author**, and unliking takes that point back. The like count
  is visible before answering. For now likes do nothing else; serving questions by quality is a
  later idea. A seed has no author: a like on one counts and pays nobody.
  - Built as `POST /v1/likes` (`WyrApi.Paths.LIKES`), in `LikeStore.setLiked`, on `likes`, one row
    per player and question while the like is held, under a primary key on both. A `LikeRequest`
    names the question and `liked`, which *sets* the like rather than toggling it, so asking for what
    already holds writes nothing and pays nothing and a retry needs no attempt id; `liked` has no
    default, and a body without it is 400. The answer is a `LikeResultDto`: the question, its
    `likeCount` and `likedByMe`. A question that is not servable is 404 and an unknown player 401,
    as for votes and skips, and the id is checked as a vote's is. That holds for an unlike too: a
    retired question's likes stay held and paid until it is restored (*Moderation*).
  - Only a row actually inserted pays the author (`Scoring.POINTS_PER_LIKE`, an SQL increment) and
    only one actually deleted takes the point back, in the same transaction. A like reads whether the
    row is there and inserts only if not, and an unlike is one delete. Two likes or two unlikes of one
    question queue on its row's lock (§4), so the second finds what the first left, and the key
    still holds one like per player. `LikeStoreTest` races both.
  - Every feed batch carries each question's `likeCount` and `likedByMe` (`QuestionDto`), answered
    or not, read in one more grouped statement per batch (`LikeStore.likesOf`), never one per
    question, and the two numbers in that one statement so they agree. `GET /v1/me` reports
    `likesReceived` (*Stats*). Nothing about a like touches the tally, the cycle or what is due.
  - No author travels on the wire, so the result carries no points: an author sees theirs in their
    stats. `SubmissionDto` carries no like count yet.
  - *The client* is `LikeRepository` in `:core:domain`, behind `SetLike`, which ensures a session
    first. `DefaultLikeRepository` sends the `LikeRequest` through `withSessionRecovery`, as votes and
    skips go, and the retry after a recovered session sends the same `liked`, so a resend can never
    undo the like; nothing resends after any other failure. The answer is a `QuestionLikes`. A
    `Question` carries `likeCount` and `likedByMe` as the feed served them (`QuestionMapper`), and a
    queued one keeps them as fetched; `PlayerStats` carries `likesReceived`.
  - *The console* shows both on the question in its Play section, beside a Like button (Unlike while
    the player likes it). It asks for the opposite of what the question on screen shows, then puts
    the server's answer on that question, and only on the one it names. So a like lost to `NETWORK`
    leaves the question as it was, and pressing again asks for the like again. It reads the stats
    after every like, a failed one too, and shows `likesReceived`. Since a like moves its author's
    total without a vote, the console compares its stats with the last vote's total only while they
    count as many likes received as the first read after that vote did (`likesMovedSinceOutcome`),
    and not at all until the next vote once it sent a like before any such read worked
    (`likesUnmeasuredAtOutcome`). It works out no points itself.
- **Submitting** *(built; details decided 2026-09-23)*: earns no points
  directly, because authors earn through likes. The author writes both options and **picks one or
  more categories** (each a real one, not `UNKNOWN`; *Categories*). A player may have at most **20
  submissions pending** moderation at once. A submitted question is served only after a moderator
  approves it; once approved it is due for every player in their current cycle. Built as
  `POST /v1/questions`, in `SubmissionStore.submit` after `checkedSubmission`. Both options are
  trimmed, then each must be non-blank, at most `WyrApi.Limits.MAX_OPTION_LENGTH` (200, UTF-16
  units) and one line (no control character, nor U+2028 or U+2029, the line and paragraph
  separators), and the two must differ ignoring case: otherwise 422 `INVALID_SUBMISSION`, which the
  player can put right. No category, or one that is not a real one, is 400 `VALIDATION_FAILED`,
  since no correct client sends either: a picker must have one picked before it lets the player
  submit. A category named twice is filed once, and the question's categories are stored in
  declaration order, in the submission's own transaction. The 21st pending submission is 409
  `SUBMISSION_LIMIT`, counted under the author's row lock (§4). A submission is stored `PENDING`
  until a moderator decides it (*Moderation*). Questions carry an author and a
  `QuestionStatus`, and `QuestionStore.servable` serves only approved ones, due at once in whatever
  cycle each player is on. `GET /v1/me/questions` lists the author's submissions of every status,
  newest first, a rejected one with its reason and a retired one as `RETIRED`
  (`SubmissionStore.byAuthor`; `SubmissionStatus.RETIRED` on the client). On the client,
  `SubmitQuestion` and `GetMySubmissions` go through `withSessionRecovery`
  (`DefaultSubmissionRepository`). `SubmitQuestion` refuses no category, or `OTHER`, before it
  ensures a session, so nothing is sent, not even a guest's mint, and the repository refuses them
  again before building the request; every other rule is the server's. A status this build cannot
  name is `SubmissionStatus.OTHER`. The console's *Submit a question* section drives both.
- **Moderation** *(built)*: a moderator approves or rejects each pending submission, **may change
  its categories** when approving (*Categories*: at least one stays, and a change replaces the
  question's `question_categories` rows in one transaction), and **may retire an approved question
  and restore it** (*Retiring*, below; provisional, §8b), and sees every question (the list). A
  rejection carries a **short reason**, and the author sees the status of each of their submissions
  and, for a rejected one, that reason (`GET /v1/me/questions`, *Submitting*). The moderator is
  whoever holds the server's admin token (`ADMIN_TOKEN`, §8), not a role on a player account. Built
  in `io.ntole.wyr.server.moderation`.
  - *Off* when the token is unset: the admin routes are not registered, so each is 404 as a path the
    server never had. *On*, every admin route checks the `X-Admin-Token` header
    (`WyrApi.Headers.ADMIN_TOKEN`) before it reads anything else of the request (`requireAdmin`), and
    answers 403 `FORBIDDEN` without the right one. Never 401: the client answers a 401 by refreshing
    and then replacing the player's session. The bearer token plays no part and may travel beside
    it, live or dead. `AdminToken` keeps only the token's SHA-256 digest and compares a presented
    one's with `MessageDigest.isEqual`, so a refusal takes as long whatever was guessed.
  - `GET /v1/admin/submissions` is the queue: the players' submissions at `?status=` (`PENDING` when
    absent; `UNKNOWN`, an unknown name or a second value is 400), oldest first, bounded by `?limit=`
    as the feed is, in a `SubmissionListDto` of `SubmissionDto`s. Never a seed, and no author.
  - `GET /v1/admin/questions` is the list of every question, seeds included, newest first, in an
    `AdminQuestionPageDto` of `AdminQuestionDto`s: options, categories, status, whether it is a seed,
    when it was stored, reviewed and retired, a rejected one's reason, its tally and its like count,
    and no author. `?status=` and `?category=` narrow it, each repeated for several and matching any of its
    values, none for all; `UNKNOWN` or a name that is no status or category is 400, as for the feed's
    category (`categoryFilter`). `?limit=` bounds a page as the feed's is. A page is asked for by
    cursor, not offset (`QuestionCursor`, `?cursor=`): `nextCursor` is the last question's place,
    its `submitted_at` and then its id, and null on the last page, which is read as one row more than
    the limit. A question stored meanwhile is newer than every one listed and lands before the first
    page, where an offset would push one already listed onto the next page and list it twice. A page
    is two statements whatever its length (`ModerationStore.questions`): its rows with both vote
    counts and the like count as subqueries in one statement, so a question's numbers are one
    moment's (§4), and their categories in one more. No index orders every question by time: the
    list is the moderator's alone and the table small; `(submitted_at, id)` is the index once it is
    not.
  - `POST /v1/admin/approvals` takes an `ApproveSubmissionRequest`: the id, and categories that, when
    there are any, replace the author's (each real, each once, in declaration order); none keeps the
    author's. `POST /v1/admin/rejections` takes a `RejectSubmissionRequest`: the id and a reason,
    trimmed, then non-blank, at most `WyrApi.Limits.MAX_REJECTION_REASON_LENGTH` (200) and one line
    as an option is (provisional, §8b). A reason that breaks a rule is 400 `VALIDATION_FAILED`, not
    422: the moderator's client checks it against the same rules before it lets them send. Both
    answer 200 with the question's `SubmissionDto` as its author now sees it.
  - A decision is a compare-and-set on `PENDING` (`ModerationStore.decide`, §4): a question that is
    not pending, a seed included, is 409 `ALREADY_DECIDED` and changes nothing, an unknown id is 404,
    and of two moderators deciding one submission exactly one wins (`ModerationStoreTest` races
    both kinds). A decision sets `reviewed_at`, and a rejection its reason, which an approval clears.
    New categories are written after the decision is made and in its transaction, so they commit
    with it or not at all. An approved question is servable from the commit on: due at once for
    every player in their current cycle, its author included. Nothing moves a decided question on
    but retirement, below: no second look at a rejection, and no approval undone.
  - *Retiring* (*built; provisional, §8b*): `POST /v1/admin/retirements` takes a
    `RetireQuestionRequest`, the id of an approved question, a seed's included, and
    `POST /v1/admin/restorations` a `RestoreQuestionRequest`, the id of a retired one. Both answer
    200 with the question's `AdminQuestionDto`. A retired question is served to nobody and due for
    nobody, so a cycle can finish without it, and a vote, skip, like or unlike of it is 404; nothing
    it earned is taken back (§8c). Its author sees it as `RETIRED` in `GET /v1/me/questions`, the
    moderator in the queue's and the list's `?status=RETIRED`, never under `APPROVED`. Restored, it
    is servable again from the commit on, due for every player who has neither answered nor skipped
    it in their current cycle. Each is a compare-and-set (`ModerationStore.move`, §4): retire only a
    question standing at approved, restore only one standing at retired, anything else 409
    `WRONG_STATUS` and changes nothing, an unknown id 404, a malformed body 400; of two moderators
    racing exactly one wins. Every vote, skip and like locks its question's row first
    (`QuestionStore.lockIfServable`), so a retirement waits for those that found it servable, and
    answers with them counted, and one that comes after it finds it retired. Stored as
    `questions.retired_at` (V3) beside an `APPROVED` status, never as a status: `statusOf` and
    `standsAt` read the two columns as one status, so a rollback to the build before reads every row.
    The seed writes only into a database with no question, so a retired seed stays retired through
    every boot. `RetirementTest` pins it, the races included.
  - *The client* is `ModerationRepository` in `:core:domain`, behind `GetPendingSubmissions`,
    `ApproveSubmission` and `RejectSubmission`, none of which ensures a session: the moderator is
    not a player. `DefaultModerationRepository` calls `ModerationApi` through `runApi` alone, never
    `withSessionRecovery`, so nothing a moderator does can refresh or replace the player's session
    (the Auth plugin refreshes only on a 401). Each call sends the `AdminToken` it is given in the
    header, and only that call; nothing stores it, the HTTP trace records no headers, and
    `AdminToken.toString` shows none of it. The queue and every decision come back as the author's
    `Submission`. An approval under `Category.OTHER` is refused before anything is sent, and none
    keeps the author's categories. `RejectionReason` holds only a reason the server accepts, by
    `checkedRejection`'s rules, so a rejection's 400 can only be a bug; its `MAX_LENGTH` copies the
    wire's limit, which `:core:domain` cannot see, and `ModerationMapperTest` pins the two equal.
    `ModerationApi.questions` asks for a page of the list, one `?status=` and `?category=` per value
    and the cursor sent back as it came, and `retire` and `restore` post a question's id; each
    carries the token as the others do. Behind `ModerationRepository` they are `GetQuestions`, a
    `ModeratedQuestionPage` of `ModeratedQuestion`s for a `QuestionFilter` and the `QuestionCursor`
    the page before gave (none for the first), and `RetireQuestion` and `RestoreQuestion`, each
    answered with the `ModeratedQuestion` as the list now shows it; through `runApi` alone, as the
    rest. `DefaultModerationRepository` reads the queue and each page of the list
    `ModerationRepository.PAGE_SIZE` at a time, 100, the most the server lists at once (a copy
    `ModerationMapperTest` pins to `WyrApi.Limits.MAX_PAGE_SIZE`), since every read is a request of
    the address's admin budget (§8b). A `ModeratedQuestion` holds its categories as a question does,
    its status as a `SubmissionStatus` (`RETIRED`, or `OTHER` for one this build cannot name),
    whether it is a seed, its times as instants, its `Tally` and its like count. A filter by
    `SubmissionStatus.OTHER` or `Category.OTHER` is refused before anything is sent. `WRONG_STATUS`
    is `DomainError.WRONG_STATUS`. `moderationDataModule(environment)` binds it, and only there, for a
    client that only moderates: an HTTP client of its own over an in-memory session store nothing
    writes, no `TokenStorage` or `RecoverySecretStorage` needed, no session repository, so no bearer
    token goes out and no guest can be minted or recovered. The game's `dataModule` binds none of it,
    so the game cannot moderate (`DataModuleTest` pins both).
  - *The moderation app* (`:app:adminApp`, `io.ntole.wyr.admin`, §3) is where a moderator works: a
    desktop window and a browser page on `moderationDataModule` (`adminModules`), so it never has a
    player session, sends no bearer token, mints or recovers no guest and keeps no recovery secret
    (`AdminModuleTest`). Its header always names the server and its URL, production's in the error
    colors, and so does the desktop window's title (§8e). The admin token is typed into a masked field and held in `ModerationViewModel`'s
    memory only, never in saved state or storage, and `SecretText` keeps it out of the state's text;
    Lock forgets it and everything read with it, and cancels the action in flight, so nothing it
    answers is shown. The field itself is made anew on every Lock (`ModerationState.locks`): a text
    field keeps its undo history for as long as it is shown, so Undo in the one that held the token
    gave it back (`TokenBarTest`). Nothing is sent until what is typed can be a token
    (`AdminToken.of`), and one action runs at a time. *Pending* lists the queue, oldest first, each
    submission with its options, categories and age: Approve files it under the categories picked
    for it, none keeping the author's, and Reject sends the reason typed once it is a
    `RejectionReason`. The queue is read again after every decision, whatever became of it. A read
    lists at most `ModerationRepository.PAGE_SIZE`, and a queue that long says more may be waiting,
    its tab `Pending (100+)`, rather than naming itself the whole. *All questions* is the
    list, seeds included, newest first, filtered by any statuses (`RETIRED` among them; never
    `OTHER`) and any categories, none being every one: Load reads its first page and Load more the
    next, at the filter the list was read at, with the cursor the page before gave, and changing the
    filter drops what was read at the one before. Each question shows its options, categories,
    status, whether it is a seed, its votes and likes, its times and a rejection's reason, and what
    can be done where it stands: Retire an approved one, only once the moderator confirms it in a
    dialog; Restore a retired one; decide a pending one as in the queue, from the same draft of
    categories and reason. A retirement or restoration puts the question it answers with in its
    row, or drops it once the list's filter no longer picks it: the server reads that answer as it
    reads a page's row. A decision, whose answer lacks the row's tally and likes, reads the list
    again as many pages deep as were shown, and so does a move that failed, so the list shows what
    the server holds without losing the moderator's place; a decision reads the queue again too.
    Nothing is read again after a 403 or a 429, which did nothing and would refuse the read too:
    after a wrong token the read would only spend another of the address's ten a minute, past which
    every admin request from it is refused (§8b). A failure shows where it happened: a read's above
    its screen, an action's under its question, or at the top of the screen it was started from,
    named by the question's options, once the read after it no longer lists it. A 403 reads as a
    wrong token, a 409 as a decision or a move made first, a 429 with the wait its `Retry-After`
    named (`WyrException.retryAfter`, §8b). An answer the data layer cannot name (`UNKNOWN`) claims
    no status, leaving it to the detail line under it, and says a bare 404 means moderation is off
    on that server: a proxy's own page or an error code newer than the build reads as `UNKNOWN` too.
    `ModerationViewModelTest` and `QuestionListViewModelTest` drive it over a scripted repository,
    `ModerationOverHttpTest` over the real client configuration, and `ScreensDrawTest` draws every
    screen off screen at a desktop window's size. The dev console had a *Moderation* section until
    the app replaced it (`feat/moderation-app`): the game's builds no longer moderate at all.

## 8e. Client environments — decided 2026-09-24

Every client build targets one of three server environments, chosen **when it is built**, so a phone
can play against the deployed servers and a production build can never talk to a development one by
accident. `WyrEnvironment` (`io.ntole.wyr.core.network.environment`) names them, each with its API
base URL, a display name, and whether a build for it shows the developer tools. It lives in
`:core:network`, not `:app:shared`, so a client without the game UI (the moderation app,
`:app:adminApp`, §3) can name one too.

| Environment | Server                                                           | Developer tools |
|-------------|------------------------------------------------------------------|-----------------|
| `LOCAL`     | `http://localhost:8080`; the Android emulator's `10.0.2.2:8080`  | shown           |
| `DEV`       | `https://wyr-server-dev.onrender.com` (§8: in-memory H2)         | shown           |
| `PROD`      | `https://wyr-server.onrender.com`                                | hidden          |

- *Naming one.* `WyrEnvironment.parse` takes `local`, `dev` or `prod`, in any case, trimmed; no name,
  or a blank one, is LOCAL. Any other value throws, naming it, rather than falling back. Every entry
  point hands its name to `initKoin(environmentName)` (the moderation app's to `initAdminKoin`),
  which has no default, and it is parsed before Koin starts, so a bad name stops the app at launch. LOCAL's URL comes from each platform source set
  of `:core:network`, so it is right on every platform, the emulator's included.
- *Android*: product flavors `local`, `dev` and `prod` in one `environment` dimension;
  `BuildConfig.WYR_ENV` is the flavor's name, which `WyrApplication` passes on. Each installs beside
  the others: application id suffix `.local`, `.dev` or none, launcher label *WYR Local*, *WYR Dev* or
  *WYR*. Cleartext HTTP (the `usesCleartextTraffic` manifest placeholder) is on for `local` alone;
  `dev` and `prod` are https only. `dev` is Android Studio's default variant, since a physical phone
  cannot reach LOCAL. `assembleDebug` builds all three.
- *Desktop*: the `WYR_ENV` environment variable, read in `:app:shared`'s jvmMain
  (`desktopEnvironmentName`), which `Main.kt` hands to `initKoin`; unset is LOCAL.
  `WYR_API_BASE_URL`, which pointed the desktop client at any server, is retired: left set in a shell,
  it sent a PROD build's requests wherever it named, with nothing on screen to say so, since a PROD
  build has no console. No client can put another URL in its environment's place. The moderation app
  reads the same variable in its own `jvmMain` (`desktopEnvironmentName` there too), since it cannot
  see `:app:shared`'s: `WYR_ENV=dev ./gradlew :app:adminApp:run`.
- *Web*: the Gradle property `wyr.env` (`-Pwyr.env=dev`), local when absent, which the
  `generateWyrEnv` task writes into a Kotlin constant, `WYR_ENV`, under `build/generated`; a name it
  does not know fails the build. The task is `gradle/wyr-env.gradle.kts`, a script each module with a
  browser entry point applies after naming the constant's package in `extra["wyrEnvPackage"]`, so no
  two copies of the rule can drift: `:app:webApp` into `io.ntole.wyr`, `:app:adminApp` into
  `io.ntole.wyr.admin`. A web build against DEV or PROD also needs that server's
  `ALLOWED_WEB_ORIGINS` (Render dashboard, `sync: false`) to include the page's origin, or every
  request fails CORS.
- *iOS*: the `WYR_ENV` build setting in `app/iosApp/Configuration/Config.xcconfig` (`local` by
  default). `Info.plist` carries it as its `WYR_ENV` key (`$(WYR_ENV)`), and `MainViewController`
  reads that from the main bundle; a missing key is LOCAL.
- *Guest-only on desktop and web* (*decided 2026-09-25*): neither keeps a recovery secret (§8a,
  *Recovery*), so a player there is bound to that one storage, and the console's *Reinstall (keep
  secret)* mints a fresh guest there, as *New guest* does.
- *In the app.* Koin binds the environment (`appModules`), and `dataModule` sends every request to
  that same environment's URL, so the dev console's header, which shows its name and URL, always says
  where requests go. The console tab is shown only where the environment shows developer
  tools (`rootScreensFor`): a PROD build shows the Play screen alone, with no tab to reach the console.
  The moderation app binds its environment the same way (`adminModules`), and names it on every
  screen, whatever the environment: its header shows the server's name and URL, production's in the
  error colors, and the desktop window's title shows both too (`windowTitleOf`).
- *A session per environment.* Each environment's guest session is stored under a key of its own
  (`SessionStore.keyFor`: `wyr.session.local`, `wyr.session.dev`, and `wyr.session` for PROD, the key
  every build used before there were environments), which `dataModule` is handed with the
  environment. On desktop, iOS and web one storage serves every environment's build: a JVM
  Preferences node, one bundle id's `NSUserDefaults`, one origin's `localStorage`. With one key, a
  build for one server sent the other's tokens to it and, once they were refused, replaced that
  guest, and its points, with a new one (§8a). Android's flavors have storage of their own anyway.
  The recovery secret (§8a, *Recovery*) is one environment's too, since each server's database holds
  its own, so it is kept under a key per environment the same way (`RecoverySecretStore.secretKeyFor`:
  `wyr.recovery.local`, `wyr.recovery.dev`, `wyr.recovery.prod`, with no legacy key to keep), one
  iCloud Keychain item per environment included, or a DEV secret would be sent to PROD and dropped as
  unknown. Renaming a key strands every secret kept under it.
---

## 9. How to work in this repo

- Read this file first, every session.
- Before adding a dependency, changing the module graph, or picking a tool for an §8b item,
  surface the decision rather than assuming.
- When a decision is made, encode it here and in config — not just in conversation.
- Verify with `./gradlew ktlintCheck` plus the test and compile tasks listed in
  `.github/workflows/ci.yml`. That workflow is the definition of "green". Besides `verify` it runs
  `server-postgres` (the server suite against a Postgres service container), `docker-smoke` (builds
  the image and polls `/health`), and `ios` (framework link, simulator tests, the `:core` modules'
  test compiles, and an `xcodebuild` simulator build on macOS). All four passed on their first run,
  2026-09-24. None of those three
  can run on this machine: read their results with `gh run list -R niktok1/would-you-rather`,
  through a login to the personal account only (§7). `:server:test` uses H2 unless `WYR_TEST_JDBC_URL` (plus
  `WYR_TEST_DB_USER` / `WYR_TEST_DB_PASSWORD`) names another database; the suite then wipes it,
  Flyway's history included, before each test that uses it (`ExternalTestDatabase.clean`), and runs
  the schema tests (`MigrationsTest`, `SchemaDriftTest`) on it as well as on H2.
- **iOS cannot be linked, tested, or run on a machine without Xcode** (Command Line Tools alone
  are not enough). The Kotlin compile does not need Xcode, so before pushing iOS-touching code
  run `./gradlew :app:shared:compileKotlinIosSimulatorArm64 :app:shared:compileTestKotlinIosSimulatorArm64`
  locally, and before pushing a common test in `:core:network` or `:core:data`, that module's
  `compileTestKotlinIosSimulatorArm64`: Kotlin/Native refuses a comma in a test's name, which the JVM
  takes. Framework linking, the simulator tests and the Xcode app stay unverified until the
  `ios` CI job or a machine with full Xcode runs them.
