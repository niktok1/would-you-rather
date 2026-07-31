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

- **iOS.** This machine has Command Line Tools but no Xcode, so `iosArm64` /
  `iosSimulatorArm64` were never compiled and the Xcode project was never opened. The iOS
  source (`IosTokenStorage`, `PlatformModule.ios.kt`, `MainViewController`) is written but
  unproven. **Build this first on a machine with Xcode.**
- **`Dockerfile` and `render.yaml`.** Docker is not installed here, so the image has never been
  built and nothing has been deployed. The `WYR_SERVER_ONLY` switch it depends on works (it is
  just a `settings.gradle.kts` conditional), but the build itself is unproven.
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

## Next steps (in rough priority order)

1. **Build iOS on a machine with Xcode.** Highest-risk unverified area.
2. **Look at the UI** on desktop and Android; refine the §5b palette against reality and run the
   WCAG AA contrast check that §5b flags as outstanding.
3. **Provider linking** (`POST /v1/auth/link`) — the seam exists and is documented in §8a. Until
   it lands, losing the device loses the account. This is the biggest functional gap.
4. **Verify the Docker build and deploy to Render**, then set `ALLOWED_WEB_ORIGINS` and point the
   clients at the deployed URL instead of localhost.
5. **SQLDelight cache** with the per-platform split described in §4, so the question queue
   survives a restart on Android/iOS/desktop.
6. **Design the submit-question screen**, then extend the contract with author + `QuestionStatus`.
7. **Design the stats/profile screen**, then add the player-stats DTO. The server already tracks
   `totalPoints` and `streak` per player, so the data exists.

## Things worth knowing before you touch the code

- **The wire enum rule (§5) is load-bearing and easy to break silently.** It only works because
  `WyrJson` sets `coerceInputValues = true` *and* `ServerJson` sets `encodeDefaults = true`.
  Remove either and the `UNKNOWN` defaults become decorative — with no test failure, because the
  breakage only shows up on a client build older than a server enum addition.
- **Exposed 1.x renamed everything.** Packages are `org.jetbrains.exposed.v1.*`, and
  `SqlExpressionBuilder.eq` is deprecated *as an error* — import the top-level `eq` instead.
  Expect to hit this again the first time you write a new query.
- **The tie rule is duplicated in two places on purpose** and must stay in sync: `Scoring.award`
  on the server and `VoteOutcome.agreedWithMajority` on the client both treat an exact tie as
  agreement. There is a test on each side asserting it.
- `Tally.percentB` is defined as `100 - percentA` rather than rounded independently, so the two
  always sum to 100. There is a property test over every split up to 40/40.
- `:server` must not depend on `:core:domain` (§3). That is why scoring lives in `:server`.
- Only `:core` and `:core:domain` enforce `explicitApi()`, so new public declarations there need
  an explicit `public`.
