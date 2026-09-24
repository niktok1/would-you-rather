# Next session — pick up here

Read `CLAUDE.md` first (authoritative). This file is just the working handoff.

## Where we are

The vertical slice is built and running: **zero-click session → fetch questions → vote → see
the tally and points**. Server and all client targets except iOS are verified on this machine.

Repo initialized on `main` with the personal identity and `user.useConfigOnly = true` (§7).

### Verified working

- `:server` on H2: 156 tests green, including 59 end-to-end flow tests in `ApiFlowTest`. Flat
  scoring is covered there (every vote pays 1, majority and minority alike, and the total
  accumulates) and by `PlayerStoreTest`, which races awards for one player and refreshes of one
  token. The endless feed, re-answering and attempt replay are covered there too, and by
  `QuestionStoreTest` and `VoteStoreTest`. Feed cycles are pinned in `QuestionStoreTest`,
  including two requests racing to start the next cycle, and in `VoteStoreTest`, an answer that
  waited on its vote's lock while a cycle started. `GET /v1/me` is covered in `ApiFlowTest` (a
  fresh guest, a re-answer and a replay, the lazy start of a cycle) and by `StatsStoreTest`, which
  commits an answer in the middle of a stats read to show it counts in every number or in none.
  `POST /v1/skips` is covered in `ApiFlowTest` (out of the cycle and back in the next, a repeat,
  an answer after a skip, 404, 401 and malformed bodies) and by `SkipStoreTest`, which also races
  two first skips of one question, and a skip that waited on another's lock while a cycle started.
  Question submission (`feat/question-submission`, server and contract only): `POST /v1/questions`
  and `GET /v1/me/questions` are covered in `ApiFlowTest` (the content rules, malformed bodies, the
  pending cap, a pending question served to and answerable by nobody, the list's order and owner,
  401s), by `SubmissionStoreTest`, which races two submissions for the last pending place at 19,
  and by `ServableQuestionsTest`, which pins what the one servable predicate lets through. The flow
  tests also pass against one shared database (`WYR_TEST_JDBC_URL` at a shared H2), so the per-test
  drop copes with the new questions-to-players key, and with `question_categories`.
  Multiple categories (`feat/multi-category`): on the server, `QuestionStoreTest` pins a
  question in several categories served once with all of them in declaration order, a filter of
  one or two categories serving each matching question once (an `EXISTS`, and a join mutation fails
  it), the two as one pool, served again only once nothing in it is due, the due count over a set,
  that a batch reads its categories in the same number of statements whatever its size, and that a
  stored name this build does not know reads as `RANDOM`, sent once however many read as it, in
  the feed and in the author's list.
  `ApiFlowTest` covers the repeated `?category=`, the 400s for `UNKNOWN`, an unknown name, a
  comma-separated list and an empty value, and submitting under several (deduplicated and ordered,
  and listed back the same) with the 400s for none and for `UNKNOWN`. `SubmissionStoreTest` pins
  the rows, and the author's list in the same number of statements whatever its length.
  `WyrJsonTest` and `ServerJsonTest` pin an unknown name in a list decoding as `UNKNOWN` on both
  sides. On the client, `QuestionMapperTest` pins a question's categories as a set, each once in
  declaration order, with an unknown name as `OTHER` beside the rest and an empty or missing list as
  `OTHER` alone; `QuestionApiTest`, one `?category=` per category, in declaration order;
  `DefaultQuestionRepositoryTest`, a selection of several sent whole with every refill, any change to
  it dropping the queue (a refill in flight included), the same set keeping it, and `OTHER` refused;
  and `DevConsoleViewModelTest`, the Category row's toggles and *All*.
  Moderation (`feat/moderation`, server and contract only): `ModerationStoreTest` races two
  approvals of one submission, and an approval against a rejection, and exactly one decides it each
  time, with only the winner's categories written (dropping `PENDING` from the compare-and-set, or
  replacing the categories before the decision, fails them); it also pins what a decision writes,
  409 and nothing changed for a decided question or a seed, 404 for an unknown id, and the queue's
  order, bound, statuses and statement count. `ApiFlowTest` pins the effects end to end: an approved
  submission due at once for its author, a player midway through the cycle and one who had answered
  everything else (served it in cycle 1, not a new cycle); a rejected one served to nobody, 404 to
  answer or skip, its trimmed reason in the author's list; categories named on approval changing
  what `?category=` finds; 400 for every malformed decision or queue query; 403 `FORBIDDEN` on all
  three admin routes for a missing, wrong, case-changed or bearer-carried token, and before the body
  is read; 200 beside any player session, live, forged or dead, never 401; and 404 on all three with
  `ADMIN_TOKEN` unset. `AdminTokenTest` pins the comparison: one `MessageDigest.isEqual` of two
  32-byte digests per check, whatever is presented. `ServerConfigTest` pins `ADMIN_TOKEN`'s parsing,
  `CorsTest` the preflight for `X-Admin-Token`. `SubmissionStoreTest` and `ServableQuestionsTest`
  now decide through `ModerationStore` rather than writing the table. The whole suite passes against
  one shared database too (`WYR_TEST_JDBC_URL` at a shared H2), so the per-test drop copes with the
  new index.
  Moderation's client, on the same branch, which merges `feat/submission-client` up to its data layer
  (5ca5cd7) for the `Submission` domain type and its mapping: `ModerationApiTest` pins the admin header
  on each admin call and on no other request, the bearer left to the Auth plugin (none without a
  session), no refresh after a 403, and the token absent from the trace and from a failure's message;
  `DefaultModerationRepositoryTest` the queue mapped as the author's list is, an approval's categories
  in declaration order and none keeping the author's, `OTHER` refused before sending, the trimmed
  reason, each refusal's `DomainError` with its message, `UNKNOWN` for moderation off, and that neither
  a 403 nor a 401 replaces the player's session or makes one; `AdminTokenTest` and
  `RejectionReasonTest` the token's and the reason's rules; `ModerationMapperTest` the reason limit
  against the wire's; `ModerationConsoleViewModelTest` the section: nothing sent without a valid token,
  Reject off until the reason is valid, the queue read again after every decision, picks for a
  submission no longer listed dropped, and the token in neither the state's text nor the log.
  Likes (`feat/question-likes`, server and contract only; `:server` 179 tests with it, 65 of them
  flows): `LikeStoreTest` pins a like paying the author a point and an unlike taking it back, a
  repeat of either writing and paying nothing, likedByMe being each player's own, a self-like paid,
  a seed's likes paying nobody, a like being no answer, the author's total as their answers plus
  the likes their questions hold, 404 for a pending or rejected question and 401 for an unknown
  player; two first likes racing pay once (on the key, through Exposed's rerun) and two unlikes
  racing take back once; the feed's likes before answering, in the same number of statements for a
  batch of one as for the pool, and an unlike committed mid-read showing in neither number; and
  `likesReceived` against likes added, removed and given. `StatsStoreTest` commits a like mid-read
  and finds it in neither the points nor `likesReceived`. `ApiFlowTest` pins `POST /v1/likes` end to
  end with the 404s, 401s and 400s (no `liked` is 400, since a like sets rather than toggles).
  Dropping the key, taking back for an empty delete, paying a repeat, skipping a self-like, reading
  likes per question or in two statements, or reading `likesReceived` apart from the total, each
  fails them. The flows pass against one shared H2 too.
- Live curl run of moderation against `ADMIN_TOKEN=... ./gradlew :server:run` on H2, on
  `feat/moderation`: a guest submitted two questions; the queue listed both, oldest first; the queue
  without the token, and with only the player's bearer token, was 403 `FORBIDDEN`; approving one
  with `["SUPERPOWERS","RANDOM"]` answered it `APPROVED` under both, and it was then in the
  `?category=RANDOM` feed and the guest's due count (25); rejecting the other with `"  Too close to
  a seed "` stored the reason trimmed, which `GET /v1/me/questions` showed; deciding the approved
  one again was 409 `ALREADY_DECIDED`, an unknown id 404 and a blank reason 400; the queue was then
  empty and `?status=APPROVED` listed the one approved.
- The console's moderation path against the fat jar on H2 with `ADMIN_TOKEN` set, on
  `feat/moderation`: a throwaway JVM test, not committed, drove `ModerationConsoleViewModel` from the
  real Koin graph through the real CIO client. A curl guest had submitted three questions. Load
  pending listed them oldest first; a wrong token logged `FORBIDDEN`; approving the first under
  SUPERPOWERS and RANDOM, the second keeping its categories, and rejecting the third with a padded
  reason each worked, the reason stored trimmed; approving the rejected one again logged
  `ALREADY_DECIDED`, an unknown id `QUESTION_NOT_FOUND`, and the queue was then empty. The moderator's
  graph never made a player session, and neither the trace (which showed every request, with
  `?status=PENDING&limit=20`) nor the section's state held the token. The guest's `GET /v1/me/questions`
  then showed both approvals and the rejection with its reason, the `?category=RANDOM` feed served the
  first, and the guest's due count was 26 (the 24 seeds and the two approved).
- Live run of likes against `PORT=18431 ADMIN_TOKEN=... ./gradlew :server:run` on H2, on
  `feat/question-likes`, by a throwaway script: liking a pending submission was 404; once approved,
  a fan's like answered `likeCount` 1 and `likedByMe` true, and again the same; the author's own like
  made it 2, and `GET /v1/me` showed the author 2 points and `likesReceived` 2; the fan's feed showed
  the question at 2, liked by them and not answered; the fan's unlike, twice, left 1 and took one
  point back once; a seed's like answered 1; a body without `liked` was 400 and no token 401.
- The likes client (`feat/question-likes`) against a live `:server:run` on H2, with `PORT` and
  `ADMIN_TOKEN` set: a throwaway JVM test, not committed, drove `DevConsoleViewModel` and the use
  cases from the real Koin graph through the real CIO client, as two guests. The author submitted a
  question, approved it through `ApproveSubmission`, answered one question (stats agreed, total 1),
  and paged *Next question* to their own: `likeCount` 0, `likedByMe` false. *Like* showed it at 1,
  liked, logged `setLike(... liked=true)`, and the stats read after showed total 2 and
  `likesReceived` 1, with `likesMovedSinceOutcome` and no mismatch. A second guest's `SetLike` made it
  2 and the author's *Read stats* total 3 and 2 received. The author's *Unlike* left it at 1, not
  liked, and the stats at total 2 and 1 received. The fan's feed then served the question at 1,
  `likedByMe` true, not answered; a seed liked twice answered 1 both times and unliked 0. The trace
  showed each like as `POST /v1/likes` 200. In the tests, `LikeApiTest` pins the request (an unlike
  writes `liked: false` out) and a refusal's code; `LikeMapperTest`, `QuestionMapperTest` and
  `PlayerMapperTest` the new fields, with a count the player is not part of; `SetLikeTest` the session
  first; `DefaultLikeRepositoryTest` the first launch's like going out once, recovery after a 401
  sending the same body, a like lost to `NETWORK` asked for again and held once, and a 404 leaving the
  session alone, all through `MockEngine`; and `DevConsoleViewModelTest` the Like button and the
  likes-moved comparison, a like sent before any read after the vote worked included.
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
  the next batch. That run predates feed cycles (`feat/feed-cycles`): the least-recently-answered
  loop it saw is gone, and cycles have run only in the server tests. It also predates server-side
  skips (`feat/skip-per-cycle`), which keep a skipped question out until the next cycle.
- The submission client (`feat/submission-client`) against a live `:server:run` on H2: a
  throwaway JVM test, not committed, drove `SubmitQuestion` and `GetMySubmissions` through the real
  CIO client. A fresh guest listed nothing; `"  Fly  "` came back stored as `Fly`, `PENDING`, its
  categories in declaration order; an option of 200 characters was stored. A blank option, the two
  options the same but for case, a newline, 201 characters and a U+2028 were each
  `INVALID_SUBMISSION` with the server's own message. The 21st pending was `SUBMISSION_LIMIT`, and
  the list held all 20, newest first. A session the server did not know was recovered as one fresh
  guest, who then owned the submission and listed only their own. In the tests, `SubmissionApiTest`
  pins the request and the reads, `SubmissionMapperTest` an unknown status as `OTHER` and a
  submission's categories mapped as a question's, with none or `OTHER` refused before sending,
  `SubmitQuestionTest` the same refused before a session is ensured,
  `DefaultSubmissionRepositoryTest` recovery after a 401 and the 422 and 409 end to end through
  `MockEngine`, and `SubmissionConsoleViewModelTest` the console section.
- Client resilience (`fix/client-resilience`, no server or contract change): `RequestTimeoutTest`
  pins, in virtual time, a call the server never answers failing at 60 s as
  `HttpRequestTimeoutException`, a 50 s cold start answered, the connect and socket timeouts handed
  to the engine on every call and on a retry after a refresh, the refresh's 5 minutes with the
  ordinary connect timeout (from `AuthApi.refresh` too), a 90 s refresh landing although the call
  that asked for it timed out, and a refresh that never ends giving up at 5 minutes with the old
  session kept. `RunApiOverHttpTest` pins a timeout as `NETWORK`, `HttpTraceTest` a timeout traced as
  one and a cancellation still as a cancellation. `AndroidTokenStorageTest`, an Android host test,
  pins `commit()` over `apply()`, off the caller's thread, one commit at a time in order, a failed
  commit thrown, and a write whose caller was cancelled landing. `SessionStorageFailureTest` pins a
  session the store could not make durable, as a guest is minted, on a clear and in recovery,
  failing the call as `NETWORK` rather than as a bare exception, with no guest minted twice for it.
  `DesktopApiBaseUrlTest` pins `WYR_API_BASE_URL`: bound by the desktop module, blank as unset,
  trimmed, and every malformed value refused with the variable named. By hand: an invalid value run
  through `./gradlew :app:desktopApp:run` stopped the app at start naming it, and a second run, on a
  reused configuration cache, named the new value.
- Client tests: `:core:domain` 32, `:core:data` 107, `:core:network` 52 (58 as Android host tests:
  the common ones and `AndroidTokenStorageTest`), `:app:shared` 109 (the ViewModels, the Koin graph
  and the desktop base URL); `:server` 156. `:app:shared` compiles for JVM, JS, wasmJs and the iOS
  simulator.
- `:app:androidApp:assembleDebug` produces a real APK.
- `ktlintCheck` clean across every module.

### NOT verified

- **The refresh rotation on a live server.** Nobody has re-run the curl pass since it landed, so
  the compare-and-set rotation is proven by tests only. The 1-point rule has been seen live, in
  the client run above.
- **The endless feed and vote replay on Postgres.** `RANDOM()` in the feed, the compare-and-set
  that starts a cycle, a first answer racing another (the 23505 aborts the transaction and Exposed
  reruns it), and the `SELECT ... FOR UPDATE` that makes a retry wait and replay have only run on
  H2. So has the skip, locked and then written as a vote is, with a first skip racing another on
  the `skips` key. The store races in `QuestionStoreTest`, `VoteStoreTest` and `SkipStoreTest`
  poll H2's `SESSIONS`, like `PlayerStoreTest`. The stats read has run only on H2 too: one
  statement with a correlated subquery on `players.current_cycle`. That one statement sees one
  committed state at READ COMMITTED is documented PostgreSQL behaviour, not something a test here
  has seen.
- **Question submission on Postgres.** The pending cap locks the author's `players` row
  (`SELECT ... FOR UPDATE`) and then counts in a later statement, which at READ COMMITTED sees a
  submission committed while it waited. That has run only on H2 (`SubmissionStoreTest` polls H2's
  `SESSIONS`); on PostgreSQL it is documented behaviour, not something a test here has seen. The
  client has run against a live `:server:run` on H2 only, and the console's section never.
- **Moderation on Postgres, and from any client.** A decision is a compare-and-set,
  `UPDATE ... WHERE id = ? AND status = 'PENDING'`, and a second decision on the question waits on
  the first's row lock and then re-checks that `WHERE` against the committed row. That has run only
  on H2 (`ModerationStoreTest` polls H2's `SESSIONS`); on PostgreSQL it is documented READ COMMITTED
  behaviour, as for the refresh rotation, not something a test here has seen. So are the category
  rows' delete and batch insert in the decision's transaction, and the new `(status, submitted_at,
  id)` index. The client has sent moderation requests only from the JVM (the live run above), and no
  browser has sent `X-Admin-Token`: only `CorsTest` has seen its preflight. Nobody has looked at the
  console's *Moderation* section on any platform.
- **Likes on Postgres, and in any client but the JVM.** Two first likes racing on the key (the 23505
  aborts the transaction and Exposed reruns it), two unlikes queuing on one row, the grouped count
  with its `COUNT(CASE ...)` and the stats' subquery have run only on H2 (`LikeStoreTest` polls H2's
  `SESSIONS`). The client has sent likes only from the JVM (the live run above), and nobody has
  looked at the console's Like button or its likes lines on any platform.
- **Multiple categories on Postgres, and in the client.** The `EXISTS ... IN` filter, the batch's
  second statement for its categories and the batch insert of a submission's categories have run
  only on H2. On the client, several categories per question and a selection of several have run
  only against `MockEngine` and the console ViewModel's fakes, never against a live `:server:run`.
- **READ COMMITTED and the refresh compare-and-set on Postgres.** Every race and burst in
  `PlayerStoreTest` runs on H2, even in the `server-postgres` job: it hardcodes `jdbc:h2:mem:`,
  because its wait-for-the-lock polling reads H2's `INFORMATION_SCHEMA.SESSIONS`. So the ci.yml
  note that isolation differences surface in that job holds only for `ApiFlowTest`'s sequential
  flows. On Postgres the rotation stays single-use because an `UPDATE` that waited on a row lock
  re-checks its `WHERE` against the committed row. That is documented Postgres behaviour, not
  something a test here has seen, and the same goes for the concurrent-seed recovery described on
  `Seed.questionsIfEmpty`. Porting the races means polling `pg_stat_activity` instead.
- **Timeouts on a real engine, against a real cold start.** Every timeout test runs on
  `MockEngine`, which enforces only the request timeout (Ktor's own timer). What each engine takes
  was read in the Ktor 3.5.1 sources: OkHttp maps the connect timeout to its own and the socket
  timeout to its read and write timeouts; CIO applies both, and drops its own 15 s request timeout
  once `HttpTimeout` is installed; Darwin takes only the socket timeout, as the request's idle
  timeout, so iOS has no 30 s connect limit; the browser engines apply neither. That the engines
  then enforce those is their documented behaviour, and the 60 s is a guess at Render's cold start,
  not a measurement: time the first request after the service has idled once it is deployed.
- **Durable session writes on a device.** `AndroidTokenStorageTest` pins which call is made and
  where, against a stand-in; that Android's `commit()` then survives a kill is its documented
  behaviour, not something seen here. Whether `NSUserDefaults` keeps a change the app is killed
  straight after (`IosTokenStorage`) is unchecked too.
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
  either: its actions are tested through its ViewModel, and the use cases behind them ran live,
  all but `SkipQuestion`, which has run only against `FakeServer`. `POST /v1/skips` itself has run
  only in the server's own tests. The *Submit a question* section has not been opened either: its
  ViewModel and line helpers are tested, and its use cases ran live, but it has never been drawn.

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
`io.ntole.wyr.di.DevApiBaseUrl`. The **desktop** client takes another server from
`WYR_API_BASE_URL`, read by its platform module (`PlatformModule.jvm.kt`), so it can be pointed at a
deployed server or another machine; `./gradlew` passes the variable on to the app:

```bash
WYR_API_BASE_URL=https://<service>.onrender.com ./gradlew :app:desktopApp:run
```

It is trimmed, blank counts as unset, and it must be an http or https URL of a host, a port
allowed, with no path, query or fragment (every route is an absolute path, so a path would be
dropped silently). Anything else stops the app at start with a message naming the variable. The
console's header shows the `api` in use. For the **web** client the server also needs
`ALLOWED_WEB_ORIGINS` set or CORS preflight will reject every request. The page's dev server
takes the first free port from 8080, so with the API already there it serves on 8081:

```bash
ALLOWED_WEB_ORIGINS=localhost:8081 ./gradlew :server:run
```

```bash
./gradlew :app:webApp:wasmJsBrowserDevelopmentRun
```

### Moderating

Moderation is off unless the server has an admin token (CLAUDE.md §8d): the admin routes are then
404, every submission stays pending, and the boot log says so. Give it one, at least 32 visible ASCII
characters with no whitespace, or it warns (shorter) or refuses to boot (whitespace or anything else):

```bash
export ADMIN_TOKEN=$(openssl rand -hex 32); echo "$ADMIN_TOKEN"
./gradlew :server:run
```

Then, in another shell, `export ADMIN_TOKEN=` the token echoed above, submit a question as a fresh
guest, read the queue, and approve it. `X-Admin-Token` carries the token, never `Authorization`, and
every admin route answers 403 `FORBIDDEN` without the right one:

```bash
ACCESS=$(curl -s -X POST localhost:8080/v1/auth/guest | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])')
curl -s -X POST localhost:8080/v1/questions -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' -d '{"optionA":"Fly","optionB":"Swim","categories":["SUPERPOWERS"]}'
curl -s localhost:8080/v1/admin/submissions -H "X-Admin-Token: $ADMIN_TOKEN"
curl -s -X POST localhost:8080/v1/admin/approvals -H "X-Admin-Token: $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' -d '{"questionId":"<id from the queue>","categories":["SUPERPOWERS","RANDOM"]}'
```

Or moderate from the dev console's *Moderation* section (below). Leave `categories` out to keep the
author's. To reject instead, send
`{"questionId":"<id>","reason":"Too close to a seed"}` to `/v1/admin/rejections`; the reason is
trimmed and must then be one line of at most 200 characters. `?status=APPROVED` or
`?status=REJECTED` on the queue lists decided submissions, and `GET /v1/me/questions` with the
guest's bearer token shows the author's view. Deciding a question twice is 409 `ALREADY_DECIDED`.

### The dev console

The app opens on the **Console** tab (`io.ntole.wyr.dev`). **Play** is the frozen game screen.

- **Session.** *Ensure session* mints a guest, or reuses the stored one. The header then shows the
  player id and when its access token expires. Opening the console reads the stats, which ensures a
  session too, so the first open mints a guest. *New guest* drops the session and the question
  queue, then mints a fresh player and loads a question for it.
- **Play.** *Skip* records the skip of the question on screen (`POST /v1/skips`), then loads the
  next question and reads the stats. The skipped one is not due for the rest of the cycle and
  comes back in the next, `answeredBefore` only if it was ever answered. A skip that fails is
  logged as `recordSkip` with its error, and the next question loads anyway, its entry ending
  `skip=unrecorded`; that question stays due, so the feed can serve it again this cycle. Skip is
  off until a question is loaded. The question shows every category it is filed under, `OTHER` for
  each one this build cannot name. *A* / *B* answer it, each tap as a new attempt, and the raw
  `VoteOutcome` appears below, `replayed` included. A question the feed looped back to shows
  `answeredBefore: true` and logs as `question=<id> looped`. The header's total points is the last
  outcome's `totalPoints`; the Stats section has the server's own count. The question shows its
  `likeCount` and `likedByMe` too, answered or not. *Like* (*Unlike* while `likedByMe`) sets the
  player's like of it (`POST /v1/likes`), logged as `setLike(questionId=<id> liked=<bool>)` with the
  server's answer, `question=<id> likes=<n> likedByMe=<bool>`, which the question then shows; the
  stats are read after. A like that fails leaves the question as it was, so pressing again asks for
  the same, which the server holds once. Liking your own question pays you a point: submit one,
  approve it (*Moderating*), and page *Next question* until it comes up.
- **Retry last vote (same attempt)**, under Play. Sends the last vote again unchanged, attempt id
  included, whether or not it got an answer. While it is still that question's latest answer the server
  replays it, logged as `+0 total=<n> replayed`. A vote that never landed is paid as an answer.
  Every vote's log entry shows its attempt id, so the two entries can be compared.
- **Answer many.** *Answer N* answers N questions in a row (1 to 50), alternating A and B. Each
  answer is logged as `answer` as it lands, then the run as `answered=N looped=K total=T`; a
  failure ends the run, and its vote is left for *Retry last vote*. **To see the loop:** the server
  seeds 24 questions, so press *New guest*, then *Answer N* with 25. The first 24 are the seeds in
  random order and the 25th logs `looped`: it is the first question of cycle 2, which serves all
  24 again in a new random order.
- **Stats.** Every number `GET /v1/me` returns: total points, answers given (re-answers count,
  replays do not), distinct questions answered, the cycle, how many questions are still due in it,
  and the likes the player's own questions hold (`likesReceived`). Read when the console opens, after
  every vote, *Skip*, *Like*, *Answer N* and *New guest*, and on
  *Read stats*. *Read stats* is an action like any other, logged as `readStats` whether it works or
  not. The other reads are logged only when they fail, as `refreshStats`. A read that fails keeps
  what was shown, which after a vote is nothing: a vote's outcome drops the stats it outdated. A red
  `MISMATCH totalPoints` line means the stats and the last outcome disagree: a vote landed whose
  answer was lost (*Retry last vote* replays it, and the flag goes), a vote from the Play tab, a
  like of one of the player's questions from another client between a vote and the first read to
  work after it, or a bug. The two are compared only for one player. A read the server refused as a
  dead session (a restarted `:server:run` does that) recovers it, and the stats are then a fresh
  guest's: instead of the flag, Stats shows `lastOutcome: paid to <id>, not compared`. A like of one
  of the player's questions, theirs or anyone's, moves the total without a vote, so once
  `likesReceived` differs from what the first read after the last vote counted, Stats shows
  `lastOutcome: likesReceived was <n> then, not compared` instead of the flag. A *Like* sent before
  any read after the last vote worked leaves nothing to measure from, so until the next vote Stats
  shows `lastOutcome: a like went out before likesReceived was read, not compared`.
  **To see the lazy cycle start:**
  once the last due question is answered or skipped, Stats shows the finished cycle with
  `dueThisCycle: 0`. The next cycle starts only when the feed is next asked for questions, which the
  console does when its queue is empty (*Next question*, *Skip*, or the next answer of *Answer N*).
  *Read stats* then shows it, with the whole pool due; a *Skip* that asked the feed shows it at
  once, since it reads the stats after its next question.
- **Questions.** Fetch the next question, or empty the local queue, and see its size.
- **Category.** A row of chips: *All*, then every category but `OTHER`, which no feed can be
  filtered to. Each category chip toggles that category in or out of the selection, and *All*
  empties it; *All* shows selected while nothing else is, since none selected is every category,
  and taking out the last category selected is *All* too. The selected chips are the repository's
  own selection (`QuestionRepository.categories`), so they show what the next fetch asks for, and
  "feed filtered to" names them. A change switches the feed (one `?category=<NAME>` per category
  selected, in declaration order, in the HTTP trace; none for *All*), drops the queue, and loads a
  question from the new selection, logged as `selectCategories(categories=<NAME>,<NAME>)`, `all`
  for none. It does not read the stats. *All* with nothing selected changes nothing: the queue
  stays, and the next question comes from it, with a request only when it is empty. The categories
  selected are one pool within the player's cycle: a question filed under several of them is served
  once, and once nothing in any of them is due while other questions are, they are served again,
  `looped` on what was answered, skipped questions included (provisional, CLAUDE.md §8b). A
  selection the server has no questions in logs `OUT_OF_QUESTIONS`, and stays selected. *New guest*
  and *Reset queue* keep the selection. The Play tab draws from the same repository, so it is
  filtered too.
- **Moderation** (`io.ntole.wyr.dev.moderation`). Type the server's admin token (the one echoed when
  starting it with `ADMIN_TOKEN`) into *admin token*; it is masked, held in memory only, and gone once
  the app restarts, and every button stays off until what is typed can be a token. *Load pending*
  lists the submissions waiting, oldest first, each with its options and categories. Under each, the
  chips pick the categories *Approve* files it under in place of the author's; none picked keeps the
  author's. *Reject* stays off until the reason typed is one line of at most 200 characters once
  trimmed. After every decision the queue is read again, so a decided submission leaves the list. The
  section's own log shows `loadPending`, `approve(questionId=... categories=keep|<NAMES>)` and
  `reject(questionId=... reason="...")`, and the server's refusals: `FORBIDDEN` for a wrong token,
  `ALREADY_DECIDED` for a question decided already (by another moderator too), `QUESTION_NOT_FOUND`.
  A server without `ADMIN_TOKEN` answers every admin route with a bare 404, which logs as `UNKNOWN`
  with a hint that moderation is off. Moderating never touches the player's session.
- **Vote by id.** Sends a vote for whatever id is typed, as a new attempt. An unknown id provokes
  `QUESTION_NOT_FOUND` (404). A known one is simply answered again and pays 1: there is no
  "already voted" any more.
- **Submit a question** (`io.ntole.wyr.dev.submission`, a ViewModel of its own). Type option A
  and B, and pick one or more category chips (every category but `OTHER`). *Submit* stays off until
  both options hold more than whitespace and a category is picked. The options go as typed, and the
  entry shows them quoted, then the submission as the server stored it: trimmed, categories in
  declaration order, `PENDING`. A stored one clears both options, unless they were changed while it
  was in flight, and keeps the categories. A refusal keeps everything and logs `err` with the
  server's own message: `INVALID_SUBMISSION` for what the options say (422; the fields are not
  single-line, so a line break can be typed to provoke it), `SUBMISSION_LIMIT` for the 21st pending
  (409). **My submissions** lists the player's own, newest first: status, categories, and for a
  rejected one the reason (`OTHER` is a status this build cannot name). Read when the console opens,
  after every *Submit*, a failed one too, and on *Refresh my submissions*, which logs
  `listSubmissions` whether it works or not; the other reads log only a failure, as
  `refreshSubmissions`. "listed for" is the player it was read as, which after *New guest* is the
  previous one until it is read again. The section has its own log, below the list, and runs one
  action at a time of its own; its requests show in the HTTP trace with the rest. That is not the
  console's, so *New guest* pressed while a read is in flight can mislabel the list it returns, as
  "not read" or as the new guest's. Nothing approves a submission until moderation is built, so
  every one stays `PENDING`.
