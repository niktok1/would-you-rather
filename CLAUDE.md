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

Three Java libraries are in the tree by deliberate exception, all server-only where no Kotlin
equivalent exists: HikariCP (connection pooling), the PostgreSQL JDBC driver, and `java-jwt`
(pulled in by Ktor's own `ktor-server-auth-jwt`). H2 is a fourth, used only as the local
development database.

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

:core:network        Ktor client, the Json config, platform token storage, API classes.
                     Depends on :core and :core:domain.

:core:data           Repository implementations, local cache, DTO<->domain mapping.
                     Depends on :core:domain, :core, and :core:network.

:app:shared          Compose Multiplatform UI shared across all client platforms:
                     screens, theme, ViewModels, DI wiring.
                     Depends on :core:domain, :core:data, :core:network.

:app:androidApp      Android Application/Activity, manifest, Android-only wiring.
:app:desktopApp      JVM main() entry point.
:app:webApp          js + wasmJs browser entry point.
app/iosApp           Xcode project consuming the Shared framework (not a Gradle module).

:server              Ktor server. Routes, auth, persistence (Exposed). Depends on :core.
```

**Entry-point rule:** `:app:shared` holds everything identical across platforms. A platform
entry point holds ONLY what cannot be expressed in common code: OS lifecycle binding, platform
permissions, framework/manifest config, and platform-specific DI wiring. If code can live in
shared, it lives in shared.

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
| HTTP client        | Ktor (client)          | Same family as the server                        |
| Serialization      | kotlinx.serialization  | Backbone of :core                                |
| Async              | Coroutines + Flow      | Official                                         |
| Local cache        | SQLDelight             | **Declared, not yet wired — see below**          |
| Server persistence | Exposed                | JetBrains Kotlin SQL framework, pairs with Ktor  |
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
- Two ktlint rules are suppressed and both are documented at the suppression site: PascalCase
  `@Composable`/`@Test` function names (`.editorconfig`), and `MainViewController` on iOS.

**Wire enum rule (load-bearing — do not break):** any enum on the wire whose value set may grow
server-side MUST have an `UNKNOWN` member, every property of that type MUST declare `UNKNOWN` as
its default, and the client `Json` instance MUST set `coerceInputValues = true`. Without all
three, adding an enum value server-side makes already-installed clients fail deserialization
outright. Enums that are structurally closed (e.g. `OptionSide` — a question has exactly two
sides) are exempt and must stay closed.

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
- `:server` deploys as a Render **web service**, built from the root `Dockerfile`. Health check
  path is `/health`. **Auto-deploy is off** (`autoDeployTrigger: "off"` in `render.yaml`) until
  the first deploy, per the interim schema-migration policy in §8b. The first-deploy milestone
  sets `autoDeployTrigger: checksPass`, which deploys a commit on `main` only after its CI checks
  pass. Not `commit`, which is what the deprecated `autoDeploy: true` means: it deploys every
  commit whether or not CI is green.
- PostgreSQL is a **Render managed Postgres** instance in the **same region** as the web
  service (use the internal connection URL, never the external one).
- Connection string and all secrets come from Render **environment variables** — never
  committed. `ServerConfig` reads them all, with dev-only defaults, and logs a loud warning
  when running on a dev default.
- `DATABASE_URL` arrives in `postgres://` form, which JDBC rejects; `ServerConfig` translates it.
- The Docker build sets `WYR_SERVER_ONLY=1`, which makes `settings.gradle.kts` skip the app
  modules. Without it the Android Gradle plugin fails at configuration time for want of an SDK.
- Free tier caveats to design around: free web services spin down after ~15 min idle (cold
  start on next request), and free Postgres is time-limited — migrate to a paid instance before
  relying on persistence.
- Do not hand-deploy. Once auto-deploy is on (`checksPass`), the path is: push to `main` → CI
  (ktlint + tests) → Render builds → publishes.

## 8a. Authentication — resolved

**Zero-click, server-issued guest sessions with a custom Kotlin implementation.** No third-party
auth SDK, satisfying §2.

- `POST /v1/auth/guest` mints the player server-side and returns a signed access JWT plus an
  opaque refresh token. Nothing is asked of the player.
- Identity is **server-issued**, which is the whole point: a client-supplied device id would be
  forgeable and would let one device stuff the ballot.
- The access token travels in `Authorization: Bearer`, never in a request body, so `VoteRequest`
  does not change when auth evolves.
- Only a SHA-256 hash of the refresh token is stored. Refresh tokens **rotate on every use**, so
  a replayed token is dead on arrival.
- `POST /v1/auth/link` does not exist yet. It is the intended next step and is what will make an
  account survive reinstall and sync across devices.

**Known limitation, by design for now:** a guest account is bound to one device's storage. Lose
the device or clear storage and the account — and its points — are gone. Token storage is also
ordinary preference storage (SharedPreferences / NSUserDefaults / JVM Preferences /
localStorage), not Keychain or EncryptedSharedPreferences. Both must be addressed before real
accounts exist.

## 8b. Open decisions (resolve before relevant work)

- **Provider linking** (Google / Apple / passkeys) — needed to make accounts durable. Requires
  OAuth client credentials, and Apple additionally requires a paid developer account. Passkeys
  have no desktop-JVM story, so desktop would need a browser handoff.
- **SQLDelight cache** — see §4. Needs a per-platform split because of web. Lower priority once
  the endless feed (§8d) makes the server the source of truth for what a player has answered.
- **Question submission + moderation** — the game rules are settled in §8d; the contract
  (`QuestionStatus`, author field) and the moderator model are not.
- **Rate limiting** — `ErrorCode.RATE_LIMITED` exists on the wire and nothing emits it yet.
- **Schema migrations** — *interim policy, decided 2026-09-23:* nothing is deployed, so until the
  first Render deploy a schema change ships as a fresh database through `SchemaUtils.create`, and
  `render.yaml` keeps `autoDeployTrigger: "off"` so connecting the blueprint cannot deploy early.
  A real migration tool (`exposed-migration-jdbc` plus a runner) must be chosen before the first
  column change **after** that deploy.
- **WCAG AA contrast audit** — see §5b. Paused along with UI polish (§8d).

`RANDOM` was an open item and is resolved: it is a content category (the absurd questions), not a
"surprise me" filter, and it stays in `QuestionCategory` as-is. The unfiltered feed already mixes
every category.

## 8c. Scoring rules — flat

Server-authoritative, in `io.ntole.wyr.server.vote.Scoring`. The client displays what the server
returns and never recomputes points, so the two cannot disagree.

- Every answer earns `Scoring.POINTS_PER_ANSWER`, which is **1 point**, whichever side it picks
  (§8d). There is no majority bonus and no streak.
- `PlayerStore.addPoints` adds in SQL (`total_points = total_points + n`), never as a read then a
  write, so two votes by one player landing together cannot lose a point.
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
console, which is the default root screen. `PlayScreen` stays as a frozen second tab.

- **Scoring** *(built; see §8c)*: every answer earns exactly **1 point**, whichever side
  it picks. There is no majority bonus and no streak: the streak is removed from the server, the
  contract, and the domain. The reveal still shows the split and whether the player sided with
  the majority, as information only.
- **Endless feed** *(not built)*: the game never ends. A player is served questions they have
  not answered, in **random order per player**. Once none remain, answered questions loop back,
  least-recently-answered first. `GET /v1/questions` requires a bearer token and is per-player.
- **Re-answering** *(not built)*: a looped question can be answered again. It earns the point
  again and the player may change their pick. The tally always holds **one vote per player per
  question**, their latest.
- **Retry safety** *(not built)*: every vote carries a client-generated idempotency key. A repeat
  of the same key returns the stored result and pays nothing. A new key on an answered question
  is a fresh answer.
- **Skipping** *(not built)*: allowed. It earns nothing and is not recorded, so the question
  stays unanswered and comes back later.
- **Own questions** *(not built)*: an author is never served their own question.
- **Likes** *(not built)*: any player may like any question except their own, at any time
  (before or after answering), once each, and may unlike it. Each like currently held is **+1
  point to the author**, and unliking takes that point back. The like count is visible before
  answering. For now likes do nothing else; serving questions by quality is a later idea.
- **Submitting** *(not built)*: earns no points directly, because authors earn through likes. A
  submitted question is served only after a moderator approves it.

---

## 9. How to work in this repo

- Read this file first, every session.
- Before adding a dependency, changing the module graph, or picking a tool for an §8b item,
  surface the decision rather than assuming.
- When a decision is made, encode it here and in config — not just in conversation.
- Verify with `./gradlew ktlintCheck` plus the test and compile tasks listed in
  `.github/workflows/ci.yml`. That workflow is the definition of "green". Besides `verify` it runs
  `server-postgres` (the server suite against a Postgres service container), `docker-smoke` (builds
  the image and polls `/health`), and `ios` (framework link, simulator tests, and an `xcodebuild`
  simulator build on macOS; that last step is `continue-on-error` until it has passed once). None
  of those three can run on this machine. `:server:test` uses H2 unless `WYR_TEST_JDBC_URL` (plus
  `WYR_TEST_DB_USER` / `WYR_TEST_DB_PASSWORD`) names another database; the suite then drops every
  app table (`appTables`) before each test.
- **iOS cannot be linked, tested, or run on a machine without Xcode** (Command Line Tools alone
  are not enough). The Kotlin compile does not need Xcode, so before pushing iOS-touching code
  run `./gradlew :app:shared:compileKotlinIosSimulatorArm64 :app:shared:compileTestKotlinIosSimulatorArm64`
  locally. Framework linking, the simulator tests and the Xcode app stay unverified until the
  `ios` CI job or a machine with full Xcode runs them.
