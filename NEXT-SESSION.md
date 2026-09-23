# Next session — pick up here

Read `CLAUDE.md` first (authoritative). This file is just the working handoff.

## Where we are

The vertical slice is built and running: **zero-click session → fetch questions → vote → see
the tally and points**. Server and all client targets except iOS are verified on this machine.

Repo initialized on `main` with the personal identity and `user.useConfigOnly = true` (§7).

### Verified working

- `:server` on H2: 52 tests green, including 21 end-to-end flow tests in `ApiFlowTest`. Flat
  scoring is covered there (every vote pays 1, majority and minority alike, and the total
  accumulates) and by `PlayerStoreTest`, which races awards for one player and refreshes of one
  token. The endless feed, re-answering and attempt replay are covered there too, and by
  `QuestionStoreTest` and `VoteStoreTest`.
- Live curl run against `./gradlew :server:run` confirmed guest auth, paging, voting,
  refresh-token rotation, replay rejection, and the `ErrorDto` envelope on 400/401/404/409. That
  run predates flat scoring, `fix/read-committed` and the endless feed, so the scoring it checked
  was the old streak rule, and the rotation was the old find-then-update-by-id at REPEATABLE_READ,
  not today's compare-and-set at READ COMMITTED. The cursor paging and the 409 it checked no
  longer exist.
- The client against a live `:server:run` on H2, on `feat/endless-feed`: a throwaway JVM test,
  not committed, drove `GetNextQuestion` and `CastVote` through the real CIO client for 60
  answers. The first 24 were the 24 seeds, all different, in random order. From the 25th every
  question came back `answeredBefore`, least recently answered first, never the same one twice in
  a row, and every answer paid 1. The last vote sent again as the same attempt was replayed: +0,
  and the stored side although the request asked for the other. A skipped question came back in
  the next batch.
- Client tests: `:core:domain` 10, `:core:data` 47, `:core:network` 21, `:app:shared` 34 (the
  ViewModels and the Koin graph). `:app:shared` compiles for JVM, JS, wasmJs and the iOS
  simulator.
- `:app:androidApp:assembleDebug` produces a real APK.
- `ktlintCheck` clean across every module.

### NOT verified

- **The refresh rotation on a live server.** Nobody has re-run the curl pass since it landed, so
  the compare-and-set rotation is proven by tests only. The 1-point rule has been seen live, in
  the client run above.
- **The endless feed and vote replay on Postgres.** `RANDOM()` and `NULLS FIRST` in the feed, a
  first answer racing another (the 23505 aborts the transaction and Exposed reruns it), and the
  `SELECT ... FOR UPDATE` that makes a retry wait and replay have only run on H2. The store races
  in `QuestionStoreTest` and `VoteStoreTest` poll H2's `SESSIONS`, like `PlayerStoreTest`.
- **READ COMMITTED and the refresh compare-and-set on Postgres.** Every race and burst in
  `PlayerStoreTest` runs on H2, even in the `server-postgres` job: it hardcodes `jdbc:h2:mem:`,
  because its wait-for-the-lock polling reads H2's `INFORMATION_SCHEMA.SESSIONS`. So the ci.yml
  note that isolation differences surface in that job holds only for `ApiFlowTest`'s sequential
  flows. On Postgres the rotation stays single-use because an `UPDATE` that waited on a row lock
  re-checks its `WHERE` against the committed row. That is documented Postgres behaviour, not
  something a test here has seen, and the same goes for the concurrent-seed recovery described on
  `Seed.questionsIfEmpty`. Porting the races means polling `pg_stat_activity` instead.
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
  layout and the §5b palette in practice as unreviewed. The dev console has not been opened
  either: its actions are tested through its ViewModel, and the use cases behind them ran live.

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
`ALLOWED_WEB_ORIGINS` set or CORS preflight will reject every request. The page's dev server
takes the first free port from 8080, so with the API already there it serves on 8081:

```bash
ALLOWED_WEB_ORIGINS=localhost:8081 ./gradlew :server:run
```

```bash
./gradlew :app:webApp:wasmJsBrowserDevelopmentRun
```

### The dev console

The app opens on the **Console** tab (`io.ntole.wyr.dev`). **Play** is the frozen game screen.

- **Session.** *Ensure session* mints a guest, or reuses the stored one. The header then shows the
  player id and when its access token expires. *New guest* drops the session and the question
  queue, then mints a fresh player and loads a question for it.
- **Play.** *Skip* loads the next question and sends nothing, so the feed serves the skipped one
  again in a later batch. *A* / *B* answer it, each tap as a new attempt, and the raw
  `VoteOutcome` appears below, `replayed` included. A question the feed looped back to shows
  `answeredBefore: true` and logs as `question=<id> looped`. The header's total points is the last
  outcome's `totalPoints`; there is no stats endpoint yet.