- **Action log.** Every action, newest first: `ok`, `err` (the `DomainError` and its diagnostic
  message) or `crash` (anything else thrown), with how long it took. One action runs at a time.
- **HTTP trace.** Every request that went out, with status and time. A refreshed call shows as
  the 401, the refresh, and the retry. One that got no answer shows what the caller was thrown:
  `HttpRequestTimeoutException` once it ran out of time (CLAUDE.md §8a), never the cancellation Ktor
  wraps it in on the way.

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
  pins the client half. The server's `encodeDefaults` is pinned by `ApiFlowTest`'s fresh-guest
  stats test, which checks every stats field is sent at its default, and by `ServerJsonTest`, which
  checks an empty list of categories is sent too; a server-side slip only breaks client builds older
  than the server. A **list** of categories needs `QuestionCategoryListSerializer` on top, because
  coercion never reaches a list's elements: drop it from a property and one new category fails a
  whole batch on every older client. It decodes an unknown name as `UNKNOWN`, never dropping it,
  since the server decodes submissions with it too and a dropped name would let `[FOOD, NEWCAT]`
  through as `[FOOD]`; a missing list decodes as an empty one. The client then maps each `UNKNOWN`
  to `Category.OTHER`, beside the categories it can name, and an empty list to `OTHER` alone
  (`QuestionMapper`), because `Question.categories` is never empty. `WyrJsonTest` and
  `QuestionMapperTest` pin the two halves.
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
  knows what the player answered in their current cycle and serves only what is still due, which
  includes what is queued and the question on screen. `InMemoryQuestionCache` drops only ids still
  queued, and `DefaultQuestionRepository` drops the one it handed out last. An "ever seen" set is
  what used to end the game after one pass; do not bring one back. `OUT_OF_QUESTIONS` now means
  the server sent an empty batch, and nothing else.
