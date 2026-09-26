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

:core:domain         Domain models, repository/cache ports, use cases, the analytics port
                     (Analytics, §8g). PURE Kotlin.
                     Depends on NOTHING else in the project — not even :core.
                     The innermost layer. explicitApi() enforced.

:core:network        Ktor client, the Json config, platform token storage, API classes,
                     the server environments (WyrEnvironment, §8e), and the analytics
                     sender, PostHog over its HTTP API (PostHogAnalytics, §8g).
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
| Server persistence | Exposed                | JetBrains Kotlin SQL framework, pairs with Ktor  |
| Schema migrations  | Flyway                 | Server-only Java exception (§2); runs at boot    |
| Schema diffing     | exposed-migration-jdbc | JetBrains; test scope only (drift test, drafts)  |
| Dependency inj.    | Koin                   | Pure Kotlin, no codegen, KMP standard            |
| Date/time          | kotlinx-datetime       | Replaces platform date APIs                      |
| Connection pool    | HikariCP               | Server-only Java exception (§2)                  |
| Server DB driver   | PostgreSQL JDBC        | Server-only Java exception (§2)                  |
| Local dev DB       | H2 (in-memory)         | Dev/test only. Never production                  |
| Lint               | ktlint (Gradle plugin) | Style pinned in `.editorconfig`                  |
| Product analytics  | PostHog (service, HTTP API, no SDK) | EU cloud; over the Ktor client (§8g) |

Server database engine: **PostgreSQL** (via Exposed). Hosting: **Render** — see §8.

