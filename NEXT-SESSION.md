# Next session — pick up here

Read `CLAUDE.md` first (authoritative). This file is just the working handoff.

## Where we are

The vertical slice is built and running: **zero-click session → fetch questions → vote → see
the tally and points**. Server and all client targets except iOS are verified on this machine.

Repo initialized on `main` with the personal identity and `user.useConfigOnly = true` (§7).

**Deployed** (CLAUDE.md §8): **prod** `wyr-server` at https://wyr-server.onrender.com on Render
Postgres, promoted by hand with *Manual Deploy*, runs `d4a9dbf` since 2026-09-25, with V1 to V4
applied; **dev** `wyr-server-dev` on in-memory H2, deployed automatically from every green commit on
`main` (its URL is on its Render page).

**`feat/simple-accounts` is on `main`**, and on `origin/main`, at b175247: stage 1 took the recovery
secret, Block Store, the Keychain, the rollback mirror and the question's row lock out (CLAUDE.md
§8a, §8b). Stage 2 built simple accounts on the server (§8a, *Accounts*): V5, register, log in, log
out, and the username in `GET /v1/me` (*Accounts*, under *Running it locally*). Stage 3 built the
client: an account repository behind `RegisterAccount`, `LogIn` and `LogOut`, and the game's
**Account** tab, in every build (§8d, *The Account screen*; how to try it on a phone is under
*Accounts*). A `d4a9dbf` build of the app on a phone that keeps a recovery secret fails every call
against this server once its session dies, since the recovery it tries first is 404 here: install a
newer build on it.

**`feat/play-skip-like` is on `main`**, and on `origin/main`, at b8d992c: **Skip** and **Like**
moved from the dev console onto the game's **Play** tab, the second feature moved after the Account
tab (CLAUDE.md §8d, *The Play screen*, *Skipping*, *Likes*). How to try it on a phone is under *Skip
and Like on Play*.

**`feat/play-categories` is on `main`**, and on `origin/main`, at 63e38ce: the **category picker**
moved from the console's Category row onto the Play tab, the third feature moved (CLAUDE.md §8d,
*The Play screen*, *Categories*). The client alone changed; the server and the contract did not. How
to try it on a phone is under *Categories on Play*.

**`feat/submit-screen` is on `main`**, and on `origin/main`, at 61bfcad: **submitting** moved from
the console's *Submit a question* section onto a **Submit** tab of the game's own, in every build,
the fifth feature moved (CLAUDE.md §8d, *The Submit screen*, *Submitting*). The client alone
changed, with `SubmissionRules` in `:core:domain` so the form checks the options as they are typed;
the server and the contract did not. How to try it on a phone is under *Submit on its own tab*.