- **The feed runs in cycles, and a cycle ends only when everything in it is answered or
  skipped** (CLAUDE.md §8d). Every question comes back once per cycle, in a new random order each
  time; nothing loops by answer time any more, and `answered_at` is information only. Batches are
  never topped up, so they shrink towards the end of a cycle. A skip is kept on the server
  (`skips`, one row per player and question) for the cycle it was made in, and the one due
  predicate in `QuestionStore` reads it beside the vote, so the feed and the stats' due count
  cannot disagree about it. A skip is no vote: it pays nothing, the tally never sees it, and it
  does not make a question `answeredBefore`. Only a skip the server never recorded leaves its
  question due: once that is the last one, the feed serves it again and nothing else until it is
  answered or skipped. A cycle is per player, so a category with nothing due is served again
  rather than starting the next cycle while other categories are still due, skipped questions
  included; that last part is provisional (CLAUDE.md §8b). Three server rules to keep: starting a
  cycle is a compare-and-set on the cycle read (`PlayerStore.startNextCycle`), and an answer and a
  skip each read their cycle after their row's lock (`VoteStore.cast`, `SkipStore.skip`). All three
  have races in the store tests.
- **Admin routes answer 403, never 401, and are absent without `ADMIN_TOKEN`** (CLAUDE.md §8d).
  The client answers any 401 by refreshing and then replacing the player's session, so an admin
  route must never send one: none sits inside `authenticate(JWT_AUTH)` or reads the bearer token,
  and each calls `requireAdmin` before it reads anything else. A new admin route goes through
  `moderationRoutes`, which registers nothing without a token, and needs its line in
  `ApiFlowTest.everyAdminRoute`, which the 403 and 404 tests run over. A decision is a
  compare-and-set on `PENDING`; an update by id alone would let a second moderator overwrite the
  first.
