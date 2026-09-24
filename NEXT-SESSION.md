# Next session — pick up here

Read `CLAUDE.md` first (authoritative). This file is just the working handoff.

## Where we are

The vertical slice is built and running: **zero-click session → fetch questions → vote → see
the tally and points**. Server and all client targets except iOS are verified on this machine.

Repo initialized on `main` with the personal identity and `user.useConfigOnly = true` (§7).

**Deployed** (CLAUDE.md §8): **prod** `wyr-server` at https://wyr-server.onrender.com on Render
Postgres, promoted by hand with *Manual Deploy*; **dev** `wyr-server-dev` on in-memory H2, deployed
automatically from every green commit on `main` (its URL is on its Render page).

### Verified working

- `:server` on H2: 263 tests, 261 green and 2 skipped (the PostgreSQL-only boot races), including
  73 end-to-end flow tests in `ApiFlowTest`. Flat scoring is covered there (every vote pays 1,
  majority and minority alike, and the total
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
  player; two first likes racing pay once (then on the key, through Exposed's rerun; on the question's
  row lock since `feat/moderation-app`) and two unlikes
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
  `feat/moderation`, before the moderation app replaced that section: a throwaway JVM test, not committed, drove `ModerationConsoleViewModel` from the
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
  each), a retirement waiting for a vote that holds the question and counting it, and a vote,
  skip, like and unlike waiting on a retirement and then 404. `SeedTest` pins a retired seed
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
  every error, a failure kept once its submission is no longer listed. `ModerationOverHttpTest`
  runs it over the real client and a mock engine: 403, a bare 404, a 409 and a 429 with its
  `Retry-After`, and every request with the admin header and no bearer token. `AdminModuleTest`
  resolves the app from its own modules, with no platform module, and pins that nothing there can
  make or keep a player session: wired with the player's data module instead, it fails.
  `QuestionListViewModelTest` drives the list: pages read to the last one and no further, Load more
  at the filter the list was read at (ignoring it fails), a filter change dropping what was read and
  refused while a read runs, `OTHER` refused as a filter, Retire only after the dialog is confirmed
  and only for an approved question, every action reading the list again as deep as it was shown
  (one page only fails it), a decision from either screen reading the queue and a read list again,
  drafts kept for a question pending in the list alone, an empty page that claims another ending the
  read, and Lock keeping the filter alone. `ModerationOverHttpTest` adds a 409 `WRONG_STATUS` on a
  retirement. `ScreensDrawTest` draws both screens and the Retire dialog off screen (Compose's
  `ImageComposeScene`) in both themes. `DataModuleTest` pins that the game's `dataModule` binds
  nothing of the moderator's (binding the API back fails it), and `WyrHttpClientTest` and
  `RunApiOverHttpTest` a 429's `Retry-After` reaching `WyrException.retryAfter` (dropped at either
  step, they fail). Against the fat jar on JDK 21 (`PORT=18092`, no `DATABASE_URL`, a throwaway
  `ADMIN_TOKEN`), a throwaway JVM test, not committed, drove `ModerationViewModel` through the real
  CIO client, after a curl guest submitted two questions: a wrong token read `FORBIDDEN`; the queue
  listed both oldest first; approving the first under SUPERPOWERS and RANDOM worked and read the
  queue again; approving it again was `ALREADY_DECIDED`; the list at pending and approved, FOOD and
  SUPERPOWERS, held the other; rejecting it from the list with a padded reason stored it trimmed;
  every question was 20 and then, with Load more, 26; Retire asked, was cancelled and sent nothing,
  then confirmed retired the first, Restore put it back, a second Restore was 409; Lock cleared it
  all. Counts at the branch head: `:server` 263, 2 skipped; `:core:domain` 37; `:core:data` 133;
  `:core:network` 77 (83 as Android host tests); `:app:shared` 106, the console's 21 moderation
  tests gone with the section; `:app:adminApp` 79. Every client target compiles, the moderation
  app's JVM, JS and wasmJs included, as do the iOS simulator main and test, and
  `:app:androidApp:assembleDebug` builds; `WYR_SERVER_ONLY=1` still configures `:core` and
  `:server` alone.
  `DesktopEnvironmentNameTest` pins `WYR_ENV`. Its JVM, JS and wasmJs compiles run.
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
  without a build (`RATE_LIMIT_*`). A browser cannot read `Retry-After`, which CORS does not expose;
  nothing reads it yet. Counts live in one instance's memory and reset with every restart, a deploy
  or a free-tier spin-down included.
