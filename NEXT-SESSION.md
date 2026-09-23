# Next session — pick up here

Read `CLAUDE.md` first (authoritative). This file is just the working handoff.

## Where we are

The vertical slice is built and running: **zero-click session → fetch questions → vote → see
the tally and points**. Server and all client targets except iOS are verified on this machine.

Repo initialized on `main` with the personal identity and `user.useConfigOnly = true` (§7).

### Verified working

- `:server` on H2: 17 tests green, including 8 end-to-end flow tests.
- Live curl run against `./gradlew :server:run` confirmed guest auth, paging, voting, the
  majority *and* minority scoring paths, refresh-token rotation, replay rejection, and the
  `ErrorDto` envelope on 400/401/404/409.
- `:app:shared` compiles for JVM, JS, and wasmJs; 5 ViewModel tests green.
- `:app:androidApp:assembleDebug` produces a real APK.
- `ktlintCheck` clean across every module.

### NOT verified

- **iOS.** This machine has Command Line Tools but no Xcode. The Kotlin compile does not need
  Xcode: `iosArm64` and `iosSimulatorArm64` main sources and the simulator test sources now
  compile. That check caught `MainViewController` using `GlobalContext`, which Koin's native
  artifact does not expose; it now uses `KoinPlatform.getKoinOrNull()`. Framework linking, the
  simulator tests and the Xcode project do need Xcode (`linkDebugFramework*` fails here with
  `MissingXcodeException`), so the app itself is still unproven. **Build it first on a machine
  with Xcode**, or let the `ios` CI job do it.
- **`Dockerfile` and `render.yaml`.** Docker is not installed here, so the image has never been
  built and nothing has been deployed. What the image runs *is* verified: the fat jar, built with
  `WYR_SERVER_ONLY=1 ./gradlew :server:buildFatJar`, now registers both JDBC drivers, boots on H2
  with no `DATABASE_URL`, and answers `/health`. The image build around it is still unproven.
- **The three new CI jobs** — `server-postgres`, `docker-smoke`, `ios` — are written but have
  never run, and cannot until a GitHub remote exists. The Postgres harness was exercised locally
  by pointing `WYR_TEST_JDBC_URL` at a shared H2 database, which proves the per-test drop but
  not the Postgres dialect. The `xcodebuild` step in `ios` is `continue-on-error` because it is
  the least certain of them; **once it has gone green, delete that line** so it gates like the
  rest.
- **The UI has never been looked at.** It compiles and its ViewModel is tested, but no
  screenshot of the play screen or the reveal state has been taken on any platform. Treat the
  layout and the §5b palette in practice as unreviewed.

## Running it locally

Server first, then a client. The server defaults to in-memory H2 and logs a warning saying so.

```bash
./gradlew :server:run
```

```bash
./gradlew :app:desktopApp:run
```

Android uses `http://10.0.2.2:8080` (the emulator's alias for the host loopback); desktop, iOS
simulator, and web use `http://localhost:8080`. All four are in
`io.ntole.wyr.di.DevApiBaseUrl`. For the **web** client the server also needs
`ALLOWED_WEB_ORIGINS` set or CORS preflight will reject every request.

## Roadmap (agreed 2026-09-23)

UI polish is paused and the work is functionality-first behind an engineering dev console. Game
rules live in CLAUDE.md §8d. Each item is one short-lived branch, in order:

0. `chore/ci-coverage` *(done)* — fix fat-jar JDBC driver registration (the image cannot boot on
   H2), close the Hikari pool on stop, set `autoDeployTrigger: "off"`, and add CI jobs for
   Postgres, iOS compile, and a Docker `/health` smoke test.
1. `fix/server-errors` *(done)* — a vote from an unknown player returns 401, not 409
   ALREADY_VOTED; only a real duplicate returns 409; add a body-parse helper; drop logback from
   TRACE to INFO; `?category=UNKNOWN` returns 400; parse CORS origins that include a scheme.
2. `feat/flat-scoring` *(done)* — 1 point per answer, streak removed (§8d).
3. `fix/client-errors-session` — truthful error mapping (offline, dead refresh, and cancellation
   currently all show UNKNOWN); invalidate Ktor's cached bearer on session change; single-flight
   reset; first `:core:network` / `:core:data` tests.
4. `feat/dev-console` — the engineering UI, as the default root.
5. `feat/endless-feed` — per-player random unanswered questions, then loop; re-answering;
   idempotency key; skip.
6. `feat/player-stats` — `GET /v1/me`.
7. `feat/question-submission` — split into server + contract, then client + console.
8. `feat/moderation`.
9. `feat/question-likes`.
10. `feat/category-play`.
11. Pre-deploy hardening and the first Render deploy, which sets `autoDeployTrigger: checksPass`
    in `render.yaml` (CLAUDE.md §8).

**Blocked on the user:** no git remote exists yet, so `.github/workflows/ci.yml` has never run.
It needs a GitHub repo on the personal account (§7). Render and iOS verification both depend on
CI running.

Deferred: provider linking (§8a), SQLDelight, a leaderboard, UI polish and WCAG, and the
known `PlayViewModel` issues (the Play tab is frozen).

## Things worth knowing before you touch the code

- **The wire enum rule (§5) is load-bearing and easy to break silently.** It only works because
  `WyrJson` sets `coerceInputValues = true` *and* `ServerJson` sets `encodeDefaults = true`.
  Remove either and the `UNKNOWN` defaults become decorative — with no test failure, because the
  breakage only shows up on a client build older than a server enum addition.
- **Exposed 1.x renamed everything.** Packages are `org.jetbrains.exposed.v1.*`, and
  `SqlExpressionBuilder.eq` is deprecated *as an error* — import the top-level `eq` instead.
  Expect to hit this again the first time you write a new query.
- **The majority verdict on the reveal is client-side and display only.**
  `VoteOutcome.agreedWithMajority` treats an exact tie as agreement, and nothing on the server
  mirrors it because no points depend on it (§8d). Scoring against the tally again would bring
  back a rule the two sides must keep in sync.
- **At REPEATABLE_READ, concurrent writes to one row fail, and are retried only briefly.** The
  Hikari pool sets that level. The database refuses the second of two conflicting writes to a
  row (SQLState 40001), even an SQL increment. Exposed re-runs the whole transaction, but makes
  only 3 attempts in total with no delay between them. Once they run out the request is a 500.
  No point is lost, since the vote rolls back too, but the answer is rejected. A probe of 8
  concurrent awards to one player saw 3 succeed and 5 fail. At READ COMMITTED (Postgres's
  default) the SQL increment alone is correct and nothing needs a retry. The retry also hides a
  read-then-write bug, which only shows at READ COMMITTED. `PlayerStoreTest` races two awards at
  both levels, and two awards fit in 3 attempts.
- **Open decision, to settle with the user before likes** (CLAUDE.md §8b). Likes put many
  players' +1s on one author's row at once, which will use up the 3 attempts. There are two
  options. Run the vote and like transactions at READ COMMITTED with a per-transaction
  `transactionIsolation` override, which the SQL increment makes safe. Or keep REPEATABLE_READ
  and configure `maxAttempts` plus a retry delay. Pin whichever is chosen with a burst test
  through the route.
- `Tally.percentB` is defined as `100 - percentA` rather than rounded independently, so the two
  always sum to 100. There is a property test over every split up to 40/40.
- `:server` must not depend on `:core:domain` (§3). That is why scoring lives in `:server`.
- Only `:core` and `:core:domain` enforce `explicitApi()`, so new public declarations there need
  an explicit `public`.