**PostHog is a service, not a library** (*decided 2026-09-26*): the clients call its public HTTP
`/batch/` endpoint with the Ktor client already in the tree (`PostHogAnalytics`, §8g), so nothing is
added to the catalog, and no PostHog SDK, none of which is Kotlin Multiplatform, is ever added.

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
  `ModerationStore.decide`, a retirement or restoration, `ModerationStore.move`, a registration
  naming a player who has no username, `AccountStore.register`, and a submission's cost,
  `PlayerStore.spend`, whose `WHERE total_points >= cost` holds even an author's last point to one
  submission). Where the
  `WHERE` can hold the whole check, nothing need be read first (`SessionStore.rotate`, whose second
  racer re-checks it against the first's commit).
  Or the read takes the row lock (`SELECT ... FOR UPDATE`), so a concurrent writer waits and then
  reads the row as committed (`VoteStore.cast` and `ReactionStore.set`, which branch on more than
  one outcome, and `SkipStore.skip`).
  A read of rows a racing writer is about to add has no row to lock, so it locks a parent row that
  every such writer locks first (`SubmissionStore.submit` counts an author's pending questions
  under the author's `players` row).
  The one exception is a value copied from another row, which may be a plain read where a stale
  copy is provably harmless, with the proof at the read (`VoteStore.currentCycle`: the feed moves
  the cycle on only once the answer's question is already answered or skipped in the one read).
- Uniqueness is a constraint (the `Votes`, `Skips`, `Reactions` and `Categories` primary keys,
  `players.username`'s unique constraint), never a prior `SELECT`. A violation is never caught and
  carried on from: PostgreSQL aborts a transaction at its first error. It propagates, and Exposed
  rolls back and reruns the whole transaction, which then sees the committed row (`VoteStore.cast`,
  `SkipStore.skip`, `ReactionStore.set`, `AccountStore.register`, which `AccountStoreTest` races,
  `CategoryStore.create`, which `CategoryStoreTest` races, and `Seed.writeMissing`, which
  `SeedTest` races). A plain read before such an insert only spares
  a certain violation, and needs no lock when finding the row writes nothing.
- Numbers that must agree with one another are read in one statement, which sees one committed
  state; two statements can straddle another transaction's commit (the tally in `VoteStore`,
  `StatsStore.of`, a question's like and dislike counts beside the player's own reaction in
  `ReactionStore.reactionsOf`, a question's tally and reaction counts in the moderator's list,
  `ModerationStore.questions`, and a submission's counts in its author's list,
  `SubmissionStore.byAuthor`).

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

A **list** of such an enum would need more, because `coerceInputValues` only coerces a property's
own value, never an element of a list: one unknown element fails the whole payload. So a list
property of a growable enum MUST be declared with a serializer that decodes an unknown element as
`UNKNOWN`, and MUST default to an empty list. None is on the wire today.

**Categories are not an enum on the wire** (*decided 2026-09-25*): they are server data (§8d,
*Categories*), and every categories field and parameter carries plain category ids, strings, each
list defaulting to empty. A category added server-side is then only an id an installed client has
no name for, never a payload it fails to decode, so the rule above does not apply to them.
`QuestionCategory` and its list serializer are gone; the first ids are the enum's own names, so the
JSON for them is byte for byte what it was (`WyrJsonTest`, `ServerJsonTest`). Nor is a category an
enum on the client: the domain holds category ids, named from the list the server sends (§8d,
*Categories*, *The client*), so there is no `OTHER` to land an unknown one in.

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

**Icons** are drawn by hand in the theme too, as `ImageVector`s in `WyrIcons`, a few strokes each on
a 24 by 24 grid, so no icon library is needed (§2): `Home`, `Account` and `Back` (an arrow pointing
left) for the top bars (§8d, *Navigation*); for the Play screen `Skip` (a triangle against a bar),
`ChevronDown`, the small chevron beside the categories played, and the reactions' `ThumbUp` and
`ThumbUpFilled`, and `ThumbDown` and `ThumbDownFilled`, the thumb up turned over (§8d, *The Play
screen*, *Reactions*); `CoinFace` and `CoinMark`, a disc and the rim and ring on it, the points' coin
wherever they show (§8f, *Numbers and symbols*); `Globe` for the language menu; and `Players`, two
players, heading My questions' answers (§8d, *The Account screen*). They carry no colour of their
own: `Icon` tints each from `WyrColors`, so they follow the light and dark themes as text does. The
coin is two icons drawn one on the other, the face in `WyrColors.coin` and the mark in
`WyrColors.onCoin`, the brand's amber and its dark brown, the same in both themes as the cards are
(`CoinIcon`). Adding an icon = adding a `WyrIcons` value.
`WyrIconsDrawTest` draws each off screen: every one a figure of the theme's size, no two the same,
each filled thumb covering its outline and the hand's inside, the thumb down the thumb up turned
over, and the coin's ring on its face.

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
    (`4cdc819`); it runs `d4a9dbf` since 2026-09-25, with V1 to V4 applied.
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

**Guests first: zero-click, server-issued guest sessions with a custom Kotlin implementation.** No
third-party auth SDK, satisfying §2. A guest may then register as an account (*Accounts*, below;
decided in §8b).

- `POST /v1/auth/guest` mints the player server-side and answers a `SessionDto`: a signed access JWT
  and an opaque refresh token. Nothing is asked of the player. Identity is **server-issued**, which
  is the whole point: a client-supplied device id would be forgeable and would let one device stuff
  the ballot.
- The access token travels in `Authorization: Bearer`, never in a request body, so `VoteRequest`
  does not change when auth evolves.
- **Sessions** (*decided 2026-09-25*): a player's refresh tokens live in `sessions`, **one
  refresh-token family per device**, each rotating on its own row, so a refresh on one device never
  touches another's tokens. Nothing caps how many a player has, and only a logout deletes one: an
  expired session is dead where it lies. A mint opens a player's first session (`SessionStore.open`);
  V4 opened one for every player who held a refresh token then. A refresh reads and writes its session
  alone. Every access token names its session beside its player (the `sessionId` claim), and a
  refresh keeps it. The players row's old refresh columns, V4's mirror for a rollback to a build from
  before sessions, are unused since `feat/simple-accounts` (§8b, *Rollbacks*).
  - *Logging out* is `POST /v1/auth/logout`, bearer required, no body, answered 204: the session the
    token names is deleted (`SessionStore.close`), so neither its current refresh token nor the one the
    grace keeps works again, and the player's sessions on other devices are left alone; the client then
    plays on as a fresh guest. A session already gone is 204 too. Its access tokens still work until
    each expires, at most 15 minutes: nothing reads a session to let a request in. A token from a build
    before tokens named their session, `d4a9dbf`'s, is 401 `UNAUTHORIZED`, which the client answers by
    refreshing into one that does.
- **Refresh tokens rotate on every use, with a grace** (*decided 2026-09-24*, with no time bound).
  Only a SHA-256 hash is stored. The token a rotation displaces stays as its session's previous one,
  with the expiry it had and when it was displaced, and a refresh presenting it still succeeds until
  the next rotation displaces it, never past its own expiry. So a token works twice at most, once
  while current and once more as the previous one; anything else is 401 `INVALID_REFRESH_TOKEN`.
  `REFRESH_GRACE_SECONDS` can bound the grace: unset, the default, sets no bound; 0 turns it off; a
  number of seconds takes the previous token only that long after the rotation that displaced it.
  - *Why:* a refresh the server ran whose answer never arrived (a dropped connection, a Render cold
    start, the client's 5-minute refresh timeout, the app killed mid-refresh) leaves the client with
    only the token the server rotated out. Without the grace its next refresh is refused and the
    player becomes a fresh guest. Nothing resends a refresh that failed on the network: the token
    goes again with a later call's 401, whenever the player is back, which is why the 10-minute bound
    built first was dropped (§8b, *Refresh answers lost past the grace*).
  - *The cost* is a copy's: a refresh token copied to a second device, or stolen, keeps working
    beside the original for as long as the two take turns refreshing, each leaving the other's token
    in the previous slot. Accepted for a game that stores nothing personal (the user's decision,
    2026-09-24).
  - *The rotation* is one `UPDATE` of the session whose `WHERE` is the whole check, nothing read
    before it (`SessionStore.rotate`, §4). Of two refreshes racing with the current token, both go
    through, the first's new token becoming the previous one; of two racing with the previous token,
    exactly one. It stamps every rotation, bound or not: a bound set later reads the stamp.
    `SessionStoreTest` races both and pins every rule.
- **On the client**, `SessionStore` (`:core:network`) is the only copy of the credentials, one per
  environment (§8e), and Ktor's bearer cache is off (`cacheTokens = false`), so a session change
  applies to the very next request. A dead session is replaced through `withSessionRecovery` in
  `:core:data`, which mints at most one guest for it (`DefaultSessionRepository.resetIfStill`).
  - *Clients sharing one store* (browser tabs, desktop instances) can both refresh one token, and the
    server lets both through. A refresh that finds the store moved on to another session of the same
    player refreshes once more as the stored one and keeps that answer, the latest rotation
    (`refreshAs` in `WyrHttpClient`, `SharedSessionStoreTest`); the once more is never repeated, and
    a session of another player, or none, is kept as it was.
  - Every request is bounded (`HttpTimeout` in `WyrHttpClient`: 60 s, past a Render cold start;
    Android and desktop also give up on a connect after 30 s; iOS applies only the socket timeout, a
    browser only the request timeout). A request that spends the refresh token takes
    `refreshTimeout()`, 5 minutes, since abandoning it abandons a token the server may already have
    rotated; a time bound on the grace must outlast the 5 minutes. It stays bounded because every
    call rejected meanwhile waits on it, uncancellably.
  - `TokenStorage.write` returns only once the session would survive the app being killed, since
    after a refresh the rotated token is the one sure to work (Android `commit()`s on
    `Dispatchers.IO`, one change at a time and in the order asked, never `apply()`s), and a write
    asked for lands even if its caller is cancelled. A write that cannot be made durable fails the
    call as `NETWORK`: the data layer writes the session through `runApi`.
  - The session stays on its device. Android's backup rules (`data_extraction_rules.xml`, Android 12
    and later, `backup_rules.xml` before, in `:app:androidApp`) keep `AndroidTokenStorage`'s
    `wyr.auth.xml` out of the cloud backup and a device-to-device transfer: a copy would share a
    refresh-token family with its source, and whichever refreshed less would end up a fresh guest. A
    new phone starts as a guest of its own, and a registered player logs in there. `allowBackup` stays
    on, with nothing else in it yet.
- **Accounts** (*decided 2026-09-25*, §8b): a username and a password on a player, which a guest may
  add, keeping everything it has, and log in with on another device. Built on the server:
  `players.username`, lower-cased, under a unique constraint, and `players.password_hash` (V5), both
  null for a guest. The game's client registers, logs in and logs out (*The client*, below).
  - *Registering* is `POST /v1/auth/register` with a `RegisterRequest`, bearer required, answered with
    an `AccountDto`: the player the token names gets the username and the password's hash
    (`Passwords`, §8b) and keeps its points, sessions and all else. The username, lower-cased and never
    trimmed, must be 3 to 20 of `a`-`z`, `0`-`9` and `_`, the password 6 to 128 characters of any kind
    (`WyrApi.Limits`; `checkedUsername`, `checkPassword`), or it is 422 `INVALID_USERNAME` or
    `INVALID_PASSWORD`, the username checked first. A username another player has, in any case, is
    409 `USERNAME_TAKEN`. A player registered already is 409 `ALREADY_REGISTERED`, since neither the
    username nor the password changes for now; so is a registration sent again after its answer was
    lost. `AccountStore.register`, under §4's rules: the unique constraint decides two players racing
    for one name, a compare-and-set two registrations of one player (`AccountStoreTest`).
  - *Logging in* is `POST /v1/auth/login` with a `LoginRequest`, no bearer needed, answered with a
    `SessionDto` for a new session of that player (`SessionStore.open`), this device's own, so the
    player's other devices stay logged in. A bearer token sent beside it plays no part, and the session
    it names is left alone. The username is trimmed (a keyboard's suggestion leaves a space after it,
    and no username holds one) and compared lower-cased; the password is taken exactly as sent. A
    name with no account, a wrong password, and a name or password no account can have (refused at
    once, unhashed: the rules are public) are one and the same 401 `INVALID_LOGIN`, and a name with no
    account is checked against `Passwords.UNMATCHABLE`, so it is refused only after a hash's time, as a
    wrong password is. A client must send a login so that this 401 is never taken for an expired
    access token, which would refresh the session it holds and send the login again: `AuthApi.logIn`
    sends it past the Auth plugin (`AuthCircuitBreaker`, as the plugin's own refresh goes), so no
    bearer goes with it either (`AuthApiTest`). Limited per address (§8b).
  - `GET /v1/me` names the username (`PlayerStatsDto.username`), null for a guest; a client from
    before accounts ignores it (§8d, *Stats*).
  - Not built, by design for now: a password reset (no email is collected), a rename, a password
    change, and any lockout per username. The address's login budget is the one bound on guessing.
  - Neither a password nor its hash is ever logged, nor is either in any answer; `RegisterRequest`'s
    and `LoginRequest`'s `toString` hide the password (`AccountFlowTest`).
  - On the client each of the five codes is a `DomainError` of its own (`ErrorMapper`), and
    `INVALID_LOGIN` is never `UNAUTHORIZED`, which would throw this device's session away over a
    mistyped password. `AccountRules` (`:core:domain`) holds the username and password rules, so a
    form says what is wrong before it sends; its numbers copy `WyrApi.Limits`, which the domain
    cannot see, and `AccountLimitsTest` pins each copy.
  - *The client* is `AccountRepository` in `:core:domain`, behind `RegisterAccount`, `LogIn` and
    `LogOut` (`DefaultAccountRepository`, `DefaultAccountRepositoryTest`). A registration refuses what
    `AccountRules` refuses, then ensures a session, since on a first launch the guest registered is
    the one just minted, and goes through `withSessionRecovery`, so on a dead session the retry
    registers the fresh guest. A login needs no session and never goes through it. The session it
    answers is stored in place of the device's (`DefaultSessionRepository.replace`, under the lock
    minting takes), so the next call plays as the account; a guest's session is abandoned, and dies
    unused. A logout is best effort: the device forgets its session whatever the server answers,
    and the next call mints a fresh guest. A login and a logout drop the question queue
    (`QuestionRepository.reset`), which the player before filled. The session is kept as a guest's
    is, so a logged-in player stays logged in across launches while the device refreshes within a
    refresh token's 30 days; one idle longer plays on as a fresh guest, and logs in again. No
    password is stored, anywhere: the phone's password manager may keep it, offered as the Auth page,
    where the game's client registers and logs in, leaves the screen (§8d, *The Account screen*).
    Both, once they worked, tell the analytics who plays here by player id, and a logout has them
    forget (§8g, *Who*).

**Known limitation, by design for now:** a guest account is bound to one device's storage. Lose
the device, reinstall the app or clear its storage, and the account — and its points — are gone,
unless the guest registered (*Accounts*, above), and then only until it logs in again. Session storage is ordinary preference storage
(SharedPreferences / NSUserDefaults / JVM Preferences / localStorage), not Keychain or
EncryptedSharedPreferences: enough for a game that stores nothing personal.

## 8b. Open decisions (resolve before relevant work)

- **Accounts** — *decided 2026-09-25; built (§8a, *Accounts*; the Account screen, §8d).* This
  is a simple game that stores nothing personal, and most players stay a day or a few, so the
  simplest design that is correct enough wins over maximum security. A new player plays at once as a
  guest (§8a). **Register** is optional and keeps the guest's points; **log in** is how a registered
  player gets their account on another device. What the app saves by itself is the session, so a
  player stays logged in while the device refreshes within 30 days; no password is stored, and the
  phone's password manager may keep it (§8a, *The client*). Passwords are hashed on the server and
  never logged (`Passwords`: PBKDF2-HMAC-SHA256 from the JDK, no library, 100,000 iterations over a
  16-byte salt of each password's own, about 9 ms warm on the
  development machine and so, by estimate, 0.1 to 0.2 s on Render's tenth of a CPU). A stored hash
  names its algorithm and cost, `pbkdf2-sha256$<iterations>$<salt>$<hash>`, so the cost can be
  raised later and the hashes already stored still verify; nothing rehashes one at a new cost yet.
  No email is collected, so there is **no password reset**: a forgotten password means a new
  account. No-click sign-in (Play Games Services on Android, Game Center on iOS) comes later, once
  there is an Apple developer account: it would link a platform's player to a player here as
  registering links a username, beside the password or in its place. This replaces the recovery
  secret (V4), which is gone from the server and every client; its column stays, unused, until a
  later migration drops it. A `d4a9dbf` phone build that keeps a secret fails every call once its
  session dies, since the recovery it tries first is now 404: install a current build on it.
- **SQLDelight cache** — see §4. Needs a per-platform split because of web. Lower priority now
  that the endless feed (§8d) makes the server the source of truth for what a player has answered:
  the client keeps no record of what it served, so a persisted queue would only save one fetch
  after a restart.
- **Question submission + moderation** — the game rules are settled in §8d, and so is the
  moderator model (an admin token). The submission contract is settled and built on the server:
  `SubmitQuestionRequest`, `SubmissionDto`, `SubmissionListDto` and `QuestionStatus` in `:core`.
  No author travels on the wire: a submission's author is whoever its bearer token names, and a
  player lists only their own. The game's Submit form submits them, and the Account screen's My
  questions lists them. The moderation contract is settled and built on the server too: the admin
  routes, `ApproveSubmissionRequest`, `RejectSubmissionRequest`, the question list's
  `AdminQuestionPageDto` and `AdminQuestionDto` (paged by `WyrApi.Query.CURSOR`),
  `RetireQuestionRequest` and `RestoreQuestionRequest`, `CreateCategoryRequest` and
  `RenameCategoryRequest`, the `X-Admin-Token` header (`WyrApi.Headers`), `QuestionStatus.RETIRED`
  and the error codes `FORBIDDEN`, `ALREADY_DECIDED`, `WRONG_STATUS`, `CATEGORY_EXISTS` and
  `CATEGORY_NOT_FOUND`. The moderator's client (`ModerationApi` calls every admin route) and the
  moderation app are built on it (§8d, *Moderation*).
- **A rejection reason is one line** — *provisional — user decision.* §8d asks for a short reason;
  the server also holds it to one line, as it does an option: no control character, nor U+2028 or
  U+2029 (`checkedRejection`). Chosen as the stricter reading, since a reason is shown to its author
  as a line of text; allowing line breaks later breaks no client. The options: keep it, or allow
  line breaks in a reason.
- **Retiring a question** — *provisional — user decision.* The user asked for a way to take an
  approved question out of play and put it back (§8d, *Moderation*); the details are this build's.
  Built: a moderator retires an approved question, a seed included, and restores a retired one.
  Retired, it is served to nobody and due for nobody, and a vote, skip or reaction to it is 404, taking
  one back included; nothing it earned is taken back, so its answers' points stay and its reactions
  stay held, its likes paid, and nobody can take one back until it is restored. Restored, it is due for every player who has not
  answered or skipped it in their current cycle. It is stored as `questions.retired_at` beside an
  `APPROVED` status, and sent as `QuestionStatus.RETIRED`, so a rollback to the build before (§8b,
  *Rollbacks*) reads every row and only serves retired questions again. A vote, skip or reaction reads
  servability plainly (`QuestionStore.isServable`, no lock on the question; *decided 2026-09-25*),
  so one in flight as a retirement commits may still land, uncounted in the retirement's answer.
  The options: keep it; let a reaction be taken back on a retired question, so a player can still
  take a like back (and its author's point with it); take back what a retired question earned (every
  total would move, and §8c's sum would need the retired questions left out on both sides); or
  `RETIRED` stored as a status of its own once no build before this one is a rollback target, which
  drops the column.
- **Skips under a category filter** — *provisional — user decision.* A request filtered to one
  or more categories with nothing due in any of them, while other questions still are, serves
  those categories again (§8d, *Categories*), and that includes questions skipped this cycle, which
  §8d, *Skipping*, says come back only in the next one. Built that way because it is the Categories
  rule as written, which predates recorded skips, and it changes neither rule; a filter of several
  categories carries it over unchanged. The options: keep it; serve again only the filter's
  answered questions, and its skipped ones only when it has nothing else; or answer an empty
  batch, which the client reads as out of questions. The Categories screen sends the categories
  selected (§8d, *The Categories screen*), so a player reaches this in every build, PROD's included.
  `SkipStoreTest` pins what is built.
- **The categories on the Play screen** — *resolved 2026-09-26*: the user moved them from the row
  between the cards, where the redesign of 2026-09-25 had put them, to the middle of the top bar
  (§8d, *The Play screen*).
- **The Play row's arrangement** — *resolved 2026-09-26*: the user's, the points on the left, the
  thumbs in the middle and Skip on the right (§8d, *The Play screen*).
- **Log out under the language row** — *provisional — user decision.* The user's Account redesign put
  Log out beside the language menu, one row of the two; the Statistics switch (§8g) now shares that
  row, since a row of all three does not fit a phone's width, and a row of its own would take a
  guest's screen past the 599 of an iPhone SE (566 with the switch beside the menu, 630 under it).
  So Log out stands under the row, at its end, for a registered player (574 at the tallest). The
  options: keep it; Log out on the card, where a guest's button to the Auth page is; or the switch
  elsewhere, off the Account screen's first view.
- **One Try again** — *provisional — user decision.* The Play and Account redesigns said Try again
  two ways in Serbian, *Пробај опет* and *Покушај поново*, and so did the Categories screen, with
  *Пробај опет*; it is one text now (§8f, *The strings*), *Покушај поново*, which four of the five
  screens before it and the Account screens' *Нешто није у реду. Покушај поново.* already used. The
  options: keep it; or *Пробај опет*, a little shorter, in `Strings.tryAgain` and that sentence
  both.
- **What submitting cost, on the Account screen** — *provisional — user decision.* The Account card
  shows the points and the questions answered and not `pointsSpent` (§8d, *Stats*), and the cost
  shows on the Submit form's button. The options: keep it; or a stat on the card, what was spent.
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
  - *Per client address* (on Render, Cloudflare's `CF-Connecting-IP`: `CLIENT_IP_HEADER`, §8), for a
    caller with no session to name: guest minting 10 an hour, refreshes 30 a minute, logins 20 a
    minute (what bounds guessing a password, as each costs a hash), the categories list 120 a minute
    (it needs no session), the admin routes 60 a minute together, and on top of that, admin requests with a wrong or missing token 10 a minute. A
    request with the right token spends none of that last budget, but once an address has spent it,
    every admin request from the address is refused until the budget is back, the right token's too
    (`LockingOut`): were that one let in, its 200 among the 429s would give it away, and guessing
    would be bounded by nothing. So a guesser behind the moderator's address can lock the moderator
    out, a minute at a time. It is asked first, so guesses refused by it spend none of the
    moderator's 60.
  - *Per player*, so players behind one address do not share a budget: registrations 20 an hour
    (every one the rules take costs a password hash), logouts 30 a minute, the feed, votes and skips
    120 a minute each, reactions 60 a minute, submissions 30 an hour (the 20-pending cap still applies),
    `GET /v1/me` and `GET /v1/me/questions` 120 a minute each. The key is the player id in the
    bearer token, which the limiter verifies itself (`verifiedPlayerId`): it runs before
    authentication, so no principal is there yet. A request without a token this server signed
    spends its address's budget of the group instead, and then gets its 401, so a forged token
    naming a player cannot spend that player's budget. A token that has only expired, as every
    player's does in its turn, still names its player for a refresh token's lifetime
    (`TokenService.expiredTokenVerifier`), so the request that finds it expired spends that player's
    budget and reaches its 401, which is what the client refreshes on; keyed by address, it would be
    answered 429 once the address's budget was spent, and the client would not refresh.
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
  (`CorsTest`). Nothing retries it (`withSessionRecovery` retries only after a 401): the Account and
  Submit screens say how long to wait, and the Play screen asks the player to slow down.

  What remains: farming is bounded, not gone. A player can still earn 120 points a minute by
  re-answering, on average, and up to 240 where two windows meet (*decided 2026-09-23:* a re-answer
  keeps paying every time, inside its cycle or not), and a script gets 10 fresh guests an hour per
  address, 20 where two windows meet, each with budgets of its own, so liking one author's questions
  is bounded per address and per hour, not per author (*Likes from fresh guests*, below:
  accepted). Counts are in memory and per instance: right for the one Render instance, but a second
  would grant every budget again, so running two needs a shared store first (Render Key Value, say).
  A restart, which a deploy or a free instance's spin-down is, resets them.
- **Likes from fresh guests** — *decided 2026-09-24: no like limitations.* A like pays its author
  once per player (§8d, *Reactions*), and guests cost nothing to mint (§8a), so a script minting
  guests could pay one author a point per guest for each of their questions. The user accepted that:
  every like held pays, whoever holds it, and the only bound is guest minting's per-address budget.
  Dislikes cost nobody anything (§8c), so fresh guests' dislikes move only a count.
- **Refresh answers lost past the grace** — *decided 2026-09-24: no time bound* (§8a). The 10
  minutes built first turned a player whose refresh answer was lost, and who came back later, into a
  fresh guest. What remains, accepted: a refresh that spends the previous token and whose answer is lost
  too leaves a spent token, refused at the next refresh. That takes two lost answers in a row, or a
  settling refresh (`refreshAs`, §8a) whose answer is lost when the stored token was already the
  previous one.
- **WCAG AA contrast audit** — see §5b. Paused along with UI polish (§8d).
- **Local questions** — *design decided 2026-09-26; not built.* The game is for Serbia first and
  more countries later. Most questions translate, but some matter only in one place: a region of
  several countries (the former Yugoslavia), one country, or one city. Such a question reaches only
  the players it concerns, and no player is ever asked to pick a region. It is not a category: no
  player sees it or filters by it. Build it with question translation (§8f, *Not translated yet*),
  since both decide which questions a player is served and in what text; nothing needs it until a
  second country plays.
  - *Places* are server data the moderator manages, as categories are (§8d, *Categories*): each an
    id, a kind, and a name in Serbian and in English. A **region** is a named group of countries
    (`EX_YU`: RS, HR, BA, ME, MK, SI), and a country may be in several (`EX_YU`, `BALKAN`); a
    **country** is its ISO 3166-1 code, seeded; a **city** belongs to one country (`RS_NOVI_SAD`).
    On the wire a place is its id, a plain string, never an enum (§5).
  - *A question's audience* is any number of places, none being global, the default. A local
    question is written once, in its place's language, and never translated. The moderator sets
    the audience when approving, as they may change categories, and may change it later; the author
    is not asked. The players' DTOs never carry it, the moderator's do. Most questions about a city
    are known across its country (every Serb knows Knez Mihailova), so the moderator gives those
    the country and keeps a city for the truly local. The questions there when it lands stay global
    until the moderator gives them an audience.
  - *A player's places*: their country, detected (below) and kept on the player
    (`players.country`) once set, so travelling changes nothing and a registered player carries it
    to a new device; every region that country is in; their city, if they set one; and the country
    of the language they play in, where it has one (Serbian, either script, → RS; English none), so
    Serbian questions reach Serbs abroad who play in Serbian (the user: yes). A player of no known
    country gets what their language and city bring them, and every global question.
  - *The feed* serves a question when its audience is empty or shares a place with the player's:
    one more predicate beside the category filter. Local questions join each cycle's random order
    like any other (§8d, *Endless feed*).
  - *A city is never detected*: an address's city is unreliable (Serbia's mobile networks mostly
    read as Belgrade), and GPS needs a permission prompt. So it is opt-in, a quiet *Град* row on the
    Account screen, beside a *Земља* row that overrides a wrong country; neither is asked up front.
    Later, once a city has enough questions, a one-time *Одакле си?* card in the feed, which the
    player can skip.
  - *Open — user decision: detecting the country.* Recommended: the country Cloudflare reads from
    the client's address, `CF-IPCountry`, taken once when the guest is minted: the same on all four
    platforms, and in one place on the server. Whether Render passes that header on is to be checked
    on dev with one log line; if not, an offline IP-to-country database, a new dependency to decide
    then (§2, §4). Weaker: the device's region, which on Android usually comes with the language (a
    Serb with an English phone reads as `US`), which a browser rarely has at all, and which departs
    from §8f's *nothing reads the device's locale*.
  - *Rollbacks*: a build before the migration ignores audiences and serves local questions to
    everyone. Accepted (the user: the game is not released yet).

`RANDOM` was an open item, resolved twice. First as a content category (the absurd questions), not a
"surprise me" filter. Then, *decided 2026-09-25*: "RANDOM is actually all", so RANDOM is no category
any more. Picking none, *All*, is the unfiltered feed, which mixes every category, and the absurd
questions filed under RANDOM moved to a new category, **ABSURD** (sr *Апсурдно*, en *Absurd*; V6,
§8d *Categories*). An installed build that still asks for RANDOM, as a filter or to submit under, is
400 `VALIDATION_FAILED`.

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
  boot on it records `1 BASELINE`. That was `d4a9dbf`, deployed 2026-09-25, which ran V2 (the
  refresh-token grace window, §8a), V3 (a question's `retired_at`, §8d *Moderation*) and V4
  (sessions and the recovery secret, since dropped) there; the next Manual Deploy runs V5 there
  (a player's username and password hash, *Accounts*, above), which every player already there takes
  as a guest, and every later script: V6 (the categories table, §8d *Categories*), V7 (what a
  question cost, §8c), V8 (the seeds' made-up votes), V9 (the seeds in Serbian, §8d *Seeds*) and
  V10 (likes become reactions, §8d *Reactions*).
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
  minted one (`playerAsMintedBefore`, V4). The seeds go in as the builds before V6 wrote them, in
  plain SQL (`seedAsBefore`), since the seed now needs the categories table.
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
  holds is one as well: V4 moved refresh tokens into `sessions` and kept the `players` columns every
  build before it refreshes from as a mirror of the session written last. The mirror is gone since
  `feat/simple-accounts` (*decided 2026-09-25*): **a rollback to a build from before sessions (any
  before V4) is not supported**, since nothing keeps the refresh columns such a build refreshes from,
  so it would refuse every device and each would mint a fresh guest. A rollback to `d4a9dbf`,
  production's build before this one, still works: it reads the sessions this build writes. The
  unused columns stay declared (`Players`) and no statement names them, so the later migration that
  drops them leaves a schema this build runs on (`ApiFlowTest`). V5 only adds two nullable columns and
  a unique constraint on one, which `d4a9dbf` never names: after a rollback a registered player plays
  on through their sessions as a guest would, and cannot log in anywhere new until the roll forward.
  V6 adds `categories`, which no build before names, moves what was filed under RANDOM to ABSURD
  and holds `question_categories` to `categories` with a foreign key. A build before it (`d4a9dbf`,
  `40550e9`) boots and serves on that: it reads a stored name it has no enum member for as RANDOM,
  so it shows an ABSURD question, or one under a category added later, as RANDOM, and its RANDOM
  filter, which compares the stored names, finds none of them. A submission or an approval it files
  under RANDOM fails the foreign key, a 500, until the roll forward. V7 and V8 only add NOT NULL
  columns with a default, which a build before never names, so it charges nothing for a submission
  and pays nothing back for a rejection, and its tallies leave out the made-up votes; V9 only
  rewrites the seeds' text. V10 moves every like into `reactions`, as a like, and drops `likes`, which
  every build before it reads, so none of them runs on what it leaves: accepted, since nothing is live
  yet and so no build before it is a rollback target (the user, 2026-09-26). `MigrationsTest` reads a
  table a later script dropped by name (`DROPPED_TABLES`), since `Tables.kt` no longer names it.
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
  their own likes included, paid when the like is added and taken back when it is removed, a dislike
  replacing it included (§8d, *Reactions*). A seed has no author and pays nobody.
- A **dislike** earns and costs nobody anything (*decided 2026-09-26*): a guest costs nothing to mint
  (§8a), so a dislike that cost its author a point would let a script drive any author below zero,
  and so stop them submitting. It is a count, for now for the players and the moderator to see.
- Submitting a question **costs** its author `Scoring.SUBMISSION_COST`, **1 point** until the game is
  released (*decided 2026-09-25*), taken in the submission's own transaction (§8d, *Submitting*). The
  number is the wire's, `WyrApi.Limits.SUBMISSION_COST`, so a client can say what it is (the game's
  one copy, `SubmissionRules.SUBMISSION_COST`, is on the Submit form's button, §8d); only the server
  charges it. A player needs at least that many points to submit, or it is 409
  `NOT_ENOUGH_POINTS` and costs nothing. A rejection pays the cost back, in the decision's
  transaction; an approval keeps it, and so does a retirement. Each question keeps what it cost (`questions.submission_cost`, V7), and a
  rejection pays back that, not the constant, so a question submitted before submitting cost
  anything (V7 gave every question there 0) pays back nothing, and one submitted at 1 pays back 1
  whatever the cost is by then. A seed costs nothing and pays nobody.
- So a player's total is always what their answers earned, plus a point for each like their
  questions hold, less what their questions not rejected cost them (`PlayerStatsDto.pointsSpent`),
  which `GET /v1/me` reports in one read. Retiring a question takes nothing back (§8d,
  *Moderation*): its answers' points stay, and so do its likes, held and paid and counted in its
  author's likes received, so the sum holds over every question, retired or not. Two edge cases,
  accepted: taking a like back, or a dislike replacing it, can take an author who spent their points
  below 0, and a submission can then
  wait until they earn it back; and after a rollback to a build before V7, a rejection made there
  pays nothing back, so its author is short that point for good. One more, where two rules meet:
  an author is paid for their own like (§8d, *Reactions*), so liking their approved question gives its
  cost back. *Decided 2026-09-25: keep it.* The cost is 1 point only until release and will rise,
  and with thousands of questions an author rarely meets their own.
- `PlayerStore.addPoints` adds in SQL (`total_points = total_points + n`), never as a read then a
  write, so two votes by one player landing together cannot lose a point, nor a burst of likes for
  one author. `PlayerStore.spend` takes a cost the same way, as a compare-and-set on having it
  (§4).
- The "with the crowd" verdict is `VoteOutcome.agreedWithMajority` on the client (an exact tie
  counts as agreeing). No points depend on it, so the server keeps no copy of the rule, and since
  the Play screen's redesign nothing shows it (§8d, *The Play screen*).

Deliberately lives in `:server` and not `:core:domain`, so `:server` needs no dependency on the
client's domain module and the §3 graph stays intact.

## 8d. Game mechanics — decided 2026-09-23

Settled with the user. Each rule says whether it is built. The branch that builds a rule updates
it here (and §8c, for scoring) in the same commit.

**Principle.** This is not a guess-the-majority game. Rewarding majority picks teaches players to
answer what they think is popular instead of what they actually prefer. A mode that explicitly
rewards reading the crowd may come later as a separate, opt-in mode, never as the default.

**Current focus.** UI polish is paused. The game's own screens are the app: **Home**, **Play**,
**Account**, **Submit** and the **Auth** page opened from Account, and the **Categories** screen
opened from Play, reached from one another by buttons (*Navigation*, below), in every build, LOCAL,
DEV and PROD alike, opening on Home. The engineering dev console functionality was first built
behind is gone since `chore/remove-console` (*decided 2026-09-25*: the console is not needed), and
nothing replaces it: a LOCAL or DEV build names its server on the Account screen (§8e), and a
feature is tried through the game and the moderation app. A new feature gets a plain screen of the
game's, or a place on one, theme tokens only (§5b), and its words in `Strings` (§8f).

**Navigation** (*decided 2026-09-25*: no tabs; `App.kt`, `io.ntole.wyr.navigation`, `io.ntole.wyr.home`):
- The app opens on **Home**: the game's name, a big **Play** button and the account icon top right,
  and nothing else, the user asking for less text. Play opens the **Play** screen under a top bar of
  the home icon, left, back to Home, the categories played in its middle, which open the
  **Categories** screen, and the account icon, right. The account icon, from Home or
  Play, opens the **Account** screen under a top bar of a back arrow. On it, a guest's one button
  opens the **Auth** page, to register or log in, and My questions' *Ново питање* the **Submit**
  screen's form. On Play, the categories played open the **Categories** screen (*Categories*, *The
  Categories screen*), whose **Играј** goes back to Play. The Account, Auth, Submit and Categories
  bars hold a back arrow alone (`BackTopBar`); the Submit button the Account bar held before is
  gone. The icons are the theme's (§5b), each named for a screen reader in the language shown (§8f).
- *The back stack* is made by hand, no navigation library: a sealed `Screen` and a `Navigator` of
  the screens opened, Home at the bottom. `open` shows a screen over the one shown, or goes back to
  it when it is on the stack already, so no screen is on it twice and the home icon is
  `open(Screen.Home)`; `back` goes to the screen before, and at Home does nothing. The stack is saved
  state (`Navigator.Saver`), so an Android activity made anew, on a rotation say, shows the screen it
  showed. `NavigatorTest` pins it.
- *Android's back*, button or gesture, goes back through the navigator (`SystemBack`, over
  `BackHandler` from the catalog's `androidx.activity:activity-compose`, in `:app:shared`'s
  androidMain); at Home it is left to the system, so it leaves the app. Desktop, the web and iOS bind
  nothing: their on-screen buttons are the way back.
- *ViewModels* belong to the platform's owner, the activity's or the window's, as under the tabs,
  never to the back stack: each screen's lives as long as the app, so Play keeps its question
  through Account and back, or Home and back, and Account, Submit and Categories read the server
  again each time they are shown; the Auth page is on the Account screen's, and the Categories
  screen starts each visit afresh from what is played (`CategoriesViewModel.open`, which Play's tap
  calls before it opens the screen). `AppNavigationTest` drives the whole `App` over fakes by
  tapping its buttons, and counts the questions asked.
- *Heights*: each top bar is `WyrDimens.topBarHeight` high, 48, the tab row's height before it, so
  the Play, Account, Auth, Submit and Categories screens keep the 599 of an iPhone SE's 667 their
  draw tests hold them to. `TopBarsDrawTest` holds every bar to 48 at 375 wide with nothing cut
  short but a long selection of categories on Play's, cut on its one line, in both themes and every
  language; `HomeScreenDrawTest` holds Home to 599 at 375 wide (276
  on this Mac) and to its two texts and one icon.

**Wide screens** (*decided 2026-09-26*: of two options, the user picked the cards side by side over
the row with the top bar as it is, the other being the row moved into the top bar; and the other
screens' content held to a column): what a screen does with more width than height, a phone on its
side, a tablet on its side or a desktop window, decided by the room it gets, never by the device's
orientation, in common code alone:
- *The Play screen* stands its two cards side by side, card A first, `WyrDimens.spaceMd` apart and
  sharing the width, with the row under them across the whole width, when the room inside its padding
  is wider than tall and at least `WyrDimens.wideLayoutMinWidth`, 600, across: a phone on its side
  (an iPhone SE's gives 627), a tablet on its side, and a desktop window, which Compose opens at 800
  by 600. On anything else, a phone held upright above all, a tablet held upright or a narrow window,
  they stay stacked around the row. Stacked on a phone on its side, a card got about 90 high, too
  little for a revealed option of two lines, whose percentage was cut off. The top bar stays as it is.
- One layout does both, `QuestionLayout`, a custom `Layout`: `BoxWithConstraints` would decide the
  same, but a subcomposition cannot answer the intrinsic heights the draw tests measure the screen by.
  The cards are then the same composables whichever way they stand, so a window resized across the
  rule, or an iPhone turned, keeps a reveal's count up where it is; an Android activity, made anew on
  a rotation, counts it up again. Each card's bar in the reveal stands along its edge by the row: side
  by side, along both cards' bottoms, card B's too, which stacked stands along its top; the Play
  screen asks QuestionLayout's rule of the size it was last laid out at (`standsSideBySide`).
- *The Account screen, the Auth page, the Submit form and the Categories screen* hold their content to
  a column `WyrDimens.contentMaxWidth`, 600, wide down the middle (`contentWidth`), placed after the
  scroll, so the whole width still scrolls; the Categories screen's list scrolls in its column. Home is
  centred already and stays as it is.
- `QuestionLayoutDrawTest` holds the rule to boxes of known sizes, which no font changes: side by side
  from 600 across while wider than tall, stacked at 599, when square and on a tablet held upright, and
  the height it needs by the same rule. `PlayScreenDrawTest` draws every state on two phones on their
  side and in a desktop window, holds each state to the room a 360-by-780 phone on its side (720 by
  256) and an iPhone SE on its side (667 by 327) give, measured wider for CI's fonts as the portrait
  test is, finds the row under the cards, the points under card A, Skip under card B and the thumbs
  in the middle of the screen, card B's bar along its bottom, and nothing in the row moved by the
  reveal. The Account, Auth, Submit and Categories draw tests find every text, button
  and field in a column of 600 down the middle of a desktop window's 800, and something spanning it.

**The Account screen** (`io.ntole.wyr.account`; §8a *Accounts*, *Stats* below):
- *Its order* (*decided 2026-09-25*, the user's redesign, with less text overall): the player on a
  card, with a guest's one button to the Auth page; **My questions**, a table; the **language menu**
  (§8f, `LanguageMenu`) and beside it the **Statistics** switch (§8g), one row of the two, and under
  them **Log out**, for a registered player (*provisional — user decision*, §8b: Log out was beside
  the menu until the switch took its place); and the server line, outside PROD.
- *The card* (*redesigned 2026-09-26*, the user: "a bit nicer... later more things will be added to
  it"): the player's initial in a circle, the first letter of the username in capitals, or a guest's
  figure (`Avatar`); the username, or *Гост*; and on the right the points, the coin and the number
  (`PointsAmount`, §8f), read through `GetPlayerStats` each time the screen is shown, since the points
  move on Play meanwhile. Under a line, the stats, two to a row so more fit as they come
  (`statCells`): one for now, *Одговорена питања*, the distinct questions the player has answered.
  The answers given, the cycle and the likes received left the card (the user: "Remove cycles", "no
  need for two fields saying the same", "Likes can go to questions table"); not what the player's
  questions cost either (`pointsSpent`, *Stats*, provisional). A screen reader reads each number with
  its word. A guest gets **one button** on the card, *Региструј се или се пријави*, to the Auth page
  (below), instead of the forms; a registered player gets **Log out** under the language menu, after
  which the device plays on as a fresh guest. A read that fails says so under the card, beside its
  *Покушај поново*, or in the card's place before any read worked; a read that failed whole, the
  list's too, says so once.
- `AccountScreenDrawTest` holds every state with no question listed to 599 high in every language,
  measured 400 wide as `PlayScreenDrawTest` measures and for DEV, whose server line is the longest
  (574 on this Mac at the tallest, a registered player whose read again failed), and New question
  above 599 however long the list; a list scrolls with the screen. It finds the language menu and the
  switch on one row, neither cut short, and Log out under them, in every state and language.
- **The Auth page** (`AuthScreen`, *decided 2026-09-25*), on the Account screen's ViewModel, shows
  **Register** only (username, and password with a show/hide toggle), which keeps the points, and a
  link, *Већ имаш налог? Пријави се*, that switches the same page to **Log in** (username, password),
  with a link back, *Немаш налог? Региструј се* (`AccountState.authMode`, Register on a first
  showing; not while an action runs). No heading and no notes, the user asking for less text. Under
  each register field its rule, *3–20 знакова: a–z, 0–9, _* and *6–128 знакова*, in the error colour
  while what is typed breaks it, and Register sends nothing until both pass. A register or a login
  that worked goes back to the Account screen, which reads the player again (`AccountState.signedIn`,
  which the page takes down as it goes and the next action takes down too, so a page left before its
  answer came is not sent back later). Shown with no player read yet (an Android process brought
  back on it), it reads the player first (`authShown`), so the warning below knows the points: a read
  that fails says so on top, with *Покушај поново*, and Log in stays off until a player is read
  (`AccountState.canLogIn`). `AuthScreenDrawTest` holds every state to 599 high, measured 400 wide,
  in every language.
- A refusal from the server shows under the form that sent it, in a few words: a taken name, a wrong
  login, a rate limit with its wait, offline. A guest with points who logs in is warned once that the
  guest's points stay behind, *Поени госта (12) неће прећи на налог.*, the 12 after a coin
  (`PointsText`, §8f), and the next *Ипак се пријави* goes ahead; switching forms takes the warning down, and a form's failure with it.
- Each field names its autofill content type (`NewUsername` and `NewPassword` to register,
  `Username` and `Password` to log in), so the phone's password manager can fill them and offer to
  save them once a register or login that worked takes the page off the screen (Compose on Android
  commits autofill when no autofillable field is left, so nothing typed is cleared before then).
  What is typed lives in `AccountViewModel`'s memory only, never in saved state.
- One action at a time, and the player read again after every one, a failed one too: a registration
  whose answer was lost shows as the account it made. A register or a login whose read after it
  names an account goes back to the Account screen as one that answered does, its failure dropped;
  what was typed is gone by then, so the password manager has nothing to save. `AccountViewModelTest`
  drives it over fakes, `AccountScreenDrawTest` and `AuthScreenDrawTest` draw every state in both
  themes and every language, and `AppNavigationTest` registers through the page, an answer lost
  too, and lands back on Account; theme tokens only (§5b).
- **My questions** (`MyQuestions`, *decided 2026-09-25*; a table since 2026-09-26, the user: "Make my
  question section a table, if there is none it still has the empty table calling to make first
  question"), for guests and registered players alike: the player's submissions, newest first, read
  through `GetMySubmissions` after the stats each time the screen is shown and after every action, a
  login's and a logout's included, since the list is the player's (`AccountState.submissions`). A
  **table**: a heading row, *Питање*, then a column each headed by an icon a screen reader names, a
  thumb up for the likes (*Лајкови*), a thumb down for the dislikes (*Дислајкови*) and two players for
  the players who answered (*Одговори*); a row for each question, its two options as *Пица или Бурек*
  and under them its status in a word, *На чекању*, *Одобрено*, *Одбијено* with the moderator's reason
  as they wrote it, *Повучено* (and *Непознато* for a status this build cannot name), then its three
  numbers as the server counted them (`SubmissionDto`, *Reactions*), or a dash, `NOT_SERVED`, for a
  question never served, pending or rejected (`countsOf`); and a last row, *Укупно*, adding the
  columns up (`totalOf`), the author's likes and answers received. A screen reader hears a row as
  one, each number after its column's name, *Лајкови: 5*. With no question the table stays, with one
  row: **Постави прво питање**, to the form, for a registered player, or for a guest *Региструј се да
  додаш питање.* Its heading holds **Ново питање**, which opens the Submit screen's form
  (*Submitting*), a registered player's alone: for a guest it is off, and a guest with questions from
  before the rule is told under it to register first. A list that cannot be read says so under it,
  with Try again, apart from the stats (`AccountState.listFailure`).
- Its last line, in a LOCAL or DEV build, names the server the build talks to and its URL, in the
  language shown, *Сервер: Dev (https://wyr-server-dev.onrender.com)* (`serverLine`, §8e); a PROD
  build shows none. `AccountScreenDrawTest` finds it under everything else in every state and
  language.

**The Play screen** (`io.ntole.wyr.play`; the user's layout, *decided 2026-09-25*, rearranged
2026-09-26) asks a question and reveals its tally, holds Skip and the reactions, and opens the
Categories screen from its top bar (*Skipping*, *Reactions* and *Categories*, below):
- Two answer cards in the brand colours (§5b) and, between them, **one row** (on a wide screen the
  cards side by side over it, *Wide screens*; the user: reactions "in the middle and points to
  left"): on the left the player's points, the coin and the number (`PointsAmount`, §8f); in the
  middle the **thumbs**, a thumb up and a thumb down, each filled while the player holds it and beside
  how many hold it, before answering and after; and on the right **Skip** while the question is not
  answered yet (*Skipping*), its place kept empty in the reveal so nothing in the row moves. The
  **categories played**, *Све* or their names, cut to one line, with a small chevron, are in the middle
  of the **top bar** (the user: "category goes to top bar in middle"), between home and the account
  icon (`PlayTopBar`, `CategoriesPlayed`), and open the Categories screen. No title and no *OR*.
- *The categories' names* are the server's, in the language shown (`categoryName` in
  `io.ntole.wyr.language`, §8f), in the order the server lists them, and one not read yet by its id,
  after the rest (`categoriesPlayed`). On the top bar they have the width home and the account icon
  leave them, about 250 of 375, where the row gave them 115.
- *The row's arrangement* (`CentredRow`): Skip gets its whole width first, then the thumbs, and the
  points what they leave, no wider than `WyrDimens.playRowStartMaxWidth` (88). The thumbs stand in
  the middle of the screen while the points leave them room, and move right only as far as a wider
  start needs, which only a reaction's failure is. *Decided 2026-09-26*, replacing the provisional
  arrangement of the categories, the points in the middle, and the heart.
- A thumb asks for its reaction, or for none when the player holds it already, so a second tap takes
  it back (`reactionAfterTap`; *Reactions*).
- The points are the server's (`PlayViewModel.points`, §8c): read through `GetPlayerStats` each time
  the screen is shown, and moved to a vote's total when its answer arrives, which drops a read still
  in flight; none until the first.
- Tapping a card answers. In the reveal both cards count their percentage up from 0 to its value
  over 2.5 seconds, both at once (`COUNT_UP_MILLIS`), and each card fills a **bar** with it (the
  user, 2026-09-26): along its edge by the row, the bottom of the top card and the top of the bottom
  one, inside the card from one side to the other, 6 high (`WyrDimens.revealBarHeight`) and a little
  in from the edge, past the pick's outline (`revealBarInset`), in the card's text colour on a faint
  track of it (`WyrColors.revealTrackOnA`, `revealTrackOnB`), filling from its start to the card's
  share as the number counts (`RevealBar`, one count for both, `rememberCountUp`). Tapping either card
  again is the next
  question (`PlayViewModel.next`, from the reveal only), once the reveal has shown for half a second
  (`REVEAL_HOLD_MILLIS`), so a double tap cannot answer and skip the reveal: *provisional — user
  decision*, the other option being no hold. Nothing else shows: no verdict, no points of the vote,
  no vote counts (the domain still has `VoteOutcome.agreedWithMajority`). A screen reader hears what
  a tap does where no text says it: *Следеће питање* on a revealed card, *Промени категорије* on the
  categories played.
- *The count up is drawn, not composed* (`CountedUpText`, `RevealBar`): the count is read only as it
  is drawn, the number over the final percentage's own text, laid out once, which sizes it and is
  what a screen reader reads, and the bar in a layer of its own. So a frame of it draws two numbers
  and two bars and nothing else, where a text changed every frame recomposed both cards, laid them
  out again and told any accessibility service, too much for a debug build, several times slower, to
  do 60 times a second.
- One action at a time (`isBusy`, `canChangeCategories`): while a vote, a skip or a reaction is in
  flight, the cards, the thumbs, Skip and the categories are off, and Skip is drawn muted
  (`WyrColors.muted`). A reaction that failed says why in the points' place, in two short lines at
  most, in a slot as high as a thumb's touch target at any font size, so it moves nothing; a skip that
  failed moves on all the same.
- Loading is a spinner; a failure is one short sentence and *Покушај поново* (`Strings.tryAgain`,
  §8f), the categories played on the top bar being the way out of a selection with nothing to serve.
  The words are `PlayStrings` (§8f), but for those the Categories screen says too, *Све* and the
  spinner's name (`Strings.allCategories`, `Strings.loading`).
- The categories are picked on the Categories screen (*Categories*, *The Categories screen*), which
  a tap on them opens; the dialog the Play screen had for them is gone. A selection played there
  drops what is on screen, a question asked or answered or a failure, and loads a question from it
  (`PlayViewModel`'s `init`); while a load, a vote, a skip or a reaction is in flight it changes
  nothing on screen, and the question after it is the new selection's. A vote lost to `NETWORK` is
  never sent again (*Retry safety*). The selection lives in the repository, in memory for the
  app's life: a launch plays every category again, and a login or a logout keeps it.
- `PlayViewModelTest` drives it over fakes, a selection played on the Categories screen included.
  `PlayScreenDrawTest` draws every state in both themes and every language at 400x900 and 375x599
  (an iPhone SE less its status bar and the top bar); holds each state to 599 high, measured 400
  wide rather than 375 since CI's Linux fonts wrap wider than a phone's; reads each state's texts and
  nothing else, the points as a screen reader hears them, and what it hears a tap does; taps the
  cards before and after the reveal and each thumb, asking for its reaction or none; steps the
  scene's clock through the count up, reading the numbers and each bar by their pixels and finding a
  frame of them composing and moving nothing (`CountedUpTextDrawTest`: each number drawn once, in
  order); holds the row to 335 wide with nothing cut short, asked with Skip and answered with its
  place kept, and to one height with a reaction's failure or without at font scales 1, 1.3 and 2;
  finds Skip after the thumbs only while a question is asked, off and drawn muted while anything is in
  flight (by its pixels' colours, in both themes and every language), the thumbs in the middle, and
  nothing in the row moved by the reveal; and holds `CentredRow` to its rule on boxes of known widths,
  which no font changes. `AppNavigationTest` skips through it, under a bar of home, the categories
  played and the account icon, and plays categories picked on the Categories screen.

**The Submit screen** (`io.ntole.wyr.submit`), opened from My questions on the Account screen
(*Navigation*), is the form a question is written in (*Submitting*, below); the player's own are
listed on the Account screen.

- **Scoring** *(built; see §8c)*: every answer earns exactly **1 point**, whichever side
  it picks. There is no majority bonus and no streak: the streak is removed from the server, the
  contract, and the domain. The reveal shows the split, as information only.
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
  - *Categories:* a cycle is **per player, not per category**. The categories a request is filtered
    to are one pool, the questions filed under any of them: while anything in it is due, only what
    is due is served. A request whose pool has nothing due, while other questions still are, serves
    the pool's questions again, in random order and `answeredBefore` on those answered, and leaves
    the cycle alone: starting the next one would cut short the player's pass over the rest. The
    cycle starts only once nothing at all is due, whichever categories are asked for, and a request
    that finds no questions starts nothing. Questions skipped this cycle are served again this way
    too, so a skip does not hold through a category filter: *provisional — user decision* (§8b). The
    client selects any number (`QuestionRepository.setCategories`, from the Categories screen), none
    for every category; a change drops the queue, and a login or a logout keeps the selection.
  - `answeredBefore` (`QuestionDto`) means the player has a vote on the question, from any cycle. No
    client reads it: the domain's `Question` has no such field since the dev console went.
- **Categories** *(decided 2026-09-24; server data since 2026-09-25; built)*: a
  question is filed under **any number of categories, at least one**. A player may pick **several**
  categories to play, and a question matches when it is filed under **any** of them; none picked
  means every category. The author picks one or more when submitting, and the moderator may change
  them (*Moderation*).
  - *Server data* (*decided 2026-09-25*: there will be hundreds): each category is a row of
    `categories` (V6), a **stable id** (1 to `WyrApi.Limits.MAX_CATEGORY_ID_LENGTH`, 32, of `A`-`Z`,
    `0`-`9` and `_`), a name in **Serbian** (Cyrillic, `name_sr`) and one in **English**
    (`name_en`), each at most `MAX_CATEGORY_NAME_LENGTH` (40), and when it was added. The **order of
    categories** is when each was added, then its id (`CategoryStore.ids`): every list of them the
    server sends, a question's own included, is in it. V6 wrote the first five, `Seed.CATEGORIES`:
    `FOOD`, `LIFESTYLE`, `ETHICS` and `SUPERPOWERS` under the names the enum sent, so an installed
    client reads every one as before, a millisecond apart in its declaration order, then `ABSURD`
    (*Апсурдно*, *Absurd*), which took every question filed under RANDOM (§8b: RANDOM is no category
    now, *All* is no filter). Eight more came with the second seeds (*Seeds*), written by the seed,
    not a migration, a millisecond apart from 2026-09-26 (`Seed.ALL_CATEGORIES`): `TRAVEL`
    (*Путовања*, *Travel*), `WORK` (*Посао*, *Work*), `MONEY` (*Новац*, *Money*), `LOVE` (*Љубав*,
    *Love*), `TECHNOLOGY` (*Технологија*, *Technology*), `SPORTS` (*Спорт*, *Sports*), `ANIMALS`
    (*Животиње*, *Animals*) and `GROSS` (*Гадости*, *Gross*). Nothing deletes a category. On the wire a category is its id, a plain
    string (§5).
  - *The moderator* adds a category with `POST /v1/admin/categories` (`CreateCategoryRequest`,
    answered 201 with its `CategoryDto`) and sets both its names with
    `POST /v1/admin/category-renames` (`RenameCategoryRequest`, answered with it as it now stands),
    both admin routes (*Moderation*: the admin token, the admin rate limits). Each name is trimmed,
    then 1 to 40 and one line, as an option is. The id is given, or derived from the English name:
    accents off, upper-cased, every run of anything but `A`-`Z` and `0`-`9` one `_`, none at either
    end, cut to 32 (`categoryIdFor`: *Fast food* is `FAST_FOOD`); a name of no Latin letter or digit
    needs one given. Anything the rules refuse is 400 `VALIDATION_FAILED`, as a rejection's reason is,
    since the moderation app checks first; an id a category has already, given or derived, 409
    `CATEGORY_EXISTS`, the primary key deciding two creations racing (§4); a rename of an id no
    category has 404 `CATEGORY_NOT_FOUND`. A rename never changes the id, so what is filed under it
    stays. No delete, for now. `CategoryRules`, `CategoryStore.create` and `rename`,
    `CategoryRulesTest`, `CategoryStoreTest`, `CategoryFlowTest`. On the client, `ModerationApi`
    `addCategory` and `renameCategory`, behind `AddCategory` and `RenameCategory` (`:core:domain`,
    through `runApi` alone, as every moderator's call), answered with the `Category` as stored;
    `CATEGORY_EXISTS` and `CATEGORY_NOT_FOUND` are `DomainError`s of their own. `CategoryRules`
    (`:core:domain`) copies the id's and the names' rules, so the moderation app checks before it
    sends (`CategoryLimitsTest` pins its numbers to `WyrApi.Limits`), but not the derivation, whose
    accent stripping needs Java's `Normalizer`: an id left blank is sent as none, and the server's
    answer names the id it made. An English name that derives nothing, sent with no id, is the
    server's 400, which the moderation app shows as a server failure; the id field's hint says to
    type one then.
  - *The list*: `GET /v1/categories` (`WyrApi.Paths.CATEGORIES`) answers every category, a
    `CategoryListDto` of `CategoryDto`s (`id`, `nameSr`, `nameEn`), in the order of categories. It
    needs no session and reads none, so a client can have it before it has a player, and it is
    limited per address (§8b). *Decided*: ordered by when each was added, not by Serbian name, which
    would sort differently on H2 and PostgreSQL and by each database's collation; a client sorts by
    the name it shows, in the player's language, if it sorts at all. `CategoryStore.all`,
    `CategoryFlowTest`. On the client it is `CategoryApi.all`, behind `CategoryRepository`
    (`:core:domain`, `DefaultCategoryRepository`) and `GetCategories`, a `Category` each (its id and
    both names): read through `runApi` alone, never `withSessionRecovery`, so no read ensures,
    recovers or mints a session, kept in memory for the app's life (`CategoryRepository.categories`,
    empty until a read works, left as it was by one that fails) and read again whenever
    `GetCategories` is asked, as the Categories screen and the Submit form do each time they are
    shown. Both `dataModule` and `moderationDataModule` bind it (`DataModuleTest`).
  - Built in `question_categories`, one row per question and category, written in the question's
    own transaction, each row held to a category by a foreign key (V6): `QuestionDto.categories`,
    `SubmissionDto.categories` and `AdminQuestionDto.categories` carry every one, each once, in the
    order of categories. A batch reads its questions' categories in one more statement
    (`QuestionStore.categoriesOf`), never one per question. The feed takes a filter of any number of
    categories, `?category=` repeated, and none is every category; an id no category has is 400,
    RANDOM included. Every filter, submission and approval is checked against the categories in its
    own transaction (`CategoryStore.checked`, which reads every category, so what a request names
    never sizes the statement). The filter, and the due count beside it (`QuestionStore.dueCount`),
    is an `EXISTS` on that table, never a join, so a question in several of the categories asked for
    is served and counted once. A submission names one or more (*Submitting*).
  - *The client* lists the categories from the server (*The list*, above) and names nothing itself:
    the enum is gone, and so is `OTHER`, the bucket for what a build could not name. A question, a
    submission and a moderated question hold the ids of their categories (`categories`, a set, in
    the order the server sent them, mapped in `QuestionMapper`), whether or not a category of that
    id has been read; a screen names each by the list last read (`CategoryRepository.categories`)
    and shows one it has not read by its id. The server files every question under at least one; a
    payload without them, which no server sends, reads as none rather than failing. A player's
    selection is a set of ids too (`QuestionRepository.categories`, empty for every category), and
    every refill sends all of it, in id order, so one selection is always one request. The Play
    screen plays the selection whoever sets it (`PlayViewModel`'s `init`): a new one drops what is
    on screen, a question asked, one revealed (in its first half second too) or a failure, out of
    questions or a vote lost to `NETWORK`, which is never sent again (*Retry safety*), and loads a
    question from it (`load`, not `next`, which goes on only from the reveal and waits out its first
    half second); a load, a vote, a skip or a reaction in flight there goes on and changes nothing more
    on screen, and the question after it is the new selection's, since the change dropped the queue
    (`canChangeCategories`; `PlayViewModelTest` plays a selection from each of those states). The
    Categories screen ticks any of the categories the server lists, and *Све* empties it (*The
    Categories screen*, below). Ticking every category is not selecting none: a category a moderator
    adds later is in none and not in those ticked. Nothing checks an id against the list before
    sending it: an id no category has is the server's 400, which only a stale client could send,
    since ids never change and no category is deleted. A client submits under a set of one or more
    (*Submitting*). The game names a category in the language shown (§8f), through one function,
    `categoryName` in `io.ntole.wyr.language`, the one place the language is chosen, on the Play
    screen's row, the Categories screen and the Submit form's chips alike: the Serbian name as the
    server keeps it in Serbian Cyrillic, that name through `SerbianScript.toLatin` in Serbian Latin,
    and the English name in English. The moderation app names it in Serbian (`nameOf`).
  - *The Categories screen* (*built 2026-09-25*; the user: "Category needs its own screen for
    picker, as there will be hundreds of categories, and players should be able to pick multiple,
    random is actually all. There should be also category search."): `io.ntole.wyr.categories`, a
    `Screen` of the navigator's own (`Screen.Categories`), opened by a tap on the categories played
    on the Play screen, in place of the dialog the Play screen had for them, under a top bar of a
    back arrow (`BackTopBar`). Top down: a search field, then one lazy list (a `LazyColumn`, so
    hundreds draw only the lines on screen) of **Све**, ticked while no category is, and every
    category the search finds, each ticked or not, in the server's order; and at the bottom how many
    are ticked (*Изабрано: 3*, nothing while Све is) and **Играј**. Ticking Све unticks every
    category, ticking one unticks Све, and unticking the last is Све again: Све is none ticked, as
    the repository holds it. The search filters as it is typed, by any part of either name, whatever
    the script, the case and the accents of either: a query and each name are compared in Serbian
    Latin, lower-cased, and then without accents (`searchKey`, through `SerbianScript.toLatin`), so
    *hra*, *Хра* and *HRA* find *Храна*, *lj* finds *Љ* and *рок* a name typed in Latin. *Accents
    fold* (*decided 2026-09-25*: the players' phones may lack a Serbian keyboard): č and ć into c, š
    into s, ž into z and đ into dj, on both sides and in the English name too, so *nacin* finds
    *Начин живота*, *djak* and *đak* find *Ђак*, *dzu* finds *Џунгла* and *ćevap* a name a moderator
    typed as *Cevapi*. A category the search hides stays ticked, and a search that finds none says
    so under Све. Each visit starts from the categories played, nothing searched
    (`CategoriesViewModel.open`, called by the Play screen's tap, so a rotation keeps what is
    ticked), and reads the list (`GetCategories`) as it is shown: a read that fails says so above
    the list, *Игра није доступна.* offline, as the Play screen says it, and *Категорије нису
    учитане.* otherwise (`unreadText`), with *Покушај поново* (`Strings.tryAgain`), the categories
    read before staying to tick, and with none read before a spinner shows while it reads. **Играј**
    sets what is ticked in one `QuestionRepository.setCategories`, waits for it to land, the list
    and Играј off meanwhile, then goes back to the Play screen, which shows a question from it (*The
    client*, above); what is played already is not sent again, so the question stays. Back, the
    arrow or Android's, plays nothing. `CategoriesViewModelTest`, `CategoriesScreenDrawTest` (every
    state in both themes and every language at 400x900 and 375x599; with 301 categories at 375x599
    the search field and Play on screen, nothing cut short, only the lines that fit composed, and
    the list scrolled to its last), `AppNavigationTest` (Play, Categories and back, played or not),
    `NavigatorTest`, `TopBarsDrawTest`.
  - *A known limit:* the game's picker is a screen of its own, searched and lazy (*The Categories
    screen*), but the Submit form, the moderation app's category filter and each pending card lay out
    every chip in place, to be scrolled past, which suits tens of categories, not the hundreds
    planned; a searchable or collapsible picker for them comes with UI polish.
- **Re-answering** *(built)*: a question can be answered again, whether or not the feed has
  served it again. It earns the point again **every time**, inside its cycle or not (farming is
  bounded by rate limiting, 120 votes a minute per player on average, §8b), and the player may
  change their pick. Every answer, first or not, counts for the player's current cycle. The tally
  always holds **one vote per player per question**, their latest, beside a seed's made-up votes
  (*Seeds*). Built in `VoteStore.cast`, which moves the player's vote.
- **Retry safety** *(built)*: every vote carries a client-generated idempotency key. A repeat of
  the key last recorded for that question is replayed: nothing is written, it pays nothing, and it
  reports the stored side with the current tally and total, not the result first returned. Any other
  key is a fresh answer. Only the latest key per question is kept, so an older key arriving after a
  newer answer is a fresh answer too: it pays again and moves the vote back to its side. The app
  never resends an older attempt after a newer one, so only a duplicate from the network or a
  modified client can do that. Built in `VoteStore.cast` and in `AttemptId`, made once per tap: the
  Play screen resends a vote lost to `NETWORK` as the same attempt, as `withSessionRecovery` does
  its retry, when the player taps *Покушај поново*. Changing the categories from that failure moves
  on instead (*The Play screen*) and abandons the attempt: the vote counts only if the first send
  landed, and nothing pays twice.
- **Stats** *(built)*: `GET /v1/me` reports the session player's total points, answers given
  (every paid answer, re-answers included and replays not, in `players.answers_given`, an SQL
  increment beside the points), distinct questions answered, current cycle, and how many questions
  are still due in it, counted by the feed's own predicate (`QuestionStore.dueCount`), and the
  likes received: how many likes the questions the player submitted hold now, their own included
  (`ReactionStore.likesReceivedBy`, *Reactions*; dislikes are worth nothing, so not counted), the
  points spent: what the player's questions not rejected
  cost them (`pointsSpent`, *Submitting*), and the player's username, null for a guest (§8a,
  *Accounts*; `PlayerStats.username` on the client, so a screen reads the name with the points).
  Built in `StatsStore.of`, as one statement, so the total always agrees with the answers given, the
  likes received and the points spent (§8c). It only reads, and the cycle starts lazily on the next
  feed request, so between the answer that finishes a cycle and that request it reports the finished
  cycle with nothing due. The Account screen shows the points and the questions answered (*The
  Account screen*), and not `pointsSpent`, for less text (*provisional — user decision*, §8b); the
  likes and answers the player's questions received are in My questions' table, question by question
  and added up, read with the list rather than with the stats. The client reads only the points, the
  questions answered and the username: the domain's `PlayerStats` has no field for the answers given,
  the cycle and what is due in it, the likes received, the points spent or the player id, which the
  server still sends, the cycle's two for its own tests.
- **Skipping** *(built; decided 2026-09-23)*: allowed, earns nothing, and never touches the
  tally. The server **records the skip for the player's current cycle only**, so the question is
  no longer due in that cycle and comes back in the **next** one, except through a category filter
  with nothing due in it (*Categories* above; provisional, §8b). A player is therefore never
  stuck at the end of a cycle on a question they keep skipping. Built as `POST /v1/skips` in
  `SkipStore.skip`, on `skips.skipped_in_cycle`, which the feed's due predicate compares with the
  cycle as it does the vote's. The Play screen's Skip, in the row between the cards while a question
  is not answered yet, sends it through `SkipQuestion` and then shows the next question
  (`PlayViewModel.skip`), even when the skip failed, and says nothing of it: the player asked not to
  answer that question, and an unrecorded skip only leaves it due, so the feed may serve it again
  this cycle, where Skip works on it again. Nothing else goes while a skip is in flight, and an
  answered question goes on with a tap on a card instead.
- **Own questions** *(built; decided 2026-09-24)*: an author is served their own questions
  **like any other player** and may answer, skip and react to them; the user chose the simpler logic.
  `QuestionStore.servable` is the one predicate the feed, the due count and votes, skips and reactions
  (`QuestionStore.isServable`) read, and it asks only that a moderator approved the question and has
  not retired it (*Moderation*).
- **Seeds** *(decided 2026-09-25; built; 200 more 2026-09-26)*: the server's starter questions
  (`Seed`), approved from the start, authored by nobody: 224, `seed-1` to `seed-224`, the first 24
  under V6's categories and the 200 after them under those and eight more (*Categories*), 14 to 16
  new in each, some filed under two. **The seed writes what a database lacks, by id**, at every
  boot (`Seed.writeMissing`): a new database gets every seed, and one an earlier build seeded gets
  the seeds and seed categories added since, at the first boot of the build that added them, which
  is how they reach production. Nothing there is written again or changed: a retired seed stays
  retired, and a category a moderator renamed, or added under a seed category's id, keeps its
  names. So a new seed needs no migration; changing one already written does (V8, V9). The server
  tests seed the first 24 alone (`TEST_SEEDS`): they were written against a pool one feed batch
  holds, so seeds added later change none of them but `SeedTest`, which holds every seed to the
  rules of a submission (`checkedSubmission`), to votes of its own and to Serbian Cyrillic, no two
  alike, and every seed category to holding seeds. The seeds are universal, nothing that matters in
  one place only (§8b, *Local questions*). Each comes with **made-up votes**, a count for each side
  (`questions.base_votes_a` and `base_votes_b`, V8), so its split looks like a crowd's from the first
  answer: a different total and split for each (`SeedTest`), 96 to 548 votes, the larger side the
  one a crowd would likely pick. **Every tally the
  server reports adds them** to the players' votes, a vote's answer and the moderator's list alike,
  read in the tally's one statement (`QuestionTally`, §4); every other question has none. They are
  no player's: a player still holds one vote per question, and nothing writes them after V8 and the
  seed. V8 gives the seeds of a database seeded before theirs by id, the same counts `Seed` writes
  into a new one (`MigrationsTest` holds the two equal). No client can tell them from real votes.
  The seeds are in **Serbian**, in Cyrillic (*decided 2026-09-25*), written for Serbian and keeping
  the fun rather than put word for word from the English they began in (`SeedTest` holds every letter
  to the Serbian Cyrillic alphabet), each option phrased as §8f, *How an option is phrased*, asks,
  with no word agreeing with the player's gender. V9 rewrote the English seeds a database seeded before holds, by
  id, into the same texts, leaving their categories, votes and likes alone. A player's own question
  stays exactly as typed; putting questions into other languages is a later, bigger topic.
- **Reactions** *(built; likes 2026-09-24, dislikes decided 2026-09-26)*: any player may **like** or
  **dislike** any question, **their own included**, at any time (before or after answering), and may
  take either back. A player holds **one reaction per question**: liking a question they dislike takes
  the dislike back, and the other way round. Each like currently held is **+1 point to the author**,
  and taking it back, alone or by disliking, takes that point back; a dislike earns and costs nobody
  anything (§8c). Both counts are visible before answering. For now reactions do nothing else; serving
  questions by quality is a later idea. A seed has no author: a reaction to one counts and pays nobody.
  - Built as `POST /v1/reactions` (`WyrApi.Paths.REACTIONS`), in `ReactionStore.set`, on `reactions`
    (V10, which replaced `likes`), one row per player and question while they hold a reaction, its
    kind in `reaction` (`LIKE` or `DISLIKE`), under a primary key on both, so a like and a dislike of
    one question can never both be held. A `ReactionRequest` names the question and the `reaction`,
    `LIKE`, `DISLIKE` or `NONE`, which *sets* what the player holds rather than toggling it, so asking
    for what already holds writes nothing and pays nothing and a retry needs no attempt id;
    `reaction` has no default, and a body without it is 400. `Reaction` is a closed enum on the wire,
    as `OptionSide` is (§5): another kind of reaction would be a field of its own. The answer is a
    `ReactionResultDto`: the question, its `likeCount` and `dislikeCount`, and `myReaction`. A
    question that is not servable is 404 and an unknown player 401, as for votes and skips, and the
    id is checked as a vote's is. That holds for taking a reaction back too: a retired question's
    reactions stay held, and its likes paid, until it is restored (*Moderation*).
  - The reaction held is read under its row lock (`SELECT ... FOR UPDATE`, §4), since what is written
    and what is paid depend on which of the three it is: a second request of the same player's for
    the same question waits, then reads what the first left. Only a like actually added pays the
    author (`Scoring.POINTS_PER_LIKE`, an SQL increment) and only one actually removed or replaced
    takes the point back, in the same transaction. With no row to lock, two first reactions racing
    fail on the key, which Exposed's rerun turns into a repeat, or into a change of mind when the two
    differ (§4). `ReactionStoreTest` races each case.
  - Every feed batch carries each question's `likeCount`, `dislikeCount` and `myReaction`
    (`QuestionDto`), answered or not, read in one more grouped statement per batch
    (`ReactionStore.reactionsOf`), never one per question, and every number in that one statement so
    they agree. `GET /v1/me` reports `likesReceived` (*Stats*). The moderator's list carries both
    counts (`AdminQuestionDto`), and so does the author's own list, with how many players answered
    each question (`SubmissionDto.likeCount`, `dislikeCount` and `answerCount`, read with the
    question in one statement, `SubmissionStore.byAuthor`; a question never served has none, and a
    seed's made-up votes are no author's). Nothing about a reaction touches the tally, the cycle or
    what is due.
  - No author travels on the wire, so the result carries no points: an author sees theirs in their
    stats.
  - *The client* is `ReactionRepository` in `:core:domain`, behind `SetReaction`, which ensures a
    session first, over a domain `Reaction` of its own (`NONE`, `LIKE`, `DISLIKE`; `ReactionMapper`
    maps it to the wire's and back). `DefaultReactionRepository` sends the `ReactionRequest` through
    `withSessionRecovery`, as votes and skips go, and the retry after a recovered session sends the same
    reaction, so a resend can never undo it; nothing resends after any other failure. The answer is a
    `QuestionReactions`. A `Question` carries `likeCount`, `dislikeCount` and `myReaction` as the feed
    served them (`QuestionMapper`), and a queued one keeps them as fetched. A `Submission` carries its
    `likeCount`, `dislikeCount` and `answerCount`, and a `ModeratedQuestion` its `dislikeCount`, which
    the moderation app shows beside the likes (`dislikesOf`).
  - *The Play screen* shows both counts between the cards, asked or revealed, each beside its thumb,
    a thumb up for the likes and a thumb down for the dislikes, filled while the player holds it
    (`PlayViewModel.react`, *The Play screen*). A tap asks for the thumb's reaction, or for none when
    the player holds it already, then puts the server's answer on that question: only on the one the
    answer names, and only while it is still on screen. Nothing changes before the answer, so a
    reaction that failed, one lost to `NETWORK` included, leaves the question as it was, and says why
    in the points' place (*The Play screen*), and pressing again asks for the same again. It works out
    no points itself: a like of the player's own question moves their total without a vote, so the
    points between the cards show it from the next vote on, or from the next time the Play screen is
    shown, which reads them again. The thumbs, drawn by hand (§5b), took the heart's place.
- **Submitting** *(built; details decided 2026-09-23; the cost 2026-09-25; registered players only
  2026-09-26)*: **only a registered player may submit** (§8a, *Accounts*); a guest registers first,
  keeping everything it has. It **costs a point** (§8c) and earns no points directly, because authors
  earn through likes. The author writes both
  options (in Serbian, as §8f, *How an option is phrased*, asks, which the moderator holds them
  to) and **picks one or more categories** (each a category's id; *Categories*). A player may
  have at most **20 submissions pending** moderation at once. A submitted question is served only
  after a moderator approves it; once approved it is due for every player in their current cycle.
  Built as `POST /v1/questions`, in `SubmissionStore.submit` after `checkedSubmission`. A guest's
  submission is 403 `ACCOUNT_REQUIRED` before anything it holds is checked (a plain read of the
  player, `PlayerStore.find`: nothing unregisters one); a guest still lists whatever it submitted
  before the rule. Both options
  are trimmed, then each must be non-blank, at most `WyrApi.Limits.MAX_OPTION_LENGTH` (200, UTF-16
  units) and one line (no control character, nor U+2028 or U+2029, the line and paragraph
  separators), and the two must differ ignoring case: otherwise 422 `INVALID_SUBMISSION`, which the
  player can put right. No category, or an id no category has, is 400 `VALIDATION_FAILED`, and comes
  before any of those, since no correct client sends either: a picker must have one picked before it
  lets the player submit, and offers only categories the server has. A category named twice is filed
  once, and the question's categories are stored in the submission's own transaction. The 21st
  pending submission is 409 `SUBMISSION_LIMIT`, counted under the author's row lock (§4). Then the
  cost is taken, under the same lock and as a compare-and-set (`PlayerStore.spend`, §4): an author
  with fewer points is 409 `NOT_ENOUGH_POINTS`, and nothing is stored or taken; the question keeps
  what it cost. A rejection pays it back (*Moderation*). A submission is stored `PENDING` until a
  moderator decides it (*Moderation*). Questions carry an author and a `QuestionStatus`, and
  `QuestionStore.servable` serves only approved ones, due at once in whatever cycle each player is
  on. `GET /v1/me/questions` lists the author's submissions of every status, newest first, a
  rejected one with its reason and a retired one as `RETIRED` (`SubmissionStore.byAuthor`;
  `SubmissionStatus.RETIRED` on the client). On the client, `SubmitQuestion` and `GetMySubmissions`
  go through `withSessionRecovery` (`DefaultSubmissionRepository`); a guest's refusal is
  `DomainError.ACCOUNT_REQUIRED`, never `UNAUTHORIZED`, which would throw the session away.
  `SubmitQuestion` refuses no
  category before it ensures a session, so nothing is sent, not even a guest's mint, and the
  repository refuses it again before building the request; every other rule is the server's to
  enforce, and `SubmissionRules` (`:core:domain`) copies the options' rules so a form can check what
  is typed, as `AccountRules` does for accounts (`SubmissionLimitsTest` pins its numbers to
  `WyrApi.Limits`). A status this build cannot name is `SubmissionStatus.OTHER`. The game lists the
  player's own on the Account screen, *My questions* (*The Account screen*), and a registered player
  writes one on the **Submit** screen's form, opened from there (`SubmitViewModel`, *decided
  2026-09-25*); a guest cannot open it, and one who reaches it anyway (an Android process brought back
  on it) finds Send off and *Региструј се да додаш питање.* under it, the player being read with the
  points (`SubmitState.registered`), as is the server's `ACCOUNT_REQUIRED`, said once. Under *Шта
  би радије…*, two options and one or more of the categories the server lists, read
  (`GetCategories`) each time the form is shown, before the points, and named in the language shown
  as on Play (`categoryName`, §8f), what `SubmissionRules` refuses in each option shown under it as
  it is typed, and Send off until nothing is refused and a category is picked. A read of the
  categories that fails says so under them in one line, *Категорије нису учитане.* (*Нема интернет
  везе.* offline), with Try again, and those read before stay to pick from; when the points could
  not be read either, the one failure under Send says so, and its Try again reads both. **The cost**
  has one copy on the client, `SubmissionRules.SUBMISSION_COST`, the domain's copy of
  `WyrApi.Limits.SUBMISSION_COST`, which is what `Scoring.SUBMISSION_COST` charges, so a change to
  the cost fails `SubmissionLimitsTest` until the copy changes too (an installed build shows the
  cost it was built with). The form shows it on the button, *Пошаљи ·* and the coin and the number
  (`PointsText`, §8f), which a screen reader hears as *Пошаљи · Поени: 1*, and holds the button off
  while the player's points, read through
  `GetPlayerStats` each time the form is shown and after every submit, are fewer, with one short
  line saying so, *Немаш довољно поена.* The server's own refusal, `NOT_ENOUGH_POINTS`
  (`DomainError.NOT_ENOUGH_POINTS`, points spent meanwhile), is that same line, shown once: the
  points read after it hold the button off again. No other line explains the cost, the user asking
  for less text. A stored question clears the form and goes back to My questions, which reads the
  list again (`SubmitState.sent`, which the form takes down as it goes and the next action takes
  down too), and one stored once the player had gone back is read again by the Account screen if it
  is shown then, which takes `sent` down; a refusal keeps it and says why under it,
  `INVALID_SUBMISSION`, `SUBMISSION_LIMIT` with the 20, a rate limit with its wait, or offline.
  Points that cannot be read say so under Send, with Try again, apart from a refusal
  (`SubmitState.submitFailure`, `pointsFailure`, `categoriesFailure`). One action at a time, and the
  form cannot change while it is sent. `SubmitViewModelTest` drives it over fakes, the categories'
  read and its failure and a refusal for points included; `SubmitScreenDrawTest` draws every state
  in both themes and every language at 400x900 and 375x599, holds a written question under the
  server's first five categories to 599 whole (a longer state scrolls), names the chips in each
  language, and finds the refusal for points said once. `AppNavigationTest` sends one and lands back
  on My questions, and sends one whose answer comes after the player went back, which My questions
  then lists.
- **Moderation** *(built)*: a moderator approves or rejects each pending submission, **may change
  its categories** when approving (*Categories*: at least one stays, and a change replaces the
  question's `question_categories` rows in one transaction), and **may retire an approved question
  and restore it** (*Retiring*, below; provisional, §8b), sees every question (the list), and **adds
  categories and puts their names right** (*Categories*). A
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
    when it was stored, reviewed and retired, a rejected one's reason, its tally, its like count and
    its dislike count, and no author. `?status=` and `?category=` narrow it, each repeated for several and matching any
    of its values, none for all; `UNKNOWN` or a name that is no status, and an id no category has,
    is 400, as for the feed's category (`categoryFilter`, `CategoryStore.checked`). `?limit=` bounds a page as the feed's is. A page is asked for by
    cursor, not offset (`QuestionCursor`, `?cursor=`): `nextCursor` is the last question's place,
    its `submitted_at` and then its id, and null on the last page, which is read as one row more than
    the limit. A question stored meanwhile is newer than every one listed and lands before the first
    page, where an offset would push one already listed onto the next page and list it twice. A page
    is two statements whatever its length (`ModerationStore.questions`): its rows with both vote
    counts and both reaction counts as subqueries in one statement, so a question's numbers are one
    moment's (§4), and their categories in one more. No index orders every question by time: the
    list is the moderator's alone and the table small; `(submitted_at, id)` is the index once it is
    not.
  - `POST /v1/admin/approvals` takes an `ApproveSubmissionRequest`: the id, and categories that, when
    there are any, replace the author's (each a category's id, each once, in the order of
    categories; any other is 400 before anything is decided); none keeps the author's. `POST /v1/admin/rejections` takes a `RejectSubmissionRequest`: the id and a reason,
    trimmed, then non-blank, at most `WyrApi.Limits.MAX_REJECTION_REASON_LENGTH` (200) and one line
    as an option is (provisional, §8b). A reason that breaks a rule is 400 `VALIDATION_FAILED`, not
    422: the moderator's client checks it against the same rules before it lets them send. Both
    answer 200 with the question's `SubmissionDto` as its author now sees it.
  - A decision is a compare-and-set on `PENDING` (`ModerationStore.decide`, §4): a question that is
    not pending, a seed included, is 409 `ALREADY_DECIDED` and changes nothing, an unknown id is 404,
    and of two moderators deciding one submission exactly one wins (`ModerationStoreTest` races
    both kinds). A decision sets `reviewed_at`, and a rejection its reason, which an approval clears.
    A rejection pays the author back what the question cost (§8c), in its transaction and under the
    row lock the decision took, so of two racing only the winner pays it. New categories are written
    after the decision is made and in its transaction, so they commit with it or not at all. An approved question is servable from the commit on: due at once for
    every player in their current cycle, its author included. Nothing moves a decided question on
    but retirement, below: no second look at a rejection, and no approval undone.
  - *Retiring* (*built; provisional, §8b*): `POST /v1/admin/retirements` takes a
    `RetireQuestionRequest`, the id of an approved question, a seed's included, and
    `POST /v1/admin/restorations` a `RestoreQuestionRequest`, the id of a retired one. Both answer
    200 with the question's `AdminQuestionDto`. A retired question is served to nobody and due for
    nobody, so a cycle can finish without it, and a vote, skip or reaction to it is 404; nothing it
    earned is taken back (§8c). Its author sees it as `RETIRED` in `GET /v1/me/questions`, the
    moderator in the queue's and the list's `?status=RETIRED`, never under `APPROVED`. Restored, it
    is servable again from the commit on, due for every player who has neither answered nor skipped
    it in their current cycle. Each is a compare-and-set (`ModerationStore.move`, §4): retire only a
    question standing at approved, restore only one standing at retired, anything else 409
    `WRONG_STATUS` and changes nothing, an unknown id 404, a malformed body 400; of two moderators
    racing exactly one wins. A vote, skip or reaction in flight as it commits may still land (§8b,
    *Retiring a question*). Stored as `questions.retired_at` (V3) beside an `APPROVED` status, never
    as a status: `statusOf` and `standsAt` read the two columns as one status, so a rollback to the
    build before reads every row. The seed writes only what a database lacks and changes nothing
    there, so a retired seed stays retired through every boot. `RetirementTest` pins it, the races included.
  - *The client* is `ModerationRepository` in `:core:domain`, behind `GetPendingSubmissions`,
    `ApproveSubmission` and `RejectSubmission`, none of which ensures a session: the moderator is
    not a player. `DefaultModerationRepository` calls `ModerationApi` through `runApi` alone, never
    `withSessionRecovery`, so nothing a moderator does can refresh or replace the player's session
    (the Auth plugin refreshes only on a 401). Each call sends the `AdminToken` it is given in the
    header, and only that call; nothing stores it, and `AdminToken.toString` shows none of it. The
    queue and every decision come back as the author's `Submission`. An approval names its
    categories by id, in id order, and none keeps the author's categories.
    `RejectionReason` holds only a reason the server accepts, by `checkedRejection`'s rules, so a
    rejection's 400 can only be a bug; its `MAX_LENGTH` copies the wire's limit, which `:core:domain`
    cannot see, and `ModerationMapperTest` pins the two equal.
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
    whether it is a seed, its times as instants, its `Tally`, its like count and its dislike count. A
    filter by
    `SubmissionStatus.OTHER` is refused before anything is sent; its categories are ids, in id
    order. `WRONG_STATUS`
    is `DomainError.WRONG_STATUS`. `moderationDataModule(environment)` binds it, and only there, for a
    client that only moderates: an HTTP client of its own over an in-memory session store nothing
    writes, no `TokenStorage` needed, no session repository, so no bearer token goes out and no guest
    can be minted. The game's `dataModule` binds none of it,
    so the game cannot moderate (`DataModuleTest` pins both).
  - *The moderation app* (`:app:adminApp`, `io.ntole.wyr.admin`, §3) is where a moderator works: a
    desktop window and a browser page on `moderationDataModule` (`adminModules`), so it never has a
    player session, sends no bearer token and mints no guest (`AdminModuleTest`). Its header always names the server and its URL, production's in the error
    colors, and so does the desktop window's title (§8e). The admin token is typed into a masked
    field and held in `ModerationViewModel`'s memory only, never in saved state or storage, and
    `SecretText` keeps it out of the state's text; Lock forgets it and everything read with it,
    and cancels the action in flight, so nothing it answers is shown. The field itself is made
    anew on every Lock (`ModerationState.locks`): a text field keeps its undo history for as
    long as it is shown, so Undo in the one that held the token gave it back (`TokenBarTest`).
    Nothing is sent until what is typed can be a token (`AdminToken.of`), and one action runs at
    a time. Every Load, of either tab, reads the categories first (`GetCategories`, needing no
    token), since a moderator adds them without a build: they are the approval's and the filter's
    chips, and name a question's categories, in Serbian, one not listed by its id
    (`ModerationState.categories`); a read that fails says so above the tab and keeps those read
    before, and Lock keeps them, being the same for everybody. *Pending* lists the queue, oldest
    first, each submission with its options,
    categories and age: Approve files it under the categories picked for it, none keeping the
    author's, and Reject sends the reason typed once it is a `RejectionReason`. The queue is
    read again after every decision, whatever became of it. A read lists at most
    `ModerationRepository.PAGE_SIZE`, and a queue that long says more may be waiting,
    its tab `Pending (100+)`, rather than naming itself the whole. *All questions* is the
    list, seeds included, newest first, filtered by any statuses (`RETIRED` among them; never
    `OTHER`) and any categories, none being every one: Load reads its first page and Load more the
    next, at the filter the list was read at, with the cursor the page before gave, and changing the
    filter drops what was read at the one before. Each question shows its options, categories,
    status, whether it is a seed, its votes, likes and dislikes, its times and a rejection's reason, and what
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
    *Categories*, the third tab, lists every category, oldest first, with its id and both names, and
    adds one and puts one's names right (*Categories*, above): Add, from a form of the two names and
    an id, blank for the server to make one, goes once `CategoryDraft.isValid` holds by
    `CategoryRules`, and clears the form once added; Rename... opens a category's names in its own
    card, its id fixed, and Save names sends them. The categories are read again after every add or
    rename, whatever became of it (the list is no admin route, so the rule above for a 403 or a 429
    does not apply), and a failure shows under the form or the card it came from, a 409 as an id a
    category has already. Lock forgets what was typed for a category and keeps the categories read.
    `ModerationViewModelTest`, `QuestionListViewModelTest` and `CategoriesViewModelTest` drive it over
    scripted repositories,
    `ModerationOverHttpTest` over the real client configuration, and `ScreensDrawTest` draws every
    screen off screen at a desktop window's size. The game's builds do not moderate at all.

## 8e. Client environments — decided 2026-09-24

Every client build targets one of three server environments, chosen **when it is built**, so a phone
can play against the deployed servers and a production build can never talk to a development one by
accident. `WyrEnvironment` (`io.ntole.wyr.core.network.environment`) names them, each with its API
base URL and a display name. It lives in `:core:network`, not `:app:shared`, so a client without the
game UI (the moderation app, `:app:adminApp`, §3) can name one too.

| Environment | Server                                                           |
|-------------|------------------------------------------------------------------|
| `LOCAL`     | `http://localhost:8080`; the Android emulator's `10.0.2.2:8080`  |
| `DEV`       | `https://wyr-server-dev.onrender.com` (§8: in-memory H2)         |
| `PROD`      | `https://wyr-server.onrender.com`                                |

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
  build names no server. No client can put another URL in its environment's place. The moderation app
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
- *In the app.* Koin binds the environment (`appModules`), and `dataModule` sends every request to
  that same environment's URL, so the Account screen's last line in a LOCAL or DEV build, which shows
  its name and URL (`serverLine`), always says where requests go; a PROD build names no server there.
  Every build shows the same screens, Home, Play, Account and Submit (§8d, *Current focus*), and
  Android's launcher label says *WYR Local* or *WYR Dev* besides.
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
- *Analytics* (§8g). Each entry point reads the PostHog project the build sends to where it reads the
  environment's name, and hands both to `initKoin`. Every event names its environment, and an
  install's analytics id is one per environment, as its session is.

## 8f. Languages — decided 2026-09-25

The game is Serbian first (the user: "main language should be serbian"). Its words are written in
**Serbian Cyrillic**, the source text; **Serbian Latin** is made from the Cyrillic, never written by
hand, so the two cannot say different things; and **English** stands beside them.

- **Serbian Latin by transliteration** *(built)*: `SerbianScript.toLatin` (`:core:domain`,
  `io.ntole.wyr.core.domain.language`), Serbian's exact letter-for-letter transliteration. Љ, Њ and Џ
  are Lj, Nj and Dž, and LJ, NJ and DŽ in a word written in capitals (two letters or more, none
  small; a word is a run of letters, so a hyphen or a full stop ends one); Ђ, Ж, Ћ, Ч and Ш are the
  precomposed Đ, Ž, Ć, Č and Š, the accented Ѐ and Ѝ (ѝ, her, beside и, and) the precomposed È and
  Ì, and Dž is two letters, never Unicode's one-character digraph. Latin letters, digits,
  punctuation, spacing and the Cyrillic letters Serbian does not use (Я, Щ, Ы...) come back as they
  were. Pure and in the domain, so a question's text can go through it later. `SerbianScriptTest`
  pins every letter, capital and small, the digraphs in each case, the accented letters, and text
  that must not change.
- **The strings** *(built)*: `Strings` (`:app:shared`, `io.ntole.wyr.language`), a data class of
  every translated text, one value per `Language`: `SerbianCyrillicStrings` written by hand,
  `SerbianLatinStrings` made from it by `Strings.map(SerbianScript::toLatin)`, and `EnglishStrings`.
  `App` provides the one shown through `LocalStrings` (`WyrStrings`), and a screen reads its words
  from there and writes none of its own. Plain Kotlin values, not compose resources: string
  resources cannot express one language made from another by a function, and a text missing from a
  language is then a constructor that does not compile, not a key that fails at run time.
  `StringsTest` compares the data classes' `toString`, which names every text, so a text added later
  is checked too: the Latin is the Cyrillic transliterated, every Serbian text is in Cyrillic, and
  no Latin or English one has a Cyrillic letter. Translated so far: the Home screen, the game's name
  (*Шта би радије?*, *Would You Rather?*) and *Играј*; the top bars and the icons' names (*Почетна*,
  *Налог*, *Назад*); the language menu's name, *Језик*; the Play screen's words (`PlayStrings`,
  `Strings.playScreen`); the Categories screen (`CategoryStrings`, `Strings.categoriesScreen`:
  *Претражи категорије*, *Изабрано: 3* and *Нема резултата*); the Account screen, whole, with My
  questions and the server line; the Auth page, whole; and the Submit screen's form, whole
  (`Strings.accountScreens`, an `AccountStrings` of the Account screen's words and those of the
  pages opened from it). **Try again** is one text of `Strings`, `tryAgain`, *Покушај поново*
  (*provisional*, §8b), under a failure on Play, the Categories screen, the Account screen, My
  questions, the Auth page and the Submit form, so the game says it one way; the Account screens'
  *Нешто није у реду. Покушај поново.* asks in its words, and `StringsTest` holds the two together.
  So are *Откажи* (`cancel`), on the Auth page's warning, and *Категорије нису учитане.*
  (`categoriesUnread`), on the Categories screen and under the Submit form's categories alike, and
  the Categories screen's Play is the Home screen's *Играј* (`Strings.play`). So too are *Све*
  (`allCategories`), on the Play screen's row and first in the Categories screen's list, and
  *Учитавање* (`loading`), the name both screens give their spinner for a screen reader.
- **The categories' names** *(built)*: the server's, not `Strings`, since a moderator adds and
  renames categories without a build (§8d, *Categories*): `nameSr` in Serbian Cyrillic,
  `SerbianScript.toLatin(nameSr)` in Serbian Latin, as every Latin text is made, and `nameEn` in
  English. A Serbian name a moderator typed in Latin reads as typed in both scripts, since `toLatin`
  leaves Latin letters alone. One function makes that choice, `categoryName(category, language)`
  (`io.ntole.wyr.language`), for the Play screen's row, the Categories screen and the Submit form's
  chips, a category not read yet showing by its id in every language. A screen finds the language
  shown in `LocalLanguage`, which `WyrStrings` provides beside `LocalStrings`. `CategoryNamesTest`,
  `PlayScreenTest`, `PlayScreenDrawTest`, `CategoriesScreenDrawTest` and `SubmitScreenDrawTest` hold
  each language to it.
- **Numbers and symbols** *(built)*: a text holding a number or a name is a template, `{0}` and on,
  filled in by `fill` (`Templates.kt`), so each language puts it where its grammar wants it, and
  `StringsTest` holds every language's copy of a template to the same placeholders. **Points** are a
  **coin** and the number in the whole game (the user, 2026-09-26, in place of the unit *П* before):
  `PointsAmount` for an amount on its own, on the Play screen's row and the Account card, and
  `PointsText` for one in running text, the coin inline where a template's `{0}` is, on the Auth
  page's guest-points warning and the Submit form's cost (*Пошаљи ·* coin *1*). A screen reader hears
  the points as `Strings.points`, *Поени: 43*, *Poeni: 43*, *Points: 43*, a label and the number, so
  no plural form is needed, and the Submit button as *Пошаљи · Поени: 1*. The characters a username
  may hold (`USERNAME_CHARACTERS`, *a–z, 0–9, _*) are the same in every language, so they are not
  `Strings`, whose Serbian texts hold no Latin letter; nor is the dash My questions' table shows for a
  question never served (`NOT_SERVED`), which has no letter at all.
- **The default** *(built)*: Serbian Cyrillic on a first launch, whatever the device's language:
  nothing reads the device's locale (`Language.DEFAULT`; `LanguageMenuTest` sets an English, a
  German and a Serbian Latin locale on the JVM and still opens in Cyrillic).
- **The language menu** *(built; a menu since 2026-09-26, the user: "there will be more languages
  segmented buttons wont fit everything")*: on the Account screen, under My questions (§8d), a row of
  a globe, the language shown named in itself and a chevron (`LanguageMenu`); a tap opens a menu of
  every language, **Ћирилица**, **Latinica** and **English**, each named in itself whatever the
  language shown, the one shown marked, so a player who picked one they cannot read finds the menu by
  its globe and their own by its name (`Language.ownName`, which is why the names are not `Strings`).
  A screen reader hears the row as *Језик: Ћирилица*, in the language shown (`Strings.language`). A
  tap on a language changes every screen at once and is then kept (`LanguageViewModel`, bound in
  `uiModule` and asked for once by `App`). `LanguageMenuTest` opens it and picks each.
- **The Statistics switch** *(built)*: beside the language menu, *Статистика*, *Statistika*,
  *Statistics* (`AccountStrings.statistics`), the word and the switch one control, which a screen
  reader hears as the word, a switch, and on or off (§8g).
- **Kept on the device** *(built)*: under `wyr.language` in the storage the session is kept in (the
  platform's `TokenStorage`: SharedPreferences, `NSUserDefaults`, JVM Preferences, `localStorage`), as
  the language's BCP 47 tag (`sr-Cyrl`, `sr-Latn`, `en`). One key for the device, not one per
  environment (§8e): the language is the player's, so every build on one desktop, iPhone or browser
  shows the one last picked there. A tag this build does not know opens in Cyrillic, and a write that
  fails leaves the language this run's only, silently. `LanguageViewModelTest` and `AppModuleTest`
  pin it, the sessions beside it in one storage untouched.
- **How an option is phrased** *(decided 2026-09-26)*: in Serbian an option answers *Шта би
  радије?*, the question the game's name asks and the Submit form's *Шта би радије…* begins, never
  *Да ли би радије…?*, after which an infinitive is wrong. So an option is an infinitive (*Радити
  четири дуга дана у недељи*) or a *да* clause (*Да ти храна увек буде мало пресољена*), each a
  whole answer to it, and no word in it agrees with the player's gender: not *Бити богат*, a man's,
  but *Имати много пара*. Then no question needs to know the player's gender. The game addresses the
  player as *ти*, as its name does. Rejected: knowing the player's gender, which is personal data a
  guest has none of, and every question in two forms; the formal *Да ли бисте радије радили*, cold
  for a game; and every option as *да* with the present (*Да радиш четири дуга дана*), free of
  gender too but longer, every option opening on one word, and a migration of the seeds: the
  fallback, should the infinitive read wrong after all. Nothing checks it: the 224 seeds keep it
  (V9 and the second seeds), and the moderator holds a player's question to it when deciding, since an approval cannot
  change the text.
  - *Other languages* *(to settle when one gets questions)*: English's *Would you rather…* takes a
    bare verb, with no gender and no formality, so none of this came up there. Before a language's
    first question, settle three things and record them here beside Serbian's: which question the
    game's name asks there, and what form an option takes to answer it; whether that form carries
    the player's gender (a Slavic past tense or conditional, as Serbian's *радио*/*радила*; a
    Romance adjective, *content*/*contente*), and how an option avoids it; and whether the game
    says the familiar or the formal *you* (*du* or *Sie*, *tu* or *vous*). Croatian and Bosnian
    would take Serbian's answers.
- **Not translated yet**: question texts stay as their authors wrote them (server data; a later
  change may put Serbian ones through `SerbianScript.toLatin`; a local question is never
  translated, §8b *Local questions*), and the moderation app
  (`:app:adminApp`) stays English, naming categories in Serbian (`nameOf`).

## 8g. Analytics — decided 2026-09-26

The user: "I would like to have detailed overview, for all platforms ... like live player count,
where do they click, where do they stay the most, average question answered, how many find the account
etc. everything that helps make it better." So the game reports what players do to **PostHog**,
**EU cloud** unless a build names another host (§4), from **shared code**, so all four platforms send
the same events. The moderation app sends none.

- **The port** *(built)*: `Analytics` in `:core:domain` (`io.ntole.wyr.core.domain.analytics`):
  `track(event, properties)`, `screen(name)`, `identify(playerId)`, `reset()`, `flush()`, and the
  player's switch, `enabled` and `setEnabled`. Every call returns at once and none throws: analytics
  never blocks or fails the game. `Analytics.None` sends and keeps nothing. Every event's name is an
  `AnalyticsEvent` constant and every property's an `AnalyticsProperty` one, so what is sent is listed
  there, and a name, once sent, never changes, or the dashboards built on it lose it.
- **The sender** *(built)*: `PostHogAnalytics` in `:core:network`, over PostHog's public
  `POST <host>/batch/` with the project key in the body, and its own Ktor client (no auth, a 30 s
  bound). A batch goes every 20 events (`BATCH_SIZE`), 30 s after the first event waiting
  (`FLUSH_INTERVAL`), and on `flush()`, which the app calls as it goes to the background. At most
  1,000 events wait (`MAX_QUEUED`), in memory only, the oldest dropped first; a request carries at
  most 100. A batch the network failed, or the service answered 408, 429 or 5xx, waits again, in
  front, and nothing more goes on size alone until the next timer; one it refused otherwise (a bad
  key) is dropped. Every step runs one at a time on its own dispatcher, so no two race, and a step
  that throws anything is swallowed there. `PostHogAnalyticsTest` drives it over a `MockEngine` on
  virtual time; no test ever calls PostHog or any server.
- **Who** *(built)*: a random id per install, the event's `distinct_id`, kept in the platform's
  `TokenStorage` under a key of each environment's own, `wyr.analytics.id.local`, `.dev` and `.prod`
  (`PostHogAnalytics.idKeyFor`), beside the sessions and the language and none of them, so a DEV
  build's player is never a PROD one's (§8e). `identify(playerId)`, on a registration or a login,
  sends PostHog's `$identify` with the install's id as `$anon_distinct_id`, which joins what the
  install sent before to the player, and makes the player id the install's from then on; `reset()`,
  on a logout, makes a fresh random one, in a fresh session. The player id is the server's random id,
  as a question's is; never the username. The account's use cases do both (`RegisterAccount` and
  `LogIn` once the server took them, by the player id of the session then stored, `LogOut` once the
  session is dropped), so a login on a second device joins its install to the same player, and the
  guest a logout leaves is nobody's. An account's deletion, once there is one, resets too. Not
  joined: a registration whose answer was lost (its read after names the account, but no id), which
  stays the install's until a login.
- **What every event carries** *(built)*: `distinct_id`; `$session_id`, a version 7 UUID, a new one
  after 30 minutes without an event or 24 hours on (as PostHog's own SDKs count sessions, which its
  session views need); `$screen_name`, the screen last shown; `$lib` (`wyr-kotlin`),
  `$app_version`, `$os`, `$os_version` and `$device_type` as PostHog's SDKs name them (a browser's
  from its user agent, `osOfUserAgent`), `platform` (`android`, `ios`, `desktop` or `web`) and
  `environment` (`local`, `dev` or `prod`); and a `uuid` and a `timestamp` of its own.
- **What is never sent**: the username, an email, a password, any question's or option's text, the
  moderator's reason, anything typed, a token or the admin token. A question is its id, a category
  its id, a failure the domain's name for it (`DomainError`).
- **The key, per platform** *(built)*: a PostHog project's key and host, set when a build is made or
  started as the environment is (§8e), never committed; **no key is analytics off**, a no-op, which is
  how every test and every CI build runs. The key is the project's public one, which can only send.
  - *Android and web*: the Gradle properties `wyr.posthog.key` and `wyr.posthog.host` (`-P`, or
    `~/.gradle/gradle.properties`), or the same names in `local.properties`, read by
    `gradle/wyr-analytics.gradle.kts`: into `BuildConfig.POSTHOG_KEY` and `POSTHOG_HOST`, one key for
    every flavor, and for the web into generated constants (`generateWyrAnalytics`) beside `WYR_ENV`.
    A key of anything but letters, digits, `_` and `-`, or a host holding a quote, a backslash, a `$`
    or a space, fails the build.
  - *Desktop*: the `WYR_POSTHOG_KEY` and `WYR_POSTHOG_HOST` variables (`desktopAnalyticsSettings`).
  - *iOS*: the `WYR_POSTHOG_KEY` and `WYR_POSTHOG_HOST` build settings, set in
    `app/iosApp/Configuration/Local.xcconfig`, which git ignores and `Config.xcconfig` includes if it
    is there; the Info.plist carries them as keys of the same names (`bundledAnalyticsSettings`). A
    host is written there without `https://`, since `//` begins an `.xcconfig` comment.
  - The entry point hands them to `initKoin` as an `AnalyticsSettings`, with the app's version
    (Android's `versionName`, iOS's `MARKETING_VERSION`, desktop's `packageVersion` through the
    `wyr.app.version` property, the web build's `wyrAppVersion`), and `PostHogConfig.of` makes them a
    configuration: no key is none, no host the EU cloud, `eu.i.posthog.com` is `https://`, and a host
    that is none stops the app at launch, naming it, as an environment's name does. `dataModule` binds
    the `Analytics` for it; `moderationDataModule` binds none.
- **The app and its screens** *(built)*: `UsageTracker` (`io.ntole.wyr.analytics`), one for the app's
  life, which `App` tells of the platform lifecycle's start and stop: Android's activity, the iOS view
  controller, the desktop window minimized and back, the browser page hidden and shown. `app_opened`
  at launch and from the background (`from_background`, and the `language` shown), then
  `app_backgrounded` with `duration_ms` in the foreground, and a `flush()`. Every screen the navigator
  shows is PostHog's `$screen`, named by its key (`home`, `play`, `account`, `auth`, `submit`,
  `categories`), and the one left is `screen_left`, with `screen` and `duration_ms`, for another screen
  or for the background, back from which it is shown again. An Android rotation, whose activity stops
  only to start again, reports neither (`rememberConfigurationChanging`, over the activity's
  `isChangingConfigurations`: the one piece of it per platform). `UsageTrackerTest`, and
  `AppNavigationTest` through the whole app.
- **Taps** *(built)*: every button, card, chip, line and menu item of the game's screens hands its
  `onClick` through `tapped(element, properties)` (`io.ntole.wyr.analytics`), which reports a `tap`
  with `element` to `LocalAnalytics` (the app's, which `App` provides; none for a screen drawn alone)
  before it acts, so a tap is counted by a name that never changes with the language or the text, and
  `$screen_name` says where. An element is `screen.what`, lower case and underscores: `home.play`;
  `top_bar.home`, `.account`, `.back`, `.categories`; `play.card_a` and `.card_b` (with `answered`,
  whether the tap went on from the reveal), `.like`, `.dislike`, `.skip`, `.try_again`;
  `account.open_auth`, `.log_out`, `.try_again`; `my_questions.new_question`, `.first_question`,
  `.try_again`; `language.menu` and `language.option` (with its `language` tag); `auth.register`,
  `.show_password`, `.to_log_in`, `.log_in`, `.log_in_anyway`, `.cancel`, `.to_register`,
  `.try_again`; `submit.category` (with its `category` id), `.send`, `.categories_try_again`,
  `.try_again`; `categories.all`, `.category` (with its id), `.play`, `.try_again`. A text field is no
  tap. `TapsTest` draws every screen in the states that show all it can be tapped on, taps everything a
  screen reader could, and fails on anything that reports no tap, or a name not in its lists: a new
  button gets its name by being written with `tapped`, and a name once sent never changes.
- **The game's events** *(built)*, from the ViewModels, so each says what happened, not what was
  tapped:
  - *Play* (`PlayViewModel`): `question_shown` (`question_id`, `categories`); `question_answered`
    once the vote is counted (`side`, `answer_ms` from the question shown to the tap, a retry's the
    first tap's, and `agreed_with_majority`); `question_skipped` (`duration_ms` on it, and
    `recorded`, whether the server heard); `reaction_set` (`reaction`, `like`, `dislike` or `none`,
    and `answered`). The time is the app's `TimeSource.WithComparableMarks` (`uiModule`).
  - *Account* (`AccountViewModel`): `account_opened` each time the screen is shown (`shown()`);
    `register_started` as a registration is sent, and `register_completed` once it worked, its answer
    lost included, the read after it naming the account; `login_completed`; `logout`, sent before the
    logout so it is the account's.
  - *Submit* (`SubmitViewModel`): `submit_opened` each time the form is shown; `submit_sent` once
    stored (`categories`, `count`); `submit_refused` (`code`) for any refusal.
  - *Categories* (`CategoriesViewModel`): `categories_changed` when Play sends a new selection
    (`categories`, `count`, none being every category); what is played already sends nothing.
  - *Language* (`LanguageViewModel`): `language_changed` (`language`, its tag).
  - `error_shown` for every failure a screen shows (`code`, the `DomainError`'s name, and `action`:
    `question`, `vote`, `reaction`, `account`, `my_questions`, `register`, `log_in`, `log_out`,
    `submit`, `points`, `categories`), but a vote already counted, which moves on and shows nothing,
    and a skip, which says nothing.
  - The ViewModel tests hold each, and that nothing typed is ever in one.
- **The switch** *(built)*: **Статистика** on the Account screen, beside the language menu (§8d,
  *The Account screen*; §8f), `analytics.enabled` and `setEnabled` through `LocalAnalytics`. On by
  default, and off is kept for the device, under `wyr.analytics.enabled` (`on` or `off`), whatever the
  environment, as the language is (§8f): the choice is the person's. Off sends nothing more and drops
  what waited, the tap on the switch included; on sends from the next event. A build with no key keeps
  the choice all the same, since a player cannot tell one build from another. `AccountScreenDrawTest`
  draws it on and off and taps it, and `AppNavigationTest` turns the app's analytics off and on.
---

## 9. How to work in this repo

- Read this file first, every session.
- Before adding a dependency, changing the module graph, or picking a tool for an §8b item,
  surface the decision rather than assuming.
- When a decision is made, encode it here and in config — not just in conversation.
- Verify with `./gradlew ktlintCheck` plus the test and compile tasks listed in
  `.github/workflows/ci.yml`. That workflow is the definition of "green". Besides `verify` it runs
  `server-postgres` (the server suite against a Postgres service container), `docker-smoke` (builds
  the image and polls `/health`), and `ios` (framework link, simulator tests, the three `:core`
  modules' test compiles, and an `xcodebuild` simulator build on macOS). All four passed on
  their first run, 2026-09-24. None of those three
  can run on this machine: read their results with `gh run list -R niktok1/would-you-rather`,
  through a login to the personal account only (§7). `:server:test` uses H2 unless `WYR_TEST_JDBC_URL` (plus
  `WYR_TEST_DB_USER` / `WYR_TEST_DB_PASSWORD`) names another database; the suite then wipes it,
  Flyway's history included, before each test that uses it (`ExternalTestDatabase.clean`), and runs
  the schema tests (`MigrationsTest`, `SchemaDriftTest`) on it as well as on H2.
- **iOS cannot be linked, tested, or run on a machine without Xcode** (Command Line Tools alone
  are not enough). The Kotlin compile does not need Xcode, so before pushing iOS-touching code
  run `./gradlew :app:shared:compileKotlinIosSimulatorArm64 :app:shared:compileTestKotlinIosSimulatorArm64`
  locally, and before pushing a common test in `:core:domain`, `:core:network` or `:core:data`, that
  module's `compileTestKotlinIosSimulatorArm64`: Kotlin/Native refuses a comma in a test's name, which
  the JVM takes. The ios job compiles all three, so CI catches it too, but only after the push.
  Framework linking, the simulator tests and the Xcode app stay unverified until the
  `ios` CI job or a machine with full Xcode runs them.