- **The refresh rotation on a live server.** The grace window has run against the fat jar on H2
  (above), never on Render, so its races are proven by tests only. The 1-point rule has been seen
  live, in the client run above.
- **V2 and V3 on PostgreSQL, and on production.** `SchemaDriftTest` and `MigrationsTest` run V2 on
  PostgreSQL only in the `server-postgres` CI job, which has not seen this branch; the script is
  H2's draft rewritten by hand, the same statements V1 used for its unique constraint. V3
  (`feat/moderation-app`: `questions.retired_at`, one nullable `BIGINT` with no default) has run on
  H2 alone, its draft from `:server:pendingMigration` without `WYR_TEST_JDBC_URL`. Production
  (`wyr-postgres`, provably V1; nothing here records a migrating build booting on it yet) runs what
  it lacks at its next Manual Deploy, baselining it in the same boot if no earlier one did: nullable
  columns and a unique constraint on tables of a few rows, so no rewrite and a moment's lock. Check
  its history reads `1 BASELINE`, `2 SQL`, `3 SQL` afterwards.
- **The settling refresh in a real browser or desktop pair.** Two tabs sharing `localStorage`, or
  two desktop instances sharing JVM preferences, have raced a refresh only in
  `SharedSessionStoreTest` on `MockEngine`. JVM preferences sync between processes on their own
  schedule, so two desktop instances may not see each other's write in time for it to matter. The
  edge `WyrHttpClient` notes remains: both clients checking the store before either writes can
  still leave the displaced token there, until a lock shared across processes exists.
- **The endless feed and vote replay on Postgres.** `RANDOM()` in the feed, the compare-and-set
  that starts a cycle, a first answer racing another (queued on the question's row lock since
  `feat/moderation-app`; before it, the 23505 aborted the transaction and Exposed reran it), and the
  `SELECT ... FOR UPDATE` that makes a retry wait and replay have only run on H2. So has the skip,
  locked and then written as a vote is, with a first skip racing another on the question's row. The store races in `QuestionStoreTest`, `VoteStoreTest` and `SkipStoreTest`
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
  browser has sent `X-Admin-Token`: only `CorsTest` has seen its preflight. Nobody has opened the
  moderation app on any platform (below). The question list (`GET /v1/admin/questions`,
  `feat/moderation-app`) has run on H2 only: its counts are correlated subqueries and its pages a
  keyset on `(submitted_at, id)` compared in the database's collation, which `ApiFlowTest` runs on
  PostgreSQL only in the `server-postgres` job, which has not seen the branch. So has retirement:
  `RetirementTest`'s races poll H2's `SESSIONS`, and on PostgreSQL a `SELECT ... FOR UPDATE` that
  waited on a retirement re-checks its `WHERE` against the committed row, and finds nothing, only as
  documented behaviour (EvalPlanQual), as for the refresh rotation. H2 does the same, which the
  races show; that a locking read there re-checks rather than returning the row it first matched was
  first seen in a standalone check against H2 2.4.240.
- **Likes on Postgres, and in any client but the JVM.** Two first likes racing, and two unlikes,
  now each queued on the question's row lock (`feat/moderation-app`), the grouped count
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
  flows. On Postgres the rotation obeys the grace window's rules under a race because an `UPDATE`
  that waited on a row lock re-checks its whole `WHERE`, both halves of its `OR` included, against
  the committed row, and evaluates its `SET` against that row. That is documented Postgres behaviour
  (EvalPlanQual), not something a test here has seen, and the same goes for the concurrent-seed
  recovery described on `Seed.questionsIfEmpty`. Porting the races means polling
  `pg_stat_activity` instead.
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
- **The UI has never been looked at.** It compiles and its ViewModel is tested, but no
  screenshot of the play screen or the reveal state has been taken on any platform. Treat the
  layout and the §5b palette in practice as unreviewed. The dev console has not been opened
  either: its actions are tested through its ViewModel, and the use cases behind them ran live,
  all but `SkipQuestion`, which has run only against `FakeServer`. `POST /v1/skips` itself has run
  only in the server's own tests. The *Submit a question* section has not been opened either: its
  ViewModel and line helpers are tested, and its use cases ran live, but it has never been drawn.