**On `chore/remove-console`** (from 61bfcad; not merged, nothing pushed): the dev console is gone
(the user, 2026-09-25: "console is not needed"). Every build, LOCAL, DEV and PROD alike, shows Play,
Submit and Account and opens on Play (CLAUDE.md §8d, *Current focus*); a LOCAL or DEV build names
its server on the Account tab's last line (§8e). Changes are tried through the game and the
moderation app (*Trying a change*, below). What only the console read went with it: the HTTP trace
(`HttpTrace`, `HttpTracing`); `SessionDiagnostics`, the session and token expiry its header
showed; the session port's `currentPlayerId` and `clear`, which only it called through the port (a
logout clears the session through `DefaultSessionRepository` itself); `Question.answeredBefore`,
which only it showed; and `PlayerStats.playerId` and `VoteOutcome.questionId`, which only it read
(the wire's `QuestionDto`, `PlayerStatsDto` and `VoteResultDto` still carry all three). The client
alone changed; the server only in a comment and a test's name.

### Verified working

- `:server` on H2: 311 tests, 309 green and 2 skipped (the PostgreSQL-only boot races), including
  78 end-to-end flow tests in `ApiFlowTest`. Flat scoring is covered there (every vote pays 1,
  majority and minority alike, and the total accumulates) and by `PlayerStoreTest`, which races
  awards for one player, and `SessionStoreTest`, which races refreshes of one token. The endless
  feed, re-answering and attempt replay are covered there too, and by
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
  and `PlayViewModelTest`, the Play screen's category picker: ticking, *All categories*, every
  category ticked played as all five and never as none (`PlayScreenTest` names them, not *All*), the
  selection sent to the repository before the next fetch, the question on screen dropped for one
  from it, the same selection keeping it, a vote lost to `NETWORK` never sent again once the
  categories change from its failure, and no change while anything is in flight
  (`feat/play-categories`; the console's Category row, which it replaced, had
  `DevConsoleViewModelTest`'s).
  Moderation (`feat/moderation`, server and contract only): `ModerationStoreTest` races two
  approvals of one submission, and an approval against a rejection, and exactly one decides it each
  time, with only the winner's categories written (dropping `PENDING` from the compare-and-set, or
  replacing the categories before the decision, fails them); it also pins what a decision writes,
  409 and nothing changed for a decided question or a seed, 404 for an unknown id, and the queue's
  order, bound, statuses and statement count. `ApiFlowTest` pins the effects end to end: an approved
  submission due at once for its author, a player midway through the cycle and one who had answered
  everything else (served it in cycle 1, not a new cycle); a rejected one served to nobody, 404 to
  answer or skip, its trimmed reason in the author's list; categories named on approval changing
  what `?category=` finds; 400 for every malformed decision or queue query; 403 `FORBIDDEN` on every
  admin route for a missing, wrong, case-changed or bearer-carried token, and before the body is
  read; 200 beside any player session, live, forged or dead, never 401; and 404 on every one with
  `ADMIN_TOKEN` unset. That was three routes then; `ApiFlowTest.everyAdminRoute` holds all six now,
  the question list, retirements and restorations (`feat/moderation-app`) among them.
  `AdminTokenTest` pins the comparison: one `MessageDigest.isEqual` of two 32-byte digests per
  check, whatever is presented. `ServerConfigTest` pins `ADMIN_TOKEN`'s parsing,
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
  against the wire's; and `ModerationConsoleViewModelTest` the console's section then: nothing sent
  without a valid token, Reject off until the reason is valid, the queue read again after every
  decision, picks for a submission no longer listed dropped, and the token in neither the state's text
  nor the log. It went with that section on `feat/moderation-app`, whose `ModerationViewModelTest` and
  `QuestionListViewModelTest` pin the moderation app instead (below).
  Likes (`feat/question-likes`, server and contract only; `:server` 179 tests with it, 65 of them
  flows): `LikeStoreTest` pins a like paying the author a point and an unlike taking it back, a
  repeat of either writing and paying nothing, likedByMe being each player's own, a self-like paid,
  a seed's likes paying nobody, a like being no answer, the author's total as their answers plus
  the likes their questions hold, 404 for a pending or rejected question and 401 for an unknown
  player; two first likes racing pay once (on the key, through Exposed's rerun; on the question's
  row lock from `feat/moderation-app` until `feat/simple-accounts`) and two unlikes
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
  `feat/moderation`, before the moderation app replaced that section: a throwaway JVM test, not
  committed, drove `ModerationConsoleViewModel` from the real Koin graph through the real CIO
  client. A curl guest had submitted three questions. Load
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
  likes-moved comparison, a like sent before any read after the vote worked included. On
  `feat/play-skip-like` the Like left the console with its tests and those of a like sent before any
  read: the console's keep only the likes-moved comparison, and `PlayViewModelTest` and
  `PlayScreenTest` cover the Play tab's Like.
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
  `MockEngine`, and `SubmissionConsoleViewModelTest` the console section (retired with it by
  `feat/submit-screen`, whose `SubmitViewModelTest` drives the Submit tab).
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
- Rate limiting (`feat/rate-limiting`, no contract change; `:server` 206 tests with it):
  `RateLimitTest` runs every group of routes to its budget and past it, each refusal 429 with
  `RATE_LIMITED` and a `Retry-After` of 1 to 60 s; a refused vote paying nothing, a refused
  submission not stored, and a refused refresh leaving its token live once the budget is back; two
  players behind one address with budgets of their own; a token forged for a player spending the
  address's budget and never the player's, and an expired one this server signed spending its
  player's, so it still gets its 401 once the address's is spent, while one expired past a refresh
  token's lifetime spends the address's; `/health` never refused; the right admin token not
  spending the failed-token budget, and once that is spent, refused exactly as every wrong guess
  and no token are until the budget is back; guesses refused by that budget spending none of the
  admin one; each of the queue, an approval and a rejection spending both admin budgets and refused
  by each; the defaults letting *Answer N*'s 50 through; one INFO line per refusal, naming the
  limit and the player and neither the token nor a client address; no header read with none
  trusted, and behind `CF-Connecting-IP` one budget per address it names, a forged
  `X-Forwarded-For` changing nothing and a request without it keyed by the socket peer; and a boot
  warning on Render with no header trusted. `ClientAddressTest` pins the value read and the
  fallbacks, `ServerConfigTest` the defaults and every `RATE_LIMIT_*` and `CLIENT_IP_HEADER` value
  refused, `X-Forwarded-For` and `Forwarded` included. Letting the right admin token past a spent
  failed-token budget (Ktor's own limiter lets a weight of 0 through), weighting it like a wrong
  one, moving an approval out of both admin groups or a rejection out of the admin one, keying an
  expired token by address or keeping it for longer than a refresh token lives, keying votes by
  address, reading the token unverified, dropping the 429 handler, the `Retry-After` or the log
  line, swapping the admin groups, leaving a guest mint or `/health` ungrouped, reading the header
  with none trusted, ignoring the trusted one, taking a list or the first of several values, or
  allowing `X-Forwarded-For` as the header, each fails them. On the client, `RunApiOverHttpTest`
  pins the server's 429 and a proxy's HTML 429 as `RATE_LIMITED`, sent once, and
  `DefaultVoteRepositoryTest` a rate-limited vote not resent and a rate-limited refresh keeping the
  session and minting no guest; recovering on `RATE_LIMITED` fails them. The console needed nothing:
  `resultOf` logs every `WyrException` as `err`, `RATE_LIMITED` included, and *Answer N* stops at
  it.
- Live run of the rate limits against the fat jar on Netty, `PORT=18433` with `ADMIN_TOKEN`,
  `TRUSTED_PROXY_HOPS=2` and budgets of 2 guests, 3 votes and 2 wrong admin tokens, by curl:
  `/health` five times, 200 each; three mints from one address behind the proxies, 200, 200 and
  429, with `Retry-After: 3600` and the `ErrorDto`; four votes 200, 200, 200 and 429, with the stats
  then at 3 points and 3 answers. The log had one `rate limit ... reached` line per refusal and no
  address or token anywhere, and a boot with `RATE_LIMIT_VOTES_PER_MINUTE=abc` stopped at start,
  naming it. Its hop count and its admin results (the right token let in past a spent failed-token
  budget) are what the review fixes below replaced.
- Live run of the review fixes against the fat jar on Netty (JDK 21), `PORT=18434` with
  `ADMIN_TOKEN`, `CLIENT_IP_HEADER=CF-Connecting-IP` and budgets of 2 guests and 2 wrong admin
  tokens, by curl: three mints naming one address, the last two with a forged `X-Forwarded-For`
  each, 200, 200 and 429; one naming another address 200; three with no header or a list in it,
  keyed by the socket peer, 200, 200 and 429; and from one address the admin queue with the right,
  wrong, wrong, wrong and right token and none, 200, 403, 403, 429, 429 and 429, the right one's
  429 identical to a guess's (`Retry-After: 60`, `RATE_LIMITED`), while the right token from
  another address was 200. The log had one line per refusal and no address or token anywhere. A
  boot with `CLIENT_IP_HEADER=X-Forwarded-For` stopped at start, naming it.
- The refresh-token grace window (`feat/refresh-grace-window`, CLAUDE.md §8a), on H2. V2 adds the
  previous token's three columns; `SchemaDriftTest` holds it to `Tables.kt`, and `MigrationsTest`
  now builds its database from before migrations as V1 alone and pins V2 on it both ways, baselined
  by this boot and baselined by an earlier one (production takes one or the other), rows kept with
  the new columns NULL. `PlayerStoreTest` pins a displaced token spent within the grace and refused
  at its end, a token spent twice then dead, the displaced token's own expiry, grace 0, and both
  races at READ COMMITTED: two refreshes with the current token both through, the first's token
  then the previous one; two with the previous token, exactly one. `ApiFlowTest` retries a refresh
  whose answer was lost and keeps the player and their point, and refuses a third use. Dropping the
  previous-token branch, its grace bound or its expiry, making the presented token the previous one,
  reading then updating by id, or the route passing no grace each fails them. On the client,
  `BearerSessionTest` and `SharedSessionStoreTest` pin the settling refresh when another tab's
  refresh of the same session overtook one (once only, never for another player's session);
  removing it, repeating it or settling another player's session each fails them.
- Live run of the grace window against the fat jar on Netty (JDK 21), `PORT=18091` and no
  `DATABASE_URL`: Flyway applied V1 and V2 to the in-memory H2, `/health` was 200, and a guest's
  first refresh token refreshed 200, again 200 as the same player, and a third time 401
  `INVALID_REFRESH_TOKEN`.
- The grace without a time bound (`feat/refresh-grace-unbounded`, CLAUDE.md §8a, §8b), on H2:
  `REFRESH_GRACE_SECONDS` unset is no bound, 0 off, a number a bound in seconds. `PlayerStoreTest`
  spends a displaced token days after its rotation, then never again, kills one at the first use of
  the token that displaced it, and runs both races and the expiry with no bound and again under an
  explicit 10 minutes, as it runs the other bounded cases. `ApiFlowTest` restamps a rotation 5 hours
  back and keeps the player and their point, and at 600 seconds takes a lost answer restamped 9
  minutes back and refuses one at 11. No bound read as 10 minutes or as none at all, the previous
  token's expiry dropped, the bound ignored, and the route passing no bound or a default of its own
  each fail them. The fat jar (JDK 21, `PORT=18093`, no `DATABASE_URL`) answered `/health` 200 and a
  guest's first refresh token 200, 200 as the same player, then 401. The client changed in comments
  only. Counts on the branch: `:server` 240 (2 skipped), `:core:domain` 34, `:core:data` 121,
  `:core:network` 70 (76 as Android host tests), `:app:shared` 127.
- Sessions and the recovery secret (`feat/recovery-secret`, merged as 9c9be37, deployed to prod as
  `d4a9dbf`) were verified on H2 at the time: V4, per-device sessions, the rollback mirror and its
  fold, the secret on the server, Block Store, the iCloud Keychain and the recovery flow on the
  client. `feat/simple-accounts` took the secret, Block Store, the Keychain and the mirror out
  again; of that work, per-device sessions and their races (`SessionStoreTest`) remain.
- Client tests: `:core:domain` 34, `:core:data` 120, `:core:network` 58 (64 as Android host tests:
  the common ones and `AndroidTokenStorageTest`), `:app:shared` 122 (the ViewModels, the Koin graph
  and the desktop base URL); `:server` 235, 2 of them skipped. 569 JVM tests in all, those 2
  included. `:app:shared` compiles for JVM, JS, wasmJs and the iOS simulator (JS and wasmJs not
  re-run on the grace-window branch).
- The moderation app's server and client half (`feat/moderation-app`, CLAUDE.md §8d *Moderation*),
  on H2. `QuestionListTest` pins the list of every question: newest first with the seeds marked,
  the status and category filters (any of each, a question in two categories once), pages that
  follow their cursors to a last one that says so, a question stored between two pages not moving
  one already listed, the tally and like count, a re-answer committed mid-page counted on one side
  or neither, and two statements a page whatever its length. `RetirementTest` pins retiring and
  restoring: served to nobody, due for nobody, a cycle finishing without it, 404 for a vote, skip,
  like and unlike, every number but what is due unchanged (§8c's sum included), the author's
  `RETIRED`, the queue's and the list's `RETIRED` apart from `APPROVED`, 409 `WRONG_STATUS` for
  every wrong status and 404, a seed, two retirements and two restorations racing (exactly one
  each), and, until `feat/simple-accounts` dropped the question's lock, a retirement waiting for a
  vote that held the question and a vote, skip, like and unlike waiting on a retirement and then
  404. `SeedTest` pins a retired seed
  through a second boot, `MigrationsTest` V3 on the pre-migration database and a database each
  build migrated in turn (`1 BASELINE`, `2 SQL`, `3 SQL`, rows kept), `SchemaDriftTest` V3 against
  `Tables.kt`, `ApiFlowTest` the four routes end to end (403, 404 with moderation off, 409, 400s),
  and `RateLimitTest` the new routes in both admin groups. Mutations caught: no lock on the
  servability read, a retirement's `WHERE` by status alone, a retired row read as approved,
  `servable` ignoring retirement, a full last page claiming another, the keyset's tie-break
  including the cursor's own id, one vote count read in a statement of its own, and V3 missing.
  On the client, `ModerationApiTest`, `DefaultModerationRepositoryTest`, `ModerationMapperTest`,
  `ModerationUseCasesTest`, `DataModuleTest` (`moderationDataModule` needs no storage and binds no
  session) and `WyrJsonTest` (`RETIRED` and `WRONG_STATUS`, and a build without them reading
  `UNKNOWN`) cover the rest. Counts: `:server` 263, 2 skipped; `:core:domain` 37, `:core:data`
  132, `:core:network` 75 (81 as Android host tests), `:app:shared` 127. Every client target
  compiles, iOS simulator main and test included, and `:app:androidApp:assembleDebug` builds. The
  fat jar on JDK 21, `PORT=18092`, no `DATABASE_URL` and a throwaway `ADMIN_TOKEN`: Flyway applied
  V1 to V3, `/health` 200, the list 200 with the seeds marked and a `nextCursor`, `seed-1` retired
  200 (`RETIRED`, `retiredAt` set), retired again 409 `WRONG_STATUS`, restored 200, and the list
  without the token 403.
- The moderation app, `:app:adminApp` (CLAUDE.md §8d *Moderation*), JVM tests only.
  `ModerationViewModelTest` drives the token and the queue over a scripted repository: nothing sent
  until what is typed can be a token, every request carrying it trimmed, the token absent from the
  state's text and from a new ViewModel, Lock forgetting everything and cancelling the action in
  flight (a lock guard dropped, or the cancel, fails it), approvals with and without categories,
  rejections only with a reason the server takes, the queue read again after every decision and
  every error but a 403 or a 429, after which nothing is read again, a failure kept once its
  submission is no longer listed. `TokenBarTest` drives the
  token field with clicks and keys through `ImageComposeScene`: Undo in it after a Lock brings
  nothing back (a field not made anew on Lock gives the whole token back). `ModerationOverHttpTest`
  runs it over the real client and a mock engine: 403, a bare 404, a 409 and a 429 with its
  `Retry-After`, a proxy's HTML 403 claiming no status but the one its detail line carries, and
  every request with the admin header and no bearer token. `AdminModuleTest`
  resolves the app from its own modules, with no platform module, and pins that nothing there can
  make or keep a player session: wired with the player's data module instead, it fails.
  `QuestionListViewModelTest` drives the list: pages read to the last one and no further, Load more
  at the filter the list was read at (ignoring it fails), a filter change dropping what was read and
  refused while a read runs, `OTHER` refused as a filter, Retire only after the dialog is confirmed
  and only for an approved question, a decision reading the list again as deep as it was shown
  (one page only fails it), a retirement or restoration putting the server's answer in its row as
  its one request with two pages shown (not putting it there fails it) and dropping it from a list
  whose filter no longer picks it, a decision from either screen reading the queue and a read list
  again, a retirement or restoration refused as a wrong token or by the rate limit reading nothing
  again, drafts kept for a question pending in the list alone, an empty page that claims another
  ending the read, and Lock keeping the filter alone. `DefaultModerationRepositoryTest` pins the
  queue and a page of the list asked for 100 at a time (`ModerationRepository.PAGE_SIZE`, which
  `ModerationMapperTest` holds to the server's `MAX_PAGE_SIZE`), and `LabelsTest` and
  `QuestionLabelsTest` a queue that long saying more may be waiting, its tab `Pending (100+)`.
  `ModerationOverHttpTest` adds a 409 `WRONG_STATUS` on a retirement. `ScreensDrawTest` draws both
  screens and the Retire dialog off screen (Compose's `ImageComposeScene`) in both themes.
  `DataModuleTest` pins that the game's `dataModule` binds
  nothing of the moderator's (binding the API back fails it), and `WyrHttpClientTest` and
  `RunApiOverHttpTest` a 429's `Retry-After` reaching `WyrException.retryAfter` (dropped at either
  step, they fail), and `CorsTest` a 429 to an allowed origin naming it in
  `Access-Control-Expose-Headers`, which a page's script needs to read it (without `exposeHeader`, it
  fails). Against the fat jar on JDK 21 (`PORT=18092`, no `DATABASE_URL`, a throwaway
  `ADMIN_TOKEN`), a throwaway JVM test, not committed, drove `ModerationViewModel` through the real
  CIO client, after a curl guest submitted two questions: a wrong token read `FORBIDDEN`; the queue
  listed both oldest first; approving the first under SUPERPOWERS and RANDOM worked and read the
  queue again; approving it again was `ALREADY_DECIDED`; the list at pending and approved, FOOD and
  SUPERPOWERS, held the other; rejecting it from the list with a padded reason stored it trimmed;
  every question was 20 and then, with Load more, 26; Retire asked, was cancelled and sent nothing,
  then confirmed retired the first, Restore put it back, a second Restore was 409; Lock cleared it
  all. After the review fixes (CORS exposing `Retry-After`, the token field made anew on Lock,
  nothing read again after a 403 or 429, a moved question put in its row, reads of 100), the fat jar
  on JDK 21 (`PORT=18092`, no `DATABASE_URL`, a throwaway `ADMIN_TOKEN`,
  `ALLOWED_WEB_ORIGINS=http://localhost:8081`, `RATE_LIMIT_ADMIN_PER_MINUTE=3`) answered `/health`
  200, the list at `limit=100` 200, a wrong token 403, the queue 200, and the fourth admin request,
  from that origin, 429 with `Retry-After: 60` and `Access-Control-Expose-Headers: Retry-After`.
  Counts at the branch head: `:server` 264, 2 skipped; `:core:domain` 37; `:core:data` 134;
  `:core:network` 77 (83 as Android host tests); `:app:shared` 106, the console's 21 moderation
  tests gone with the section; `:app:adminApp` 87. Every client target compiles, the moderation
  app's JVM, JS and wasmJs included (its test compiles and development and production executables
  too), as do the iOS simulator main and test, and `:app:androidApp:assembleDebug` builds;
  `WYR_SERVER_ONLY=1` still configures `:core` and `:server` alone.
  `DesktopEnvironmentNameTest` pins `WYR_ENV`. Its JVM, JS and wasmJs compiles run.
- `feat/simple-accounts`, stage 1 (simplify; CLAUDE.md §8a, §8b), on H2. Votes, skips and likes read
  servability without the question's row lock: the key races take their `INSERTING_INTO_*` waits
  back in `VoteStoreTest`, `SkipStoreTest` and `LikeStoreTest`, and `RetirementTest` pins a vote,
  skip and like in flight as a retirement commits landing all the same (putting the lock back fails
  six tests). No client keeps a recovery secret: `AuthApiTest` reads a mint that still carries one,
  as `d4a9dbf` sends, as its session. The server's mint answers the session's four fields alone and
  both recovery routes are 404 (`ApiFlowTest`). The mirror is gone: `SessionStoreTest` pins that
  nothing writes the players row's refresh columns, and `ApiFlowTest` drops all seven unused
  players columns and plays a guest through mint, refresh, feed, vote and stats (reading `Players`
  with `selectAll()` fails it). Counts: `:server` 272, 2 skipped (78 flows); `:core:domain` 37;
  `:core:data` 134; `:core:network` 78 (84 as Android host tests); `:app:shared` 106;
  `:app:adminApp` 87. ci.yml's verify job's three steps pass as written, as do the ios job's Kotlin
  compiles that need no Xcode (`:app:shared`'s main and test, and the three `:core` modules' tests).
  The fat jar (`WYR_SERVER_ONLY=1`) on JDK 21, `PORT=18096`, no `DATABASE_URL`: Flyway ran V1 to V4,
  `/health` 200, a mint answered four fields, its refresh token refreshed 200 as the same player,
  200 again (the grace) and 401 a third time, a vote 200 and the stats 1 point, and
  `POST /v1/auth/recover` and `POST /v1/me/recovery-secret` were 404; no token was in the log. The
  `server-postgres`, `docker-smoke` and `ios` jobs have not run on the branch.
- `feat/simple-accounts`, stage 2 (server accounts; CLAUDE.md §8a, *Accounts*), on H2. `PasswordsTest`
  pins the stored form, a salt per hash, a hash at another cost verifying at its own, and a stored
  string not in the form failing without naming it. `AccountStoreTest` races two players for one name
  (one wins, the other `USERNAME_TAKEN` after Exposed's rerun; without the read before the write the
  rerun fails again) and two registrations of one player (the compare-and-set lets one through;
  dropping it fails the test). `AccountFlowTest` covers every status of register, login and logout:
  422 for each rule broken, 409 taken in any case and already registered, 401 and 400; a login from a
  second device as a session of its own, both devices refreshing and playing as one player, a logout
  ending only its device's session, its current and grace tokens both; a wrong password, an unknown
  name and a name no account can have answered byte for byte alike; an unknown name refused only
  after a hash's time (skipping the hash fails it: 1 ms against 8.5 ms); and no password, hash or
  `pbkdf2` in the log. `RateLimitTest` has the three new groups and a login past its budget refused
  before its password is checked. `MigrationsTest` takes every path to V5, production's (V1
  baselined, V2 to V4 by the builds before, V5 by this one) among them, and `SchemaDriftTest` holds
  V5 to `Tables.kt`. Counts: `:server` 310, 2 skipped; `:core:domain` 37; `:core:data` 134;
  `:core:network` 78 (84 as Android host tests); `:app:shared` 106; `:app:adminApp` 87. ci.yml's
  verify job's three steps pass, tests forced to rerun, as do the ios job's Kotlin compiles that need
  no Xcode. The fat jar (`WYR_SERVER_ONLY=1`) on JDK 21, `PORT=18096`, no `DATABASE_URL`: Flyway ran
  V1 to V5 and `/health` answered 200; a guest voted, registered as `Smoke_1` (answered `smoke_1`),
  and was refused 409 registering again, a second guest 409 for `SMOKE_1` and 422 for `a b` and a
  five-character password; `GET /v1/me` named `smoke_1` with 1 point; a login as `SMOKE_1` from a
  second device answered the same player, and a wrong password and an unknown name the same 401
  `INVALID_LOGIN`; both devices refreshed; the first logged out (204), after which both its refresh
  tokens were 401 and the second's refreshed again with the point kept. No password, token or hash
  was in the log. The first registration took 43 ms (a cold hash), a login 12 ms. The
  `server-postgres`, `docker-smoke` and `ios` jobs have not run on the branch.
- Accounts on the client (`feat/simple-accounts`, stage 3). `AuthApiTest` sends a login past the
  Auth plugin: its 401 `INVALID_LOGIN` comes back once, with no refresh and no bearer (taking the
  `AuthCircuitBreaker` out fails it), and a registration and a logout go with the bearer.
  `DefaultAccountRepositoryTest`, over the real client and `FakeServer`: a registration keeps the
  player and its session, a first launch's goes once as the guest it mints, one on a dead session
  registers the fresh guest, a taken name leaves the session; a login from a second device stores
  the account's session in place of its guest's and leaves the first device's alone; a wrong
  password and an unknown name are `INVALID_LOGIN` with nothing refreshed, minted or replaced; a
  logout tells the server and the next call mints a guest, one the server refuses still logs the
  device out, and one with no session sends nothing. `AccountUseCasesTest`: a registration ensures
  the session first and one the rules refuse sends nothing; a login and a logout drop the question
  queue, a refused login does not. `AccountRulesTest` holds the rules to the server's, and
  `AccountLimitsTest` their numbers to `WyrApi.Limits`. `AccountViewModelTest` (14): the states, the
  rules' hints with nothing sent, a taken name and a wrong login under their forms with what was
  typed kept, the guest-progress warning once and the login after it, none for a guest without
  points, cancelling it, a logout to a fresh guest, a failed read and its retry, one action at a
  time, and the copy for a rate limit and offline. `AccountScreenDrawTest` draws every state in both
  themes. `RootScreensTest`: Account in every build, a PROD build opening on Play. Counts:
  `:server` 310, 2 skipped; `:core:domain` 47; `:core:data` 146; `:core:network` 82 (88 as Android
  host tests); `:app:shared` 123; `:app:adminApp` 87. Lint (forced), the verify job's tests (each
  test task forced to rerun) and client compiles, and the ios job's Kotlin compiles pass. The fat
  jar on JDK 21, `PORT=18096`, no `DATABASE_URL`: `/health` 200, then by curl a guest voted,
  registered as `Smoke_3` (`smoke_3`, 1 point in `/v1/me`), a wrong login was 401 `INVALID_LOGIN`,
  a login from a second device answered the same player, both refreshed, the first logged out
  (204) and both its tokens were 401 after, and the second refreshed with the point kept. Then the
  real client on the JVM's engine against the same server, from a throwaway test not committed: a
  registration kept the player; a wrong login on a second device was one 401, no refresh in its
  trace, its session untouched; the right one played as the account; a logout answered 204 and the
  next read minted a fresh guest; the first device stayed logged in. No password, hash or token was
  in the server's log. The server was stopped.
- Review fixes on `feat/simple-accounts`. A login trims its username (a keyboard's suggestion
  leaves a space after the word, which read as a wrong password); `AccountFlowTest` logs in as
  `bob `, ` bob` and a tab-and-newline-wrapped `BoB`, and still refuses `b ob` and the password with
  a space added (without the trim the test fails). The Account screen's section titles take
  `WyrTypeScale.sectionTitle`. CLAUDE.md §8b now says the app saves the session, not the password,
  and names the `d4a9dbf` phone build that breaks. Counts: `:server` 311, 2 skipped; `:core:domain`
  47; `:core:data` 146; `:core:network` 82 (88 as Android host tests); `:app:shared` 123;
  `:app:adminApp` 87. Lint (forced), the verify job's tests (each forced to rerun) and client
  compiles, and the ios job's Kotlin compiles pass. The fat jar on JDK 21, `PORT=18096`, no
  `DATABASE_URL`: V1 to V5 applied, `/health` 200; a guest voted and registered as `Smoke_4`
  (`smoke_4`, 1 point), a wrong login was 401 `INVALID_LOGIN`, a login as `SMOKE_4 ` (trailing
  space) from a second device answered the same player, both refreshed, the first logged out (204)
  and its token was 401 after, the second refreshed with the point kept. No password or refresh
  token in the log. The server was stopped.
- Skip and Like on the Play tab (`feat/play-skip-like`). `PlayViewModelTest` (23, 9 before): Skip
  records the skip and shows the next question with no vote cast; a skip that fails moves on all the
  same and is not sent again; a second tap sends one skip; Skip does nothing while the vote is in
  flight or once the question is answered; the like count as served before answering; a like shows
  the server's count; a like then an unlike each put the server's answer on the question, the
  opposite of what it showed each time; a failed like leaves the question as it was with the error,
  and the next press asks for the like again; an answer naming another question is not put on the
  one shown; a like after answering keeps the reveal; nothing else goes while a like is in flight,
  nor a like while the vote is; and a like answered once the player pressed Next is not put on the
  next question. Each guard, the id check, the moved-on check, the failure and the like's direction
  were broken one at a time, and each broke its test. `PlayScreenDrawTest` draws the Play screen in
  every state, the like states among them, in both themes, and `PlayScreenTest` the count, the
  button and the failure copy. Review found that a like row of its own under the cards took 56 from
  them, so an iPhone SE's reveal (375x599 under the tab row) lost its vote counts. The count, Like
  and Skip or Next question now share one row, a like's failure takes the verdict's line, and
  `PlayScreenDrawTest` draws every state at 375x599 as well and asks each how much height it needs,
  measured 400 wide with one short line an option so that CI's wider Linux fonts wrap nothing: the
  reveal needs 569 of 599 (625 with the row of its own, which failed the test), and a failed like
  adds nothing to it (a failure on a line of its own failed that test). With two lines an option the
  reveal needs 593 at 375 wide on this Mac, as `main`'s layout does. The console lost its Skip and
  Like and their tests (`:app:shared`'s `DevConsoleViewModelTest` 49, 62 before), and the
  bookkeeping only its likes needed (`likeSentBeforeMeasure`, `likesUnmeasuredAtOutcome`). Counts:
  `:server` 311, 2 skipped (from the build cache: untouched); `:core:domain` 47; `:core:data` 146;
  `:core:network` 82 (88 as Android host tests); `:app:shared` 129; `:app:adminApp` 87. Lint
  (forced), the verify job's tests (each client test task forced to rerun) and client compiles,
  `assembleDebug` included, and the ios job's Kotlin compiles pass. Nothing was run on a device or
  against a server.
- `:app:androidApp:assembleDebug` produces a real APK.
- `ktlintCheck` clean across every module.

### NOT verified

- **`CF-Connecting-IP` on a live Render service.** `CLIENT_IP_HEADER=CF-Connecting-IP` in
  `render.yaml` rests on Render's docs (every request to a web service passes through Cloudflare)
  and on others' reports of requests reaching live Render services, all of which carried it, set to
  the client, beside `X-Forwarded-For` chains of two and of three entries. Nothing here has seen
  it. Check it once deployed, with `RATE_LIMIT_GUESTS_PER_HOUR=3` set for the check and then
  removed: from one machine the fourth mint must be 429; a fifth sent with its own
  `CF-Connecting-IP: 198.51.100.1` must still be 429 (200 means the value a client sends gets
  through, and a client picks its own address); and a mint from another network, a phone's
  hotspot say, must be 200 (429 means the header is missing, and every client shares the proxy's
  address). The server's log names no address, so the check is by status alone. Until it passes,
  the per-address budgets are not to be relied on.
- **The limits against real traffic.** The budgets are starting points nobody has watched: a
  household or a mobile carrier's shared address (CGNAT) shares 10 new guests an hour, and an IPv6
  client can rotate through its prefix for fresh per-address budgets. Every count is overridable
  without a build (`RATE_LIMIT_*`). CORS exposes `Retry-After` to a page on an allowed origin, so the
  moderation app's page can say how long to wait (`CorsTest` pins the header), but no browser has
  read it yet. Counts live in one instance's memory and reset with every restart, a deploy or a
  free-tier spin-down included.
- **The refresh rotation on a live server.** The grace window has run against the fat jar on H2
  (above), never on Render, so its races are proven by tests only. The 1-point rule has been seen
  live, in the client run above.
- **V2, V3 and V4 on PostgreSQL, and on production.** `SchemaDriftTest` and `MigrationsTest` run
  the scripts on PostgreSQL only in the `server-postgres` CI job, which passed V2 and V3 on `main`
  (9a7902f). Production runs `d4a9dbf` with V1 to V4 applied (the user's deploy of 2026-09-25);
  nothing here has read its history. Check, read-only, that it reads `1 BASELINE`, `2 SQL`,
  `3 SQL`, `4 SQL`, and that `sessions` has a row for every `players` row with a
  `refresh_token_hash`. The next script after V4 runs there at the next Manual Deploy.
- **Accounts on PostgreSQL, and on production.** V5 has run only on H2; `SchemaDriftTest` and
  `MigrationsTest` take it to PostgreSQL in the `server-postgres` job, not yet run on the branch.
  The race for one username is H2's alone (`AccountStoreTest` polls H2's `SESSIONS`): on PostgreSQL
  the second writer waiting on the first's uncommitted name and failing with 23505 once it commits is
  documented behaviour. Production takes V5 at its next Manual Deploy. The hash's cost on Render's
  tenth of a CPU is an estimate (0.1 to 0.2 s): read a login's time in its log line once deployed.
- **The Account tab on a device.** No build with it has been installed or run: the screen is drawn
  off screen on the desktop (`AccountScreenDrawTest`) and its ViewModel driven over fakes. Nothing
  has seen Android's password manager offer to fill or save the fields; that rests on the autofill
  content types and on Compose committing autofill once no autofillable field is left on screen
  (read in `AndroidAutofillManager`'s source, Compose 1.11), and iOS and the browsers may do nothing
  with them. The real client has run against a real server only on the JVM, against the local fat
  jar; not from a phone, not against dev, and not through the refresh that turns a `d4a9dbf` access
  token (no `sessionId`) into one a logout takes. Nor has anyone seen its server line
  (`chore/remove-console`) on a phone: it is drawn off screen, and found there by its semantics.
- **Skip and Like on the Play tab on a device.** No build with them has been installed or run: the
  screen is drawn off screen on the desktop (`PlayScreenDrawTest`), which proves it measures, draws
  and fits 599 high but not what it shows (there is no Compose UI test library in the tree), and its
  ViewModel is driven over fakes. Its renders on this Mac's Skia were looked at as images in review,
  in the light theme only, never on a phone. Nobody has sent a skip or a like from the app to a
  server. Android's 360x640 class (about 520 high) cuts the reveal's percentages as `main` did.
- **The category picker on a device.** The same holds for it (`feat/play-categories`): drawn off
  screen, and its renders on this Mac looked at as images in review, both themes, never on a phone.
  `PlayScreenDrawTest` checks by pixels that the screen draws its dialog, and draws what is ticked
  in it, not what the dialog shows. Nobody has tapped it, nor seen a platform's dialog open and
  close it. On Android's 360x640 class a question not answered yet no longer fits either, since the
  categories row is drawn in every state: its option cards are squeezed below their least height
  (CLAUDE.md §8b, *The categories row on the Play screen*, provisional).
- **The `:core` modules' tests on iOS.** The ios CI job runs `:app:shared`'s tests on the simulator
  and only compiles the `:core` modules' tests, which Kotlin/Native refused while their
  names held commas (`SharedSessionStoreTest`'s among them, from before `feat/recovery-secret`, and
  five the moderation app's branch added, renamed at the merge); their JVM and Android host runs are
  what cover them. `:core:domain`'s iOS test compile joined the job after the merge, which renamed
  the one name with a comma the moderation branch gave it.
- **The settling refresh in a real browser or desktop pair.** Two tabs sharing `localStorage`, or
  two desktop instances sharing JVM preferences, have raced a refresh only in
  `SharedSessionStoreTest` on `MockEngine`. JVM preferences sync between processes on their own
  schedule, so two desktop instances may not see each other's write in time for it to matter. The
  edge `WyrHttpClient` notes remains: both clients checking the store before either writes can
  still leave the displaced token there, until a lock shared across processes exists.
- **The endless feed and vote replay on Postgres.** `RANDOM()` in the feed, the compare-and-set
  that starts a cycle, a first answer racing another (the 23505 aborts the transaction and Exposed
  reruns it), and the `SELECT ... FOR UPDATE` that makes a retry wait and replay have only run on
  H2. So has the skip, locked and then written as a vote is, with a first skip racing another on its
  key. The store races in `QuestionStoreTest`, `VoteStoreTest` and `SkipStoreTest` poll H2's
  `SESSIONS`, like `PlayerStoreTest`. The stats read has run only on H2 too: one
  statement with a correlated subquery on `players.current_cycle`. That one statement sees one
  committed state at READ COMMITTED is documented PostgreSQL behaviour, not something a test here
  has seen.
- **Question submission on Postgres.** The pending cap locks the author's `players` row
  (`SELECT ... FOR UPDATE`) and then counts in a later statement, which at READ COMMITTED sees a
  submission committed while it waited. That has run only on H2 (`SubmissionStoreTest` polls H2's
  `SESSIONS`); on PostgreSQL it is documented behaviour, not something a test here has seen. The
  client has run against a live `:server:run` on H2 only.
- **Moderation on Postgres, and from any client.** A decision is a compare-and-set,
  `UPDATE ... WHERE id = ? AND status = 'PENDING'`, and a second decision on the question waits on
  the first's row lock and then re-checks that `WHERE` against the committed row. That has run only
  on H2 (`ModerationStoreTest` polls H2's `SESSIONS`); on PostgreSQL it is documented READ COMMITTED
  behaviour, as for the refresh rotation, not something a test here has seen. So are the category
  rows' delete and batch insert in the decision's transaction, and the new `(status, submitted_at,
  id)` index. The client has sent moderation requests only from the JVM (the live run above), and no
  browser has sent `X-Admin-Token`: only `CorsTest` has seen its preflight. Nobody has opened the
  moderation app on any platform (below). The question list (`GET /v1/admin/questions`,
  `feat/moderation-app`), whose counts are correlated subqueries and whose pages are a keyset on
  `(submitted_at, id)` compared in the database's collation, and retirement and restoration have
  passed on PostgreSQL in `ApiFlowTest`'s flows, in `main`'s `server-postgres` job at 9a7902f (run
  36074122336). Only the races are still H2's alone: `RetirementTest`'s poll H2's `SESSIONS`, as
  `ModerationStoreTest`'s do.
- **Likes on Postgres, and in any client but the JVM.** Two first likes racing on their key, and two
  unlikes on the like's row, the grouped count with its `COUNT(CASE ...)` and the stats' subquery
  have run only on H2 (`LikeStoreTest` polls H2's
  `SESSIONS`). The client has sent likes only from the JVM (the live run above), and nobody has
  pressed the Play tab's Like on any platform.
- **Multiple categories on Postgres, and in the client.** The `EXISTS ... IN` filter, the batch's
  second statement for its categories and the batch insert of a submission's categories have run
  only on H2. On the client, several categories per question and a selection of several have run
  only against `MockEngine` and the Play ViewModel's fakes, never against a live `:server:run`.
- **READ COMMITTED and the refresh compare-and-set on Postgres.** Every race and burst in
  `PlayerStoreTest` and `SessionStoreTest` runs on H2, even in the `server-postgres` job: each
  hardcodes `jdbc:h2:mem:`, because its wait-for-the-lock polling reads H2's
  `INFORMATION_SCHEMA.SESSIONS`. So the ci.yml note that isolation differences surface in that job
  holds only for `ApiFlowTest`'s sequential flows. On Postgres the rotation obeys the grace window's
  rules under a race because an `UPDATE` that waited on a row lock re-checks its whole `WHERE`, both
  halves of its `OR` included, against the committed row, and evaluates its `SET` against that row.
  That is documented Postgres behaviour (EvalPlanQual), not something a test here has seen, and the
  same goes for the concurrent-seed recovery described on `Seed.questionsIfEmpty`. Porting the races
  means polling `pg_stat_activity` instead.
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
- **What CI's first run proved, and what it did not.** All four jobs passed on the first push,
  2026-09-24 (run 36007354550). `ios` linked the framework, ran the simulator tests and built the
  Swift app with `xcodebuild`, so the iOS app compiles and links, but nobody has launched it on a
  simulator or a device. `docker-smoke` built the Render image and saw it answer `/health`; nothing
  is deployed. `server-postgres` ran the server suite against PostgreSQL 16, so every flow through
  `runServer` (`ApiFlowTest`) has passed on the real dialect; the store-level race tests
  (`PlayerStoreTest`, `VoteStoreTest`, `SkipStoreTest`, `LikeStoreTest`, `ModerationStoreTest`,
  `SubmissionStoreTest`) poll H2's `SESSIONS` and still run on H2 only, which is what the
  "on Postgres" bullets above still mean.
- **The UI has not been looked at on a device.** It compiles and its ViewModels are tested, but no
  screenshot has been taken on a phone, a simulator or in a browser; the Play screen's off-screen
  renders on the desktop were looked at as images in review (*Skip and Like on the Play tab on a
  device*, above). Treat the layout and the §5b palette in practice as unreviewed. `SkipQuestion`,
  behind the Play tab's Skip, has run only against `FakeServer`, and `POST /v1/skips` itself only in
  the server's own tests. The Submit tab (`feat/submit-screen`) has not been opened either: it is
  drawn off screen (`SubmitScreenDrawTest`), its renders on this Mac were looked at as images in
  review, both themes, and its ViewModel is driven over fakes; its use cases ran live, but nobody
  has typed a question into it on a phone, nor seen a keyboard's Enter in its wrapping fields.
- **The moderation app has not been opened.** No window has been shown and no page served: its
  screens were drawn off screen by `ScreensDrawTest` and looked at as images once, and its
  ViewModel has run against a local fat jar from the JVM only (*Verified*, above), never against
  dev or prod, and never from a browser, so no page has sent `X-Admin-Token` across CORS. The
  webpack build of its page has not run, so neither has the check of `kotlin-js-store`'s lock
  against it. `ScreensDrawTest` and `TokenBarTest` run with the host's Skia, which CI's Linux runner
  has yet to run; `TokenBarTest` sends the desktop's keys, and Undo in the page's token field has not
  been tried.

## Running it locally

Server first, then a client. The server defaults to in-memory H2 and logs a warning saying so.

```bash
./gradlew :server:run
```

```bash
./gradlew :app:desktopApp:run
```

Android uses `http://10.0.2.2:8080` (the emulator's alias for the host loopback); desktop, iOS
simulator, and web use `http://localhost:8080`. All four are `WyrEnvironment.LOCAL`'s, which a build
targets unless it names another environment (below); on Android that is the `localDebug` variant, not
the default. The **Account** tab's last line names the environment and the URL in use.
`WYR_API_BASE_URL`, which pointed the desktop client at any server, is retired (CLAUDE.md §8e): name
an environment instead (below). For the **web** client the server also needs `ALLOWED_WEB_ORIGINS`
set or CORS preflight will reject every request. The page's dev server takes the first free port from 8080, so
with the API already there it serves on 8081:

```bash
ALLOWED_WEB_ORIGINS=localhost:8081 ./gradlew :server:run
```

```bash
./gradlew :app:webApp:wasmJsBrowserDevelopmentRun
```

### How to run against dev/prod

Every client build targets one server environment, chosen when it is built, `local` unless it names
another (CLAUDE.md §8e). `dev` is `wyr-server-dev` at https://wyr-server-dev.onrender.com (in-memory
H2, reset on every deploy), `prod` is https://wyr-server.onrender.com; both answered `/health` with
200 on 2026-09-24. The last line of the **Account** tab names the environment and its URL in a
`local` or `dev` build (*Server: Dev (https://wyr-server-dev.onrender.com)*), and a `prod` build names
none; every build shows the same three tabs, Play, Submit and Account. Each environment keeps a
guest of its own, so switching between them loses neither.

- **Android**: Android Studio's *Build Variants* panel, where `devDebug` is the default, since a
  phone can reach dev and not the developer's machine. `localDebug` is for the emulator against
  `./gradlew :server:run`, `prodDebug` for production. They install side by side as *WYR Local*,
  *WYR Dev* and *WYR*. From the command line: `./gradlew :app:androidApp:installDevDebug`.
- **Desktop**: the `WYR_ENV` variable, which `./gradlew` passes on to the app:

  ```bash
  WYR_ENV=dev ./gradlew :app:desktopApp:run
  ```

- **Web**: the `wyr.env` Gradle property, at build time. The server must list the page's origin in
  its `ALLOWED_WEB_ORIGINS` (Render dashboard, `sync: false`) or CORS preflight rejects every request:

  ```bash
  ./gradlew :app:webApp:wasmJsBrowserDevelopmentRun -Pwyr.env=dev
  ```

- **iOS**: the `WYR_ENV` build setting in `app/iosApp/Configuration/Config.xcconfig` (`local`, `dev`
  or `prod`), then build again. The file is committed, so put it back to `local` before committing,
  or pass it to one build instead: `xcodebuild ... WYR_ENV=dev`.

Not verified yet: no flavor has been installed or run; the three debug APKs were built and each one's
id, label and cleartext flag read back with `aapt2`. Desktop stopped at start naming a bad `WYR_ENV`
run through `./gradlew :app:desktopApp:run`, but has not been run against dev. The web constant was
generated for `dev` and refused for a bad name, but no page has been served. On iOS only the Kotlin
compiles here. The `ios` CI job's `xcodebuild` is the first to process the Info.plist and its
simulator tests the first to run the bundle read, on a test bundle without the key; nothing has yet
run the app to read its own key.

### Rate limits

The server limits locally too, with the same budgets as on Render (CLAUDE.md §8b), each keyed by the
socket peer or the player. The one a developer meets first is 10 guests an hour: an eleventh fresh
guest in the hour (every fresh install, clear of storage and logout makes one) answers 429, which the
**Account** tab shows as *Too many tries. Wait N s, then try again.*
Raise any budget for a session with its variable, and a refused request says which one in the log:

```bash
RATE_LIMIT_GUESTS_PER_HOUR=1000 ./gradlew :server:run
```

### Stats on Account

The game's **Account** tab (*Accounts*, below) shows the player's stats under the points (CLAUDE.md
§8d, *The Account screen*, *Stats*), in every build, PROD's included: the answers given and the
questions they went to, the cycle and the questions left in it, and the likes the questions the
player submitted hold, as `GET /v1/me` counts them, read each time the tab is shown. The server
needs nothing new.

**`feat/account-stats` is on `main`**, and on `origin/main`, at 52f36bb: the fourth feature moved;
the client alone changed. Verified on this Mac: `AccountViewModelTest` (17, 14 before) reads a
guest's stats and a registered player's, and again on each showing; `AccountScreenDrawTest` (3, 1
before) draws every state at 400x900 and 375x599 in both themes, reads every line off the screen's
semantics, and holds every state without a form to 599 high (509 at most); `DevConsoleScreenTest`
the console's likes-moved line. A screen showing only the points, and the cycle swapped with what is
left, each broke a test. Counts: `:server` 311, 2 skipped (from the build cache: untouched);
`:core:domain` 47; `:core:data` 146; `:core:network` 82 (88 as Android host tests); `:app:shared`
135 (129 before); `:app:adminApp` 87. Lint, the verify job's tests (client tasks forced to rerun)
and client compiles, `assembleDebug` included, and the ios job's Kotlin compiles pass. **Not
verified:** nothing has run on a device or against a server, and the renders were looked at on this
Mac only.

**To try it on a phone** (`devDebug`, against the dev server, whose in-memory H2 forgets everything
on a deploy or a spin-down). The numbers are a fresh guest's, with only the 24 seeds on the server
and no category picked:

1. Uninstall any older *WYR Dev* (an install over it keeps its session, and its numbers), then
   `./gradlew :app:androidApp:installDevDebug`. Answer 3 questions on
   **Play**, skip 1, and open **Account**: *3 points*, *3 answers to 3 questions*, *Cycle 1: 20
   questions left* (the 24 seeds less the 4), *0 likes on questions you submitted*.
2. Answer or skip on Play until no question is left: once Play has asked for more, Account shows
   *Cycle 2: 24 questions left*, the answers and questions as they were (*Cycle 1: 0 questions left*
   before, if it has not asked yet).
3. A like of one of your own questions (*Skip and Like on Play*, step 6) shows as *1 like on questions
   you submitted*, and a point more.

### Accounts

A guest registers to keep its points and logs in with the same name and password on another device
(CLAUDE.md §8a, *Accounts*). The game's **Account** tab does both, in every build, PROD's included
(§8d, *The Account screen*).

**To try it on a phone** (`devDebug`, Android Studio's default variant, against the dev server; dev
is in-memory H2, so a deploy or a free-tier spin-down forgets every account):

1. Install and open *WYR Dev*: `./gradlew :app:androidApp:installDevDebug`. The first read mints a
   guest. Answer a few questions on **Play**.
2. **Account** shows *Playing as guest* and the points. Under *Register*, type a username (3 to 20 of
   letters, digits and `_`; the hint under the field turns red at a space or a bad length and
   Register stays off) and a password (6 or more; *Show* reveals it), then **Register**. The tab
   shows *Logged in as* the name, lower-cased, with the same points. Android's password manager
   offers to save the two as the form goes.
3. Uninstall the app and install it again: a fresh guest with no points.
4. **Account** → *Log in* with the same name (any case) and password (the password manager offers
   to fill them). With points as a guest, the first *Log in* shows the warning that they stay
   behind, and *Log in anyway* goes ahead. The tab then shows the account and its points, and Play
   goes on as it.
5. **Log out**: the device plays on as a fresh guest, and the account is only a login away. A wrong
   password shows *Wrong username or password.* under Log in; a name another player has shows
   *That username is taken.* under Register.

The same by curl, against `./gradlew :server:run`:

```bash
ACCESS=$(curl -s -X POST localhost:8080/v1/auth/guest | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])')
curl -s -X POST localhost:8080/v1/auth/register -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' -d '{"username":"Bob_1","password":"correct horse"}'
curl -s localhost:8080/v1/me -H "Authorization: Bearer $ACCESS"
curl -s -X POST localhost:8080/v1/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"BOB_1","password":"correct horse"}'
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/v1/auth/logout -H "Authorization: Bearer $ACCESS"
```

Registering answers `{"username":"bob_1"}`, the name lower-cased, and `/v1/me` then names it. A name
must be 3 to 20 of `a`-`z`, `0`-`9` and `_` once lower-cased, nothing trimmed, and a password 6 to 128
characters of any kind, or it is 422 `INVALID_USERNAME` or `INVALID_PASSWORD`; a name another player
has is 409 `USERNAME_TAKEN`, and registering twice 409 `ALREADY_REGISTERED`. The login answers a
`SessionDto` for a new session of the same player, the name trimmed and in any case; a wrong
password and a name with no account are the same 401 `INVALID_LOGIN`. The logout answers 204 and
ends only the session `$ACCESS` was issued for: that session's refresh token is 401 from then on, and
the login's session lives on. Logins are 20 a minute per address (`RATE_LIMIT_LOGINS_PER_MINUTE`),
registrations 20 an hour per player.

### Submit on its own tab

The game's **Submit** tab writes a question and lists your own (CLAUDE.md §8d, *Submitting*), in every
build, PROD's included. Submitting earns no points;
once approved, each like the question holds pays you 1.

**To try it on a phone** (`devDebug`, against the dev server, whose in-memory H2 forgets everything on
a deploy or a spin-down; the server needs nothing new):

1. `./gradlew :app:androidApp:installDevDebug`, open *WYR Dev*, and go to the **Submit** tab, between
   Play and Account. *My submissions* reads *None yet* for a fresh guest.
2. Type option A and B and tap one or more categories. Each option says what is wrong as it is
   typed: nothing but spaces is *Write something here*, a line break *One line, with no line breaks
   or tabs*, over 200 characters *At most 200 characters*, and B the same as A, ignoring case and the
   spaces at either end, *The two options must be different*. **Submit** stays off until nothing is
   wrong and a category is picked.
3. **Submit**: the form clears, *Sent* shows above the button, and the question tops *My
   submissions*, trimmed, as *Pending: waiting for a moderator*, with its categories.
4. Approve it in the moderation app (*The moderation app*, below): `WYR_ENV=dev ./gradlew
   :app:adminApp:run`, type dev's `ADMIN_TOKEN` (its Environment tab on Render), *Load pending*,
   pick categories in place of yours if you like, and **Approve**. On the phone, leave the Submit tab
   and come back: *Approved: in the game*, and **Play** serves it in the current cycle. A rejection
   shows as *Rejected:* and the reason typed, and a question retired under *All questions* as
   *Retired: out of the game for now*.
5. The 21st question pending shows *You have 20 questions waiting for review already* and keeps the
   form. Airplane mode, then **Submit**: *Can't reach the game. Check your connection.*, the form
   kept to send again, and the same under *My submissions*, with **Try again**. Still in airplane
   mode, leave the tab and open it again, then **Submit** once more: *My submissions* shows no list,
   only the failure and **Try again**, never a spinner. Back online, **Try again** lists them.

Locally the same works against `ADMIN_TOKEN=... ./gradlew :server:run` (*Moderating*, below), with
`./gradlew :app:desktopApp:run` for the game and `./gradlew :app:adminApp:run` to approve.

### Skip and Like on Play

The game's **Play** tab skips and likes (CLAUDE.md §8d, *Skipping* and *Likes*), in every build,
PROD's included.

**To try it on a phone** (`devDebug`, against the dev server, whose in-memory H2 forgets everything on
a deploy or a spin-down; the server needs nothing new):

1. `./gradlew :app:androidApp:installDevDebug` and open *WYR Dev*: it opens on the **Play** tab, as
   every build does.
2. One row under the two options: the like count (`0 likes` on a question nobody likes), **Like**
   and **Skip**.
   Press **Like**: the count goes up by one and the button reads **Unlike**; press it again and both
   go back. The count is the server's, so another player's like shows only when the question is
   served again.
3. Press **Skip**: the next question comes, and no points. The skipped one is not served again this
   cycle: with only the 24 seeds and no category picked, it comes back once the other 23 are answered
   or skipped, in the next cycle.
4. Answer a question: the reveal keeps the like count and **Like**, and **Next question** stands where
   Skip was. A like there works the same. The reveal is as tall as it was before Skip and Like, so
   what fitted a phone then fits it now: on an iPhone SE, each side's vote count.
5. Airplane mode, then **Like**: the question stays as it was and the line under the cards says
   *Can't reach the game right now. Try again.*, where the reveal's verdict stands once the question
   is answered. Network back on, **Like** again: it goes through. **Skip** offline
   moves on all the same while questions are queued, and shows *Can't reach the game* with *Try again*
   once they run out.
6. A like of your own question pays you a point: submit one on the **Submit** tab (*Submit on its
   own tab*), approve it in the moderation app, and play until it comes up. Like it, and the
   **Account** tab's points go up by one; the reveal's total is the vote's, so it shows the point
   from the next vote on.

### Categories on Play

The game's **Play** tab picks the categories played (CLAUDE.md §8d, *Categories*), in every build,
PROD's included. The selection lives in memory for the app's
life, so a launch plays every category again.

**To try it on a phone** (`devDebug`, as for *Skip and Like on Play*; the server needs nothing new):

1. `./gradlew :app:androidApp:installDevDebug`, open *WYR Dev*, and go to the **Play** tab. Under the
   title: **All** over *change categories*, which should read as something to tap.
2. Tap it: a dialog of *All categories* (ticked) and the five categories. Tick *Food* and *Ethics*:
   nothing changes behind the dialog yet. **Play**: the question on screen goes, and the next is filed
   under Food or Ethics; the header reads *Food, Ethics*, beside the points once you answer.
3. Open it again and **Cancel**, or tap outside it: nothing changes. **Play** with what is already
   played: the question stays.
4. Tick every category: the header cuts the names short on its one line, and the reveal is as tall as
   before (each side's vote count still shows on an iPhone SE).
5. While a vote or a like is in flight the categories do nothing when tapped. *All categories*, then
   **Play**, goes back to the whole feed. Categories the server has no questions in would show
   *You've answered everything we have*, with the categories there as the way out; every category
   has seeds, so only a server without them shows it.

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

Or moderate from the moderation app (*The moderation app*, below). Leave `categories` out to keep
the author's. To reject instead, send
`{"questionId":"<id>","reason":"Too close to a seed"}` to `/v1/admin/rejections`; the reason is
trimmed and must then be one line of at most 200 characters. `?status=APPROVED` or
`?status=REJECTED` on the queue lists decided submissions, and `GET /v1/me/questions` with the
guest's bearer token shows the author's view. Deciding a question twice is 409 `ALREADY_DECIDED`.

Every question, seeds included (`"seed":true`), newest first with its tally and like count, is
`/v1/admin/questions`, narrowed by `?status=` and `?category=`, each repeatable and matching any.
A page ends with `nextCursor` while more follow; send it back as `?cursor=` for the next:

```bash
curl -s 'localhost:8080/v1/admin/questions?status=PENDING&status=APPROVED&category=FOOD&limit=5' \
  -H "X-Admin-Token: $ADMIN_TOKEN"
```

To take an approved question out of play, a seed included, and put it back (provisional, CLAUDE.md
§8b). A retired one is served to nobody and 404 to vote on, skip, like or unlike, keeps what it
earned, and lists as `RETIRED`; the wrong status either way is 409 `WRONG_STATUS`:

```bash
curl -s -X POST localhost:8080/v1/admin/retirements -H "X-Admin-Token: $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' -d '{"questionId":"seed-1"}'
curl -s -X POST localhost:8080/v1/admin/restorations -H "X-Admin-Token: $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' -d '{"questionId":"seed-1"}'
```

### The moderation app

`:app:adminApp` (CLAUDE.md §3, §8d *Moderation*) moderates and does nothing else: a desktop window
or a browser page, with no player session. Its header names the server it talks to and that
server's URL, production's in red, and the desktop window's title names both too. It needs the
server's admin token, typed into *Admin token*: masked, held in memory only, gone once the app
closes or *Lock* is pressed, which also forgets everything read with it.

- **Local**, against `./gradlew :server:run` started with an `ADMIN_TOKEN` (*Moderating*, above);
  type the token it echoed:

  ```bash
  ./gradlew :app:adminApp:run
  ```

- **Dev and prod**: the `WYR_ENV` variable, as for the game's desktop client. Type that service's
  own `ADMIN_TOKEN`, from its Environment tab on Render (`sync: false`, never committed); dev's and
  prod's differ, and a local one works on neither:

  ```bash
  WYR_ENV=dev ./gradlew :app:adminApp:run
  WYR_ENV=prod ./gradlew :app:adminApp:run
  ```

- **In a browser**: `-Pwyr.env`, local when absent. The server must list the page's origin in its
  `ALLOWED_WEB_ORIGINS`, or CORS refuses every request, the token's header with it. The page's dev
  server takes the first free port from 8080, so beside a local API on 8080 it is 8081, and 8082 if
  the game's page already holds 8081:

  ```bash
  ALLOWED_WEB_ORIGINS=localhost:8081,localhost:8082 ADMIN_TOKEN=... ./gradlew :server:run
  ./gradlew :app:adminApp:wasmJsBrowserDevelopmentRun
  ./gradlew :app:adminApp:wasmJsBrowserDevelopmentRun -Pwyr.env=dev
  ```

  Against dev or prod the page's origin goes into that service's `ALLOWED_WEB_ORIGINS` on Render;
  for prod the desktop app needs no such change, so prefer it there.

**Pending** is the queue, oldest first, read on *Load pending*, the oldest 100 at most (a queue
that long says more may be waiting, and its tab reads `Pending (100+)`): each submission's options,
categories, age and id. The chips pick the categories *Approve* files it under in place of the
author's, none keeping the author's; *Reject* stays off until the reason typed is one line of at
most 200 characters once trimmed. After every decision the queue is read again, so a decided
submission leaves it, and a line above it says what the decision did; not after a 403 or a 429,
which decided nothing and would refuse the read too. A failure shows where it
happened: under the submission, or above the queue, named by its options, once the read after it no
longer lists it. `Wrong admin token (403)`; `An answer this build cannot name`, with its status in
the `server:` line under it, where a bare 404 is a server without `ADMIN_TOKEN` (or a build without
that route); `Already decided (409)`; and `Too many requests (429): try again in N s`, where ten wrong
tokens in a minute lock the address out, the right token too, until the wait is over.

**All questions** is every question, seeds included, newest first, read on *Load*, a page of 100 at
a time with *Load more*. The chips narrow it by status (*Retired* among them) and by category, any
of each, none being all; changing them drops what was read, and *Load* reads it at the new filter.
Each question shows its options, categories, status, seed or player's, votes and likes, when it was
stored, reviewed and retired, and a rejection's reason. An approved one has *Retire...*, which asks
first in a dialog; a retired one has *Restore*; a pending one has the queue's chips, reason and
buttons, sharing what was picked and typed with the queue. A retirement or restoration shows the
question as the server answered it, in its place, or drops it once the chips no longer pick it; a
decision, or a move that failed, reads the list again as deep as it was shown, so the question shows
where it now stands without losing your place. A question another moderator moved first says `Its
status changed first (409)`. Every admin request spends the address's 60 a minute (CLAUDE.md §8b): a
retirement or restoration is one; a decision is one, one more for the queue, and one per 100
questions the list shows; nothing is read again after a 403 or a 429.

### Trying a change

There is no dev console (`chore/remove-console`): a change is tried through the game, as a player
would, and moderated through the moderation app, against `./gradlew :server:run` or dev. Each
feature's *To try it* steps are above. What the console used to provoke on purpose goes this way now:

- **Which server.** The **Account** tab's last line in a `local` or `dev` build; a `prod` build names
  none (CLAUDE.md §8e).
- **A fresh guest.** Uninstall, or clear the app's storage, or **Log out** a registered player on
  **Account**. The desktop client keeps its session in JVM preferences, one per environment, and the
  browser in `localStorage`.
- **Cycles.** The server seeds 24 questions: answer or skip on **Play** until none is left, and
  **Account** shows the finished cycle with *0 questions left*, then *Cycle 2: 24 questions left* once
  Play has asked for more.
- **Retrying a vote.** Answer on **Play** in airplane mode: the vote fails as offline, and *Try again*
  with the network back sends it as the same attempt, paid once if the first send never landed and
  replayed for nothing if it did (CLAUDE.md §8d, *Retry safety*).
- **What no screen sends**, such as a vote for an unknown id (404 `QUESTION_NOT_FOUND`) or a
  refresh token replayed: curl, as under *Accounts* and *Moderating*, above.

## Roadmap (agreed 2026-09-23)

UI polish is paused and the work is functionality-first, in the game's own screens; the engineering
dev console it began behind is gone (`chore/remove-console`). Game rules live in CLAUDE.md §8d. Each
item is one short-lived branch, in order:

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
3a. `fix/read-committed` *(done)* — transactions at READ COMMITTED; refresh-token rotation is a
   compare-and-set.
4. `feat/dev-console` *(done; removed by `chore/remove-console`)* — the engineering UI, as the
   default root.
5. `feat/endless-feed` *(done)* — per-player feed, re-answering, idempotency key; then
   `feat/feed-cycles` *(done)*: every question once per cycle, a new random order each cycle.
6. `feat/player-stats` *(done)* — `GET /v1/me`.
7. `feat/question-submission` + `feat/submission-client` *(done)* — submit, list your own, pending
   cap 20; authors are served their own questions like anyone else.
8. `feat/moderation` *(done)* — admin-token routes (off when `ADMIN_TOKEN` is unset), approve with
   optional new categories, reject with a reason; console section.
9. `feat/question-likes` *(done)* — like/unlike any question (own included), +1 per like held to
   the author, count visible before answering.
10. `feat/category-play` + `feat/multi-category` *(done)* — questions carry one or more
    categories; the console plays several at once (any match).
    Also done: `feat/skip-per-cycle` (a skip returns next cycle) and `fix/client-resilience`
    (request timeouts, durable Android session writes, `WYR_API_BASE_URL` on desktop).
11. Pre-deploy hardening and the first Render deploy *(done 2026-09-24)* — first deployed
    `4cdc819` to prod; then rate limiting (`feat/rate-limiting`), Flyway migrations
    (`feat/db-migrations`, prod baselines at V1), the dev/prod split (`chore/dev-and-prod`) and
    the refresh-token grace window (`feat/refresh-grace-window`, the first migration after V1:
    prod takes V2 at its next Manual Deploy). Left: the `CF-Connecting-IP` check on dev (*NOT
    verified* above), and moving `wyr-postgres` to a paid instance type by about 2026-10-24.
12. `feat/moderation-app` *(done, on `main` at 9a7902f)* — moderation moves out of the player app's
    console into an app of its own: the list of every question, retiring and restoring (V3;
    provisional, CLAUDE.md §8b), and `:app:adminApp`, with its pending queue and the list of every
    question (*The moderation app*, above), which replaced the dev console's *Moderation* section.
13. `feat/recovery-secret` *(done, merged after the moderation app, deployed to prod at `d4a9dbf`)*:
    sessions per device (V4) and a recovery secret. The user found the secret, Block Store, the
    Keychain and the rollback mirror too much for a simple game (2026-09-25), so
    `feat/simple-accounts` takes them out again, keeping per-device sessions and the grace.
14. `feat/simple-accounts` *(on `main` and `origin/main` at b175247)* goes on to simple accounts
    (CLAUDE.md §8b, *Accounts*): play as a guest at once, register optionally and keep the points,
    log in on another device. Built on the server (stage 2: V5, register, log in, log out, the
    username in the stats) and in the game (stage 3: the account repository and the **Account** tab,
    the first feature moved out of the console, CLAUDE.md §8d). Still to do: read its CI run and try
    it on a phone (*Accounts*). No-click sign-in (Play Games Services, Game Center) comes later,
    once there is an Apple developer account.
15. `feat/play-skip-like` *(on `main` and `origin/main` at b8d992c)* — Skip and Like move from the
    console onto the **Play** tab, the second feature moved (CLAUDE.md §8d). Still to do: try it on a
    phone (*Skip and Like on Play*).
16. `feat/play-categories` *(on `main` and `origin/main` at 63e38ce)* — the category picker moves
    from the console's Category row onto the **Play** tab, the third feature moved (CLAUDE.md §8d).
    Still to do: the user's call on the categories row, which takes 56 from a question not answered
    yet (CLAUDE.md §8b, *The categories row on the Play screen*), then try it on a phone
    (*Categories on Play*). Then `feat/account-stats` (at 52f36bb) and `feat/submit-screen` (at
    61bfcad), both on `main` and `origin/main` too, moved the stats and submitting, the last of the
    console's features.
17. **Now:** `chore/remove-console` — the console goes, with nothing in its place; every build shows
    Play, Submit and Account, and a LOCAL or DEV build names its server on Account. Next: review,
    merge, push, CI, then try it on a phone (*Trying a change*).

**For the moderation app.** Everything it needs is in `io.ntole.wyr.core.domain.moderation`, and
none of it needs or makes a player session:

- *Wiring:* `moderationDataModule(environment)` (`:core:data`) binds the moderation repository and
  the use cases over an HTTP client of their own, with no `TokenStorage`, so a platform entry point
  needs only the `WyrEnvironment` it targets (CLAUDE.md §8e). Without Koin it is
  `DefaultModerationRepository(ModerationApi(WyrHttpClient.create(environment.apiBaseUrl,
  SessionStore(InMemoryTokenStorage(), environment))))`.
- *The token:* `AdminToken.of(typed)` is null for what no header could carry; hold it in memory only,
  as the moderation app does, and hand it to every call.
- *The queue:* `GetPendingSubmissions(token)`, `ApproveSubmission(token, id, categories)` (none keeps
  the author's, `OTHER` refused), `RejectSubmission(token, id, RejectionReason.of(text))`, each
  answered with a `Submission`.
- *Every question:* `GetQuestions(token, QuestionFilter(statuses, categories), after)` answers a
  `ModeratedQuestionPage`: `questions`, newest first, and `next`, the `QuestionCursor` to pass back as
  `after` with the same filter, null on the last page. A `ModeratedQuestion` has its options,
  categories, `SubmissionStatus` (`PENDING`, `APPROVED`, `REJECTED`, `RETIRED`, `OTHER`), `isSeed`,
  `submittedAt` / `reviewedAt` / `retiredAt`, `rejectionReason`, `tally` (`Tally`, with its
  percentages) and `likeCount`. A filter holding `OTHER` throws before sending.
- *Retiring:* `RetireQuestion(token, id)` and `RestoreQuestion(token, id)` answer the
  `ModeratedQuestion` as it now stands.
- *Errors:* every call throws `WyrException`: `FORBIDDEN` for a wrong token, `WRONG_STATUS` or
  `ALREADY_DECIDED` for a question another moderator moved first, `QUESTION_NOT_FOUND`, `NETWORK`,
  `RATE_LIMITED`, and `UNKNOWN` for a server with moderation off (a bare 404).
- *Built on it:* `:app:adminApp`, with its pending queue and the list of every question (*The
  moderation app*, above). The game's `dataModule` binds nothing of the moderator's: the game does not
  moderate.

**Remote:** `github.com/niktok1/would-you-rather` (private), `origin`, pushed over SSH through the
`github-wyr` host alias with a deploy key scoped to this repo (CLAUDE.md §7). `gh` is logged in to
the personal account for reading CI. Render is set up from the blueprint (`wyr` on the personal
account); its `ADMIN_TOKEN`s are set by hand in each service's Environment tab.

Deferred: SQLDelight, a leaderboard, UI polish and WCAG. The game's tabs are the app (CLAUDE.md §8d,
*Current focus*).

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
- **The schema is Flyway's, and every change to it is a new script** (CLAUDE.md §8b).
  `DatabaseFactory.init` runs `Migrations.migrate` before the seed, and nothing calls
  `SchemaUtils.create` in production any more. The store tests still build their tables with it,
  which is sound only because `SchemaDriftTest` shows it builds what the scripts build, names
  included. To change a table: edit `Tables.kt`, run `./gradlew :server:pendingMigration`, and write
  the draft it prints up as the next `V<n>__<what_it_does>.sql` in
  `server/src/main/resources/db/migration`, in lower case and unquoted so H2 and PostgreSQL both run
  it, with a default or a backfill for the rows already there. Never edit a script that has shipped:
  Flyway refuses to boot on a changed checksum. Forget the script and `SchemaDriftTest` fails, on H2
  locally and on PostgreSQL in CI. A database a server built before migrations is recorded at V1 by
  this build's first boot on it, without running V1, so its history shows `1 BASELINE`; a database
  nothing had booted on runs V1 and shows `1 SQL`. The Render production database, built by `4cdc819`
  at the first deploy with the same table definitions, is recorded `1 BASELINE` by the first
  migrating build that boots on it, and runs V2, V3 and V4 in that boot or later ones. Before a
  migrating build first boots on any other database it did not build, compare schemas read-only
  (`pg_dump --schema-only`, CLAUDE.md §8b), since the baseline checks only that V1's tables exist.
  Never let `WYR_TEST_JDBC_URL` name the production database: the test suite and `pendingMigration`
  wipe the database it names. V2 moved `MigrationsTest`'s pre-migration database onto V1 (V1 run
  alone, the history dropped) and made it read rows with `SELECT *`, since `Tables.kt` names columns
  V1 lacks, and V4 made it read only the tables there are, since it names a table V1 lacks too; a
  new script adds its row to `BASELINED_HISTORY` and what it does to existing rows to
  `afterLaterScripts` (`ADDED_COLUMNS` for a column added empty). That database's rows are written
  through the stores only where the SQL they run names V1's columns alone: a `selectAll` on a table
  a later script changed fails there (V3 moved the seed's check to the id alone), and a mint now
  opens a session, in a table V1 lacks, so its player is inserted as a build before sessions minted
  one (`playerAsMintedBefore`, V4). The H2 draft omits `COLUMN` and upper-cases everything; write it
  in lower case, one `ALTER TABLE` per column, since H2 takes no list of `ADD`s. Two boots at once
  are safe on PostgreSQL, under Flyway's advisory lock, only because `Migrations.migrate` takes the
  baseline itself:
  Flyway's `baselineOnMigrate` sent a boot that lost a race on an empty database to the baseline,
  where it failed, so keep it off. H2's DDL commits as it goes, releasing Flyway's lock there, so
  the four-at-once races skip H2, and `MigrationsTest` interleaves two boots one step at a time on
  H2 instead. Flyway warns at every boot that H2 2.4.240 is newer than the 2.3.232 it has verified;
  that is only the dev database. exposed-migration drafts drops for the indexes H2 builds for
  foreign keys, which `pendingStatements` leaves out.
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
  awards at a hand-picked READ COMMITTED, as `SessionStoreTest` races refreshes, so those tests stay
  discriminating whatever the server's level becomes.
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
- **The rate limiter runs before authentication and before the handler** (CLAUDE.md §8b). Ktor's
  RateLimit intercepts the Plugins phase, ahead of `authenticate`, however the two are nested, so no
  principal is there when a key is picked: a per-player key verifies the bearer token itself
  (`verifiedPlayerId`), and must keep verifying it, or a forged token could spend any player's
  budget. The same order is what makes a refused request do nothing. A new route joins a group with
  `rateLimit(RouteLimit.X) { ... }`; a new group needs its budget in `RateLimits`, its
  `RATE_LIMIT_*` variable, and its line in `RateLimitTest.groups`, which runs every group past its
  budget. `/health` stays in none. A server under test for anything else takes `NO_PRACTICAL_LIMIT`,
  so a flow never fails on a budget it is not about. The admin routes are in two groups, the
  failed-token one asked first; it weighs a request with the right token at 0, so keep that check
  the same as `requireAdmin` (`AdminToken.admits`, shared by both). Ktor's own limiter lets a
  weight of 0 through whatever is left, so that group's limiter is wrapped in `LockingOut`, which
  refuses it once the budget is spent: without it, 200 among the 429s would tell a guesser which
  guess was right.
- **A client address comes only from a header the proxy in front overwrites** (CLAUDE.md §8,
  `CLIENT_IP_HEADER`), on Render Cloudflare's `CF-Connecting-IP`. Never `X-Forwarded-For`: every
  proxy appends to it, the client writes its leftmost entry, and how many of Render's proxies stand
  behind Cloudflare has been reported both ways, so no count of entries from the right holds. With
  no header trusted the socket peer is the address; a request without the trusted header, or with
  more than one value, keys by the peer too (`clientAddress`). Log no address: in production it
  comes from a header.
- **Admin routes answer 403, never 401, and are absent without `ADMIN_TOKEN`** (CLAUDE.md §8d).
  The client answers any 401 by refreshing and then replacing the player's session, so an admin
  route must never send one: none sits inside `authenticate(JWT_AUTH)` or reads the bearer token,
  and each calls `requireAdmin` before it reads anything else. A new admin route goes through
  `moderationRoutes`, which registers nothing without a token, and needs its line in
  `ApiFlowTest.everyAdminRoute`, which the 403 and 404 tests run over, and in
  `RateLimitTest.ADMIN_ROUTES`. A decision is a compare-and-set on `PENDING`, and a retirement or
  restoration one on standing at approved or at retired (`ModerationStore.move`); an update by id
  alone would let a second moderator overwrite the first.
- **One predicate decides which questions a player may be served** (`QuestionStore.servable`,
  CLAUDE.md §8d): an approved one not retired, to every player alike, its author included. The feed,
  the stats' due count, and votes, skips and likes (`QuestionStore.isServable`) all read it, so
  a pending, rejected or retired question is served to nobody, due for nobody, and answering,
  skipping, liking or unliking it is 404. A new exclusion belongs there. `isServable` is a plain
  read, with no lock on the question (*decided 2026-09-25*; `feat/simple-accounts` dropped
  `lockIfServable`): one question's votes, skips and likes never queue on its row, two first ones by
  a player race on their key, and one in flight as a retirement commits may still land
  (`RetirementTest` pins it). Retirement is a column,
  `questions.retired_at`, beside an `APPROVED` status, and `statusOf` / `standsAt`
  (`io.ntole.wyr.server.question`) read the two as one: read a question's status through them,
  never `Questions.status` alone, or a retired question reads as approved.
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
  because a refresh abandoned after the server rotated the token leaves a token the server takes
  once more at most, and not at all if it was already the previous one (a time bound on the grace,
  if one is set, must outlast this); any new request that rotates a credential needs it too. A
  call stuck behind a refresh gives up only once the refresh ends. A timeout reaches `runApi` as
  the engine's or Ktor's own exception, so it is `NETWORK`. `RequestTimeoutTest` runs every case
  in virtual time, on a `MockEngine` given the test's dispatcher.
- **A refresh token works twice at most: once current, once more as the previous one until the
  next rotation** (CLAUDE.md §8a, `REFRESH_GRACE_SECONDS`: unset no time bound, 0 off, a number a
  bound in seconds). Every rotation, whichever token it spent, makes the token current until then
  the previous one, stamped now, so a token spent as the previous one is gone and the one it
  displaced is the previous one in its place. Keep `SessionStore.rotate`'s rotation of a session one
  `UPDATE` whose `WHERE` holds the whole check and whose `SET` copies the current columns into the
  previous ones in SQL: a read first, or an update by id, lets two racers with the previous token both
  through (`SessionStoreTest` races it). Keep stamping the rotation with no bound too: a bound set
  later reads it. A bound, if set, must stay longer than the client's `REFRESH_TIMEOUT` (5 minutes), and
  nothing but the KDocs on each side ties them, since `:server` cannot see `:core:network`. On the
  client, a refresh that finds the store moved on to the same player's other session refreshes once
  more as it (`refreshAs`), since both tabs' refreshes go through and a previous token survives one
  refresh only. `REFRESH_GRACE_SECONDS` that is not a whole number from 0 to a year fails the boot,
  naming it. The cost, accepted for guests: a used copy of a refresh token keeps working beside the
  original while the two take turns refreshing. What remains, in CLAUDE.md §8b (*Refresh answers
  lost past the grace*): a refresh that spends the previous token and whose answer is lost too, as a
  settling refresh's lost answer usually does, leaves a spent token.
- **A refresh token lives in its session, and nowhere else** (CLAUDE.md §8a, *Sessions*). A refresh
  reads and writes its session's row alone. The players row's old refresh columns, the mirror's mark
  and the recovery secret's hash are unused since `feat/simple-accounts`: no statement may name
  them (not even a `selectAll()` of `Players`), so a later migration can drop them and a rollback to
  this build still runs, which `ApiFlowTest` pins by dropping them. A rollback to a build from before
  sessions is not supported (CLAUDE.md §8b, *Rollbacks*). The server's `SessionStore` is not the
  client's, which keeps the stored session in `:core:network`.
- **An account is a username and a password hash on a player, and a login is a new session**
  (CLAUDE.md §8a, *Accounts*). The rules are written once, in `AccountRules.kt` (`usernameOrNull`,
  `isPassword`), with their numbers in `WyrApi.Limits` for a client to check against. A password goes
  only to `Passwords`, hashed on `Dispatchers.Default` and outside any transaction, and neither it nor
  its hash may reach a log, an answer or a `toString` (`RegisterRequest` and `LoginRequest` hide it).
  Exposed writes a failed statement's values into its message only when the transaction's `debug` is
  on: keep it off. A login's 401 `INVALID_LOGIN` is no expired token: `AuthApi.logIn` sends the login
  with Ktor's `AuthCircuitBreaker` attribute, without which the bearer plugin refreshes the session it
  holds and sends the login again, and never through `withSessionRecovery` (`AuthApiTest`,
  `DefaultAccountRepositoryTest`). Every access token names its session (`sessionId`), which only a logout
  reads; a token without one, from `d4a9dbf`, is 401 there, and a refresh replaces it.
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