- **One predicate decides which questions a player may be served** (`QuestionStore.servable`,
  CLAUDE.md §8d): an approved one, to every player alike, its author included. The feed, the
  stats' due count, and votes, skips and likes (`QuestionStore.isServable`) all read it, so a
  pending or rejected question is served to nobody, due for nobody, and answering, skipping or
  liking it is 404. A new exclusion belongs there. `isServable` is a plain read because a question
  only ever becomes servable; a way to withdraw an approved question would need votes, skips and
  likes to lock the question's row.
- **An attempt id is made once per tap and reused only to retry that tap.** `AttemptId.random()`
  is the only way to make one. Making a new one for a retry pays twice; reusing one for a new tap
  turns that answer into a replay that pays nothing. The server stores only the latest attempt per
  player and question, so a retry that arrives after a newer answer pays as a fresh answer.
  `PlayViewModel` keeps a vote lost to `NETWORK` for Try again and moves on after any other
  failure, since a refused vote would fail the same way every time.
- **Every request times out, and a refresh far later than the rest** (`WyrHttpClient`, CLAUDE.md
  §8a). A call gets 60 s, 30 s of it to connect (on OkHttp and CIO; on iOS the 60 s socket timeout
  covers connecting, and a browser has only the request timeout), and the same 60 s as its socket
  timeout, which overrides OkHttp's 10 s read timeout: that alone would fail a cold start on
  Render's free tier. A request that spends the refresh token gets `refreshTimeout()`, 5 minutes,
  because a refresh abandoned after the server rotated the token orphans the guest; any new request
  that rotates a credential needs it too. A call stuck behind a refresh gives up only once the
  refresh ends. A timeout reaches `runApi` as the engine's or Ktor's own exception, so it is
  `NETWORK`. `RequestTimeoutTest` runs every case in virtual time, on a `MockEngine` given the test's
  dispatcher.