- **Retry last vote (same attempt)**, under Play. Sends the last vote again unchanged, attempt id
  included, whether or not it got an answer. While it is still that question's latest answer the server
  replays it, logged as `+0 total=<n> replayed`. A vote that never landed is paid as an answer.
  Every vote's log entry shows its attempt id, so the two entries can be compared.
- **Answer many.** *Answer N* answers N questions in a row (1 to 50), alternating A and B. Each
  answer is logged as `answer` as it lands, then the run as `answered=N looped=K total=T`; a
  failure ends the run, and its vote is left for *Retry last vote*. **To see the loop:** the server
  seeds 24 questions, so press *New guest*, then *Answer N* with 25. The first 24 are the seeds in
  random order and the 25th logs `looped`. After that the loop comes round least recently
  answered first.
- **Questions.** Fetch the next question, or empty the local queue, and see its size.
- **Vote by id.** Sends a vote for whatever id is typed, as a new attempt. An unknown id provokes
  `QUESTION_NOT_FOUND` (404). A known one is simply answered again and pays 1: there is no
  "already voted" any more.
- **Action log.** Every action, newest first: `ok`, `err` (the `DomainError` and its diagnostic
  message) or `crash` (anything else thrown), with how long it took. One action runs at a time.
- **HTTP trace.** Every request that went out, with status and time. A refreshed call shows as
  the 401, the refresh, and the retry.

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
3. `fix/client-errors-session` *(done)* — truthful error mapping (offline, dead refresh, and
   cancellation used to all show UNKNOWN); the bearer is read from `SessionStore` on every
   request (`cacheTokens = false`) instead of cached; one guest minted per dead session; first
   `:core:network` / `:core:data` tests.
3a. `fix/read-committed` — run transactions at READ COMMITTED, where SQL increments are correct
   without retries, and make refresh-token rotation a compare-and-set so it stays single-use.
   This resolves the CLAUDE.md §8b "Isolation for hot counters" item before likes need it.
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
  Remove either and the `UNKNOWN` defaults become decorative. `WyrJsonTest` in `:core:network`
  pins the client half, but nothing pins the server's `encodeDefaults` yet, and a server-side
  slip only breaks client builds older than the server.
- **Exposed 1.x renamed everything.** Packages are `org.jetbrains.exposed.v1.*`, and
  `SqlExpressionBuilder.eq` is deprecated *as an error* — import the top-level `eq` instead.
  Expect to hit this again the first time you write a new query.
- **The majority verdict on the reveal is client-side and display only.**
  `VoteOutcome.agreedWithMajority` treats an exact tie as agreement, and nothing on the server
  mirrors it because no points depend on it (§8d). Scoring against the tally again would bring
  back a rule the two sides must keep in sync.
- **Transactions run at READ COMMITTED, so a read-then-write loses races silently** (CLAUDE.md
  §4). The Hikari pool sets the level for every transaction. A counter must be an SQL increment,
  any other read-then-write a compare-and-set whose `WHERE` repeats what it read (0 rows updated
  means another transaction won), and uniqueness a constraint. The bug to watch for when writing
  likes is an update by id after a read: nothing refuses it any more, it just overwrites.
  `PlayerStoreTest` pins a burst of 8 awards through `DatabaseFactory.poolConfig`, and races two
  awards and two refreshes at a hand-picked READ COMMITTED so those tests stay discriminating
  whatever the server's level becomes.
- **The client keeps no record of which questions it has served** (CLAUDE.md §8d). The server
  knows what the player answered and leads every batch with whatever is still unanswered, which
  includes what is queued and the question on screen. `InMemoryQuestionCache` drops only ids still
  queued, and `DefaultQuestionRepository` drops the one it handed out last. An "ever seen" set is
  what used to end the game after one pass; do not bring one back. `OUT_OF_QUESTIONS` now means
  the server sent an empty batch, and nothing else.
- **An attempt id is made once per tap and reused only to retry that tap.** `AttemptId.random()`
  is the only way to make one. Making a new one for a retry pays twice; reusing one for a new tap
  turns that answer into a replay that pays nothing. The server stores only the latest attempt per
  player and question, so a retry that arrives after a newer answer pays as a fresh answer.
  `PlayViewModel` keeps a vote lost to `NETWORK` for Try again and moves on after any other
  failure, since a refused vote would fail the same way every time.
- `Tally.percentB` is defined as `100 - percentA` rather than rounded independently, so the two
  always sum to 100. There is a property test over every split up to 40/40.
- `:server` must not depend on `:core:domain` (§3). That is why scoring lives in `:server`.
- Only `:core` and `:core:domain` enforce `explicitApi()`, so new public declarations there need
  an explicit `public`.