- **The moderation app has not been opened.** No window has been shown and no page served: its
  screens were drawn off screen by `ScreensDrawTest` and looked at as images once, and its
  ViewModel has run against a local fat jar from the JVM only (*Verified*, above), never against
  dev or prod, and never from a browser, so no page has sent `X-Admin-Token` across CORS. The
  webpack build of its page has not run, so neither has the check of `kotlin-js-store`'s lock
  against it. `ScreensDrawTest` draws with the host's Skia, which CI's Linux runner has yet to run.

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
the default. The console's header shows the environment and the `api` in use. `WYR_API_BASE_URL`,
which pointed the desktop client at any server, is retired (CLAUDE.md §8e): name an environment
instead (below). For the **web** client the server also needs `ALLOWED_WEB_ORIGINS` set or CORS
preflight will reject every request. The page's dev server takes the first free port from 8080, so
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
200 on 2026-09-24. The console's header shows the environment and its URL, and a `prod` build has no
console at all, only Play. Each environment keeps a guest of its own, so switching between them
loses neither.

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
socket peer or the player. The one a developer meets first is 10 guests an hour: *New guest* pressed
an eleventh time answers 429, which the console logs as `err RATE_LIMITED` with the seconds to wait.
Raise any budget for a session with its variable, and a refused request says which one in the log:

```bash
RATE_LIMIT_GUESTS_PER_HOUR=1000 ./gradlew :server:run
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

**Pending** is the queue, oldest first, read on *Load pending*: each submission's options,
categories, age and id. The chips pick the categories *Approve* files it under in place of the
author's, none keeping the author's; *Reject* stays off until the reason typed is one line of at
most 200 characters once trimmed. After every decision the queue is read again, so a decided
submission leaves it, and a line above it says what the decision did. A failure shows where it
happened: under the submission, or above the queue, named by its options, once the read after it no
longer lists it. `Wrong admin token (403)`, `Moderation is off on this server (404)` for a server
without `ADMIN_TOKEN` (or a build without that route), `Already decided (409)`, and `Too many requests
(429): try again in N s`, where ten wrong tokens in a minute lock the address out, the right token
too, until the wait is over.

**All questions** is every question, seeds included, newest first, read on *Load*, a page of 20 at
a time with *Load more*. The chips narrow it by status (*Retired* among them) and by category, any
of each, none being all; changing them drops what was read, and *Load* reads it at the new filter.
Each question shows its options, categories, status, seed or player's, votes and likes, when it was
stored, reviewed and retired, and a rejection's reason. An approved one has *Retire...*, which asks
first in a dialog; a retired one has *Restore*; a pending one has the queue's chips, reason and
buttons, sharing what was picked and typed with the queue. After every action the list is read
again as deep as it was shown, so the question shows where it now stands without losing your place.
A question another moderator moved first says `Its status changed first (409)`.

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
  "not read" or as the new guest's. A submission stays `PENDING` until it is decided in the
  moderation app (*The moderation app*, above), and *Refresh my submissions* then shows the
  decision.
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
3a. `fix/read-committed` *(done)* — transactions at READ COMMITTED; refresh-token rotation is a
   compare-and-set.
4. `feat/dev-console` *(done)* — the engineering UI, as the default root.
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
12. `feat/moderation-app` *(in progress)* — moderation moves out of the player app's console into
    an app of its own. Built: the server and client half, the list of every question and retiring
    and restoring (V3; provisional, CLAUDE.md §8b), and the app, `:app:adminApp`, with its pending
    queue and the list of every question (*The moderation app*, above), which replaced the dev
    console's *Moderation* section. Left: CI on the branch, then merging it.

**For the moderation app.** Everything it needs is in `io.ntole.wyr.core.domain.moderation`, and
none of it needs or makes a player session:

- *Wiring:* `moderationDataModule(environment)` (`:core:data`) binds the moderation repository and
  the use cases over an HTTP client of their own, with no `TokenStorage`, so a platform entry point
  needs only the `WyrEnvironment` it targets (CLAUDE.md §8e). Without Koin it is
  `DefaultModerationRepository(ModerationApi(WyrHttpClient.create(environment.apiBaseUrl,
  SessionStore(InMemoryTokenStorage(), environment))))`.
- *The token:* `AdminToken.of(typed)` is null for what no header could carry; hold it in memory only,
  as the console does, and hand it to every call.
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
  moderation app*, above). The dev console's *Moderation* section is gone, and the game's
  `dataModule` binds nothing of the moderator's: the game no longer moderates.

**Remote:** `github.com/niktok1/would-you-rather` (private), `origin`, pushed over SSH through the
`github-wyr` host alias with a deploy key scoped to this repo (CLAUDE.md §7). `gh` is logged in to
the personal account for reading CI. Render is set up from the blueprint (`wyr` on the personal
account); its `ADMIN_TOKEN`s are set by hand in each service's Environment tab.

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
  migrating build that boots on it, and runs V2 in that boot or a later one. Before a migrating
  build first boots on any other database it did not build, compare schemas read-only
  (`pg_dump --schema-only`, CLAUDE.md §8b), since the baseline checks only that V1's tables exist.
  Never let `WYR_TEST_JDBC_URL` name the production database: the test suite and `pendingMigration`
  wipe the database it names. V2 moved `MigrationsTest`'s pre-migration database onto V1 (V1 run
  alone, the history dropped) and made it read rows with `SELECT *`, since `Tables.kt` now names
  columns V1 lacks; a new script adds its row to `BASELINED_HISTORY` and what it does to existing
  rows to `afterLaterScripts` (`ADDED_COLUMNS` for a column added empty). That database's rows are
  written through the stores, so a `selectAll` on a table a later script changed fails there: V3 made
  the seed check only the id. The H2 draft omits `COLUMN` and upper-cases everything; write it in
  lower case, one `ALTER TABLE` per column, since H2 takes no list of `ADD`s. Two boots at once
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
  the stats' due count, and votes, skips and likes (`QuestionStore.lockIfServable`) all read it, so
  a pending, rejected or retired question is served to nobody, due for nobody, and answering,
  skipping, liking or unliking it is 404. A new exclusion belongs there. Since a question can now
  stop being servable, `lockIfServable` takes the question's row `FOR UPDATE`: a retirement waits
  for the votes, skips and likes that found it servable, and one behind a retirement finds it
  retired. So every vote, skip and like of one question queues on its row, and two first ones by a
  player never race on their key any more; `VoteStoreTest`, `SkipStoreTest` and `LikeStoreTest`
  race them on the row lock now, and `VoteStoreTest`'s split-tally race moves the other vote
  straight in the table, since no answer can come between. Retirement is a column,
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
  displaced is the previous one in its place. Keep `PlayerStore.rotateRefreshToken` one `UPDATE`
  whose `WHERE` holds the whole check and whose `SET` copies the current columns into the previous
  ones in SQL: a read first, or an update by id, lets two racers with the previous token both
  through (`PlayerStoreTest` races it). Keep stamping the rotation with no bound too: a bound set
  later reads it, and so does a rollback to a build from before the unbounded grace. A rollback to
  a build from before V2, such as prod's `4cdc819`, rotates without the previous-token columns and
  leaves them stale: clear them before rolling forward (the `UPDATE` is in CLAUDE.md §8a, *The
  rotation*). A bound, if set, must stay longer than the client's `REFRESH_TIMEOUT` (5 minutes), and
  nothing but the KDocs on each side ties them, since `:server` cannot see `:core:network`. On the
  client, a refresh that finds the store moved on to the same player's other session refreshes once
  more as it (`refreshAs`), since both tabs' refreshes go through and a previous token survives one
  refresh only. `REFRESH_GRACE_SECONDS` that is not a whole number from 0 to a year fails the boot,
  naming it. The cost, accepted for guests: a used copy of a refresh token keeps working beside the
  original while the two take turns refreshing. What remains, in CLAUDE.md §8b (*Refresh answers
  lost past the grace*): a refresh that spends the previous token and whose answer is lost too, as a
  settling refresh's lost answer usually does, leaves a spent token.
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