- **A session write returns once it is durable, and suspends for it** (`TokenStorage.write`,
  CLAUDE.md §8a). `AndroidTokenStorage` used `apply()`, which returns before the file is written,
  so a kill just after a refresh could come back with the rotated-out token and orphan the guest.
  It now `commit()`s, on `Dispatchers.IO` because the ViewModels write from the main thread, and
  one change at a time in the order asked (`limitedParallelism(1)`: on the plain pool, a clear could
  commit ahead of a write asked for before it). It commits inside `NonCancellable`, so a write asked
  for is never dropped, and a commit that fails throws `IOException`. `SessionStore.write` and
  `clear` suspend with it; `storeHolding` in the test fixtures stays a plain function by starting
  the in-memory write directly, since it never suspends. Because the write suspends, the refresh's
  check that the session is unchanged can now miss a change the data layer has asked for and not
  yet made: that widens the edge already noted in `WyrHttpClient`, which only a lock shared with the
  data layer closes. `AndroidTokenStorageTest` is an Android host test
  (`:core:network:testAndroidHostTest`, now in CI): it pins `commit()` over `apply()`, the thread,
  the order, the failure and the cancellation against a recording `SharedPreferences`, since the
  host has no real one. A failed commit leaves the data layer as `NETWORK`, never as the bare
  `IOException`, which the ViewModels do not catch, so the Play tab would crash on it:
  `DefaultSessionRepository` writes and clears the session through `runApi`, as the refresh's write
  already was. The change stays in memory, so the next call carries on with it.
- `Tally.percentB` is defined as `100 - percentA` rather than rounded independently, so the two
  always sum to 100. There is a property test over every split up to 40/40.
- `:server` must not depend on `:core:domain` (§3). That is why scoring lives in `:server`.
- Only `:core` and `:core:domain` enforce `explicitApi()`, so new public declarations there need
  an explicit `public`.
