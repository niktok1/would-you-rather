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
the server and the contract did not. How to try it on a phone is under *Submit from My questions*,
rewritten since for the form under Account.

**On `chore/remove-console`** (from 61bfcad; on `main` and `origin/main` since): the dev console is
gone (the user, 2026-09-25: "console is not needed"). Every build, LOCAL, DEV and PROD alike, shows
Play, Submit and Account and opens on Play (CLAUDE.md §8d, *Current focus*); a LOCAL or DEV build
names its server on the Account tab's last line (§8e). Changes are tried through the game and the
moderation app (*Trying a change*, below). What only the console read went with it: the HTTP trace
(`HttpTrace`, `HttpTracing`); `SessionDiagnostics`, the session and token expiry its header showed;
the session port's `currentPlayerId` and `clear`, which only it called through the port (a logout
clears the session through `DefaultSessionRepository` itself); `Question.answeredBefore`, which only
it showed; and `PlayerStats.playerId` and `VoteOutcome.questionId`, which only it read (the wire's
`QuestionDto`, `PlayerStatsDto` and `VoteResultDto` still carry all three). The client alone
changed; the server only in a comment and a test's name.

**On `feat/app-foundation`** (from 40550e9; merged into main as 4821c05): the foundation of the
user's redesign (2026-09-25), which the Play, Account and category screens' redesigns build on. **No
tabs**: the app opens on **Home**, the game's name, a big Play and the account icon; Play and
Account each show under a top bar of icon buttons, and Submit is reached from Account's bar for now,
over a back stack made by hand that Android's back pops (CLAUDE.md §8d, *Navigation*). The theme
draws its own icons, home, account, back and the two hearts (§5b). **Three languages**, picked on
the Account screen and kept on the device: Serbian Cyrillic, the default whatever the device's
language, Serbian Latin made from it by `SerbianScript.toLatin` (`:core:domain`), and English
(§8f). The switch in the Account heading's place is provisional (§8d, *The Account screen*): ask the
user. Only Home, the top bars and the switch are translated; the Play, Account and Submit screens'
own copy stays English for the branches that redesign them. The client alone changed. Tests:
`:core:domain` 70 (`SerbianScriptTest` 16 new), `:app:shared` 171 (`NavigatorTest`,
`AppNavigationTest`, `HomeScreenDrawTest`, `TopBarsDrawTest`, `WyrIconsDrawTest`, the language
tests; `RootScreensTest` went with the tabs). Not seen on a device: Android's back, and any screen of
it on a phone; nor whether the web build's default font draws Cyrillic.

**On `merge/redesign`, `feat/category-picker` merged in** (9c4ecd4, one `--no-ff` merge, then
0b50d1f, cfdf8af, 008d0cc, 0daeb7a, 7353691 and this note; nothing pushed): **everything is merged
here** — `feat/play-redesign`, `feat/account-redesign`, `main` at 60b0d7f (with
`chore/remove-console` and `feat/server-categories` and its clients) and now `feat/category-picker`
— and the branch is **ready for `main`**, which is its ancestor, so it goes there as a fast-forward
or a `--no-ff` merge. The Play row's categories open the **Categories screen** (`Screen.Categories`,
under the one `BackTopBar`, CLAUDE.md §8d *Navigation*, *The Categories screen*), and the picker
dialog is gone with `CategoryPicking`, `PlayViewModel`'s picker methods and `GetCategories`,
`PlayScreen`'s picker parameters, `PlayStrings`' three picker texts and their tests; the one case
only the picker covered, every category ticked played as all of them and not as none, is
`CategoriesViewModelTest`'s now. A selection played there drops what Play shows, a question asked,
one revealed (in its first half second too), or a failure, out of questions or a vote lost to
`NETWORK`, and **loads** a question from it (`load`: `next` goes on only from the reveal here);
while a load, a vote, a skip or a like is in flight it changes nothing more, and the question after
it is the new selection's (`canChangeCategories`; `PlayViewModelTest` plays one from each state, and
each case fails with `next` in the observer or with its guard taken out). **One way to say each
thing**: a category is named through `categoryName(category, language)` alone, the Categories screen
reading `LocalLanguage` as the other screens do (`Category.nameIn` and `CategoryNameTest` gone,
their cases in `CategoryNamesTest`); its Try again, Play and failed read are the game's, *Покушај
поново*, *Играј*, and *Игра није доступна.* offline or *Категорије нису учитане.* otherwise, as the
dialog said them (`unreadText`; `CategoryStrings` lost `tryAgain`, `cannotLoad` and `play`); since
the review (6b44dc4, 5a8adf7, 48e3e0e), *Све* and the spinner's name are one text each too
(`Strings.allCategories`, `Strings.loading`), and *Изабрано: {0}* beside Играј is a template; the
one cancel is `Strings.cancel`, on the Auth page alone now. **The search folds accents** (*decided*,
no longer provisional: the players' phones may lack a Serbian keyboard): č and ć are c, š s, ž z and
đ dj, on both sides and in the English name too, so *nacin* finds *Начин живота* and *djak* finds
*Ђак*. The client alone changed. Verified here (*Verified working*): lint, the verify job's tests
and client compiles, and the iOS Kotlin compiles; the `server-postgres`, `docker-smoke` and `ios`
jobs run only in CI. Tests: `:app:shared` 309 (267 before the merge: the merge brought 60, the
dialog's removal took 24, the observer's states 5 more, the naming 3 fewer, the accents 4 more). To
ask the user: how many are ticked beside Play, rather than their names; a query finds a category by
either name whatever the language shown (*food* finds *Храна* in Cyrillic); *Све* in bold; and the
Categories screen saying offline as Play does, *Игра није доступна.*, where the Submit form says
*Нема интернет везе.*. The provisional items below still stand (CLAUDE.md §8b: the Play row's
arrangement, one Try again, what submitting cost; §8d: the reveal's half second). Not seen on a
device: any of it on a phone, the keyboard over the list, and Android's back from the Categories
screen.

**On `merge/redesign`, `main` merged in** (60b0d7f, `feat/server-categories` with its clients; one
`--no-ff` merge, nothing pushed): the redesign's screens on main's server categories and cost.
Categories are main's data everywhere, ids and the server's names, with the redesign's UI: the row's
categories, and the picker **dialog** as main has it (it reads the list on opening, a line while it
loads with none, one if the read fails), its words translated (`PlayStrings.pickerTitle`,
`pickerAll`, `pickerLoading`; its buttons are `Strings.cancel`, lifted out of `AccountStrings` for
the Auth page and the picker alike, and `Strings.play`), for the parallel branch that replaces it
with a screen to build on. **Category names by language**, through one function,
`categoryName(category, language)` in `io.ntole.wyr.language` (it was `io.ntole.wyr.play`): `nameSr`
in Cyrillic, `SerbianScript.toLatin(nameSr)` in Latinica, `nameEn` in English, the language read
from `LocalLanguage`, which `WyrStrings` now provides beside `LocalStrings`. **The cost** has one
copy, main's `SubmissionRules.SUBMISSION_COST`, pinned to `WyrApi.Limits.SUBMISSION_COST` by
`SubmissionLimitsTest`; the redesign's `COST` is gone, overruling the Account paragraph's merge note
below (the task asked for main's), and so are main's `POINTS_NOTE`, `SENT_NOTE`, the form's list of
submissions (My questions on Account has it) and the English refusal for points. Send shows the cost
in `Strings.pointsUnit`, *Пошаљи · 1 П*, off while the points are fewer; the server's
`NOT_ENOUGH_POINTS` is the same short line, *Немаш довољно поена.*, said once. The Submit form reads
the categories and then the points each time it is shown; a failed read of the categories says
*Категорије нису учитане.* (`Strings.categoriesUnread`, the picker's too), or offline, under the
chips with Try again, unless the points failed too. **`pointsSpent`** is not shown: the Account card
keeps its four counts, and two of them, the answers and the likes, are terms of CLAUDE.md §8c's
sum, so once a question has cost a point they add up to more than the points shown. That gap is
accepted for less text, and the cost shows on Send (CLAUDE.md §8b, *What submitting cost, on the
Account screen*: ask the user; the other option is a fifth number). Every string translated; no
English literal left from main's side in `:app:shared`. The moderation app stays English (§8f).
Verified here at the merge (*Verified working*): lint, the verify job's tests and client compiles,
and the iOS Kotlin compiles. Tests: `:app:shared` 267 (250 on the redesign, 128 on main), `:server`
346 (2 skipped), `:core:domain` 72, `:core:data` 142, `:core:network` 72 and 78 Android host,
`:app:adminApp` 106. Not seen on a device.

**On `merge/redesign`** (from 4821c05; not on `main`, nothing pushed): `feat/play-redesign`, then
`feat/account-redesign`, each merged whole with `--no-ff`; the two paragraphs below say what each
brought. Then, at the user's asking, **Skip moved from the top bar into the row between the cards**,
after the like count, only while a question is asked and off, drawn muted as on the top bar, while
anything is in flight, its place kept in the reveal so nothing in the row moves (CLAUDE.md §8d, *The
Play screen*); the top bar's centre slot went with it. `CentredRow` keeps the points in the middle
of the screen while the categories played leave them room and moves them right only as far as a
longer selection needs, so *Начин живота* shows whole at 375 wide on this Mac, where a row keeping
the points in the middle would leave it 115 of its 125 (*provisional*, §8b: ask the user; the other
option is the points always in the middle). And **one points unit**, a text of `Strings`
(`pointsUnit`, written by `Strings.points`): *П* in Serbian, *P* in Serbian Latin by the
transliteration and in English, on the Play row, the Account card, the Auth page's guest-points
warning and Send's cost, *Пошаљи · 1 П* (§8f, *Numbers and symbols*); the Account branch's constant
Latin *P* (`POINTS_SYMBOL`, `pointsText`), and its *123 P* below, are gone. And **one Try again**,
`Strings.tryAgain`, *Покушај поново* under a failure on every screen, where Play said *Пробај опет*
(*provisional*, §8b: ask the user; offline is still said two ways, *Игра није доступна.* on Play,
which a server that is down fits too, and *Нема интернет везе.* on the Account screens). The client
alone changed. Verified here: ktlint, the verify job's tests and client compiles, and the iOS Kotlin
compiles; `:server:test` came from the cache, the server untouched. Tests: `:app:shared` 250
(`PlayScreenDrawTest` 22, `AppNavigationTest` 16, `TopBarsDrawTest` 4, `StringsTest` 8),
`:core:domain` 70, `:core:data` 139, `:core:network` 73 and 79 Android host, `:app:adminApp` 87,
`:server` 311 (2 skipped). For `feat/server-categories`: `PlayScreen.kt` conflicts again, now with
`CentredRow`'s `MiddleRow`, which takes the categories played as text, so its
`categoriesPlayed(PlayedCategories)` keeps the `all` it is given; `sendText` takes the whole
`Strings`, and the rest of the Account paragraph's merge notes hold. For `feat/category-picker`: its
`CategoryStrings.tryAgain` goes, for `Strings.tryAgain` (done at its merge, above). Not seen on a
device.

**On `feat/play-redesign`** (from 4821c05; merged into `merge/redesign`, nothing pushed): the user's
**Play screen** redesign (CLAUDE.md §8d, *The Play screen*). Two cards and one row between them: the
categories played (*Све*, a chevron, the picker dialog as before), the points (*123 П*, read each
time the screen is shown and moved by each vote's answer, `PlayViewModel.points`) and the heart with
the like count. A card answers; in the reveal the percentages count up over 2.5 s and either card
goes on (`PlayViewModel.next`, now from the reveal only). The title, *OR*, Like and Next question
buttons, vote counts, *+1* and the verdict are gone; Skip is an icon in the middle of the top bar
while a question is asked (in the row since, above). The screen's words are translated
(`PlayStrings`); the picker and the category names stay English, and `CategoryPicker`,
`CategoryOption` and `categoryName` are byte for byte untouched, for `feat/server-categories` (only
`categoriesPlayed` takes the *All* text now). The client alone changed. Tests: `:app:shared` 192
(171 before). Ask the user: the vote counts gone with the verdict, the title and *OR* gone, a failed
like in the points' place, no re-read of the points after a like (a like of one's own question shows
from the next vote or visit), and the half second after a reveal lands in which a card does not go
on, so a double tap cannot skip the reveal (`REVEAL_HOLD_MILLIS`; or no hold). The count-up plays
again when Play is shown again on a revealed question. Not seen on a device.

**On `feat/account-redesign`** (from 4821c05; merged into `merge/redesign`, nothing pushed): the
user's Account redesign (2026-09-25; CLAUDE.md §8d, *The Account screen*, *Submitting*; §8f). The
Account screen, top down: a card of the player (the username or *Гост*, the points as *123 P*, four
stats as numbers), **My questions** (the player's submissions, each option and its status in a word,
and *Ново питање*), the language switch, Log out, and the server line, all in the three languages. A
guest's one button opens the **Auth** page: Register only, with a link that switches it to Log in
and back; the rules, the short failures and the guest-points warning kept, and back to Account once
one works, or once the read after one whose answer was lost names the account. Shown before any
player is read, a read that failed says so on top with Try again, and Log in waits for one. The
Submit screen is only the form now, opened from My questions and back there once a question is
stored (one stored after the player went back is read again if Account is shown then); Send shows
the cost, *Пошаљи · 1 P* (`SubmissionRules.COST`, 1 until release), and is off with *Немаш довољно
поена.* while the points are fewer. The client alone changed: the server here charges nothing.
*Accounts* below still walks the flows as they were before it (*Submit from My questions* is
rewritten for the merge with `main`). For the
merge: `SubmitScreen.kt` and `SubmitScreenTest.kt` conflict with `feat/server-categories`' 4ceb426,
which adds `SUBMISSION_COST = 1`, a cost `POINTS_NOTE` and an English `NOT_ENOUGH_POINTS` line; keep
`SubmissionRules.COST` as the only copy, drop `SUBMISSION_COST` and `POINTS_NOTE` (this branch has
no note), map `NOT_ENOUGH_POINTS` to an `AccountStrings` text in all three languages (until then the
form shows it as *Нешто није у реду*), and drop §8d *Submitting*'s "the server here charges
nothing". The chips still call `categoryName`; `Strings.kt` and CLAUDE.md §8f will conflict with
`feat/play-redesign`, both adding texts. To ask the user: the Auth page has no heading (read
literally, it "shows only Register"), and the form's line that submitting earns no points is gone.
Tests: `:app:shared` 225 (`AuthScreenDrawTest` 9, `TemplatesTest` 4 new; `AccountScreenDrawTest` 14,
`AccountViewModelTest` 33, `SubmitViewModelTest` 25, `SubmitScreenDrawTest` 6, `AppNavigationTest`
14). Android's back is covered only by `NavigatorTest`: on the JVM `SystemBack` binds nothing. Not
seen on a device: any of it on a phone, autofill on the Auth page and Android's back included.

**On `feat/server-categories`** (from 40550e9, on `main` and `origin/main` at 60b0d7f; the server
and the contract first, then the clients): categories are **server data** (CLAUDE.md §8d,
*Categories*, decided 2026-09-25). V6 adds `categories` (id, Serbian and English names, when added),
writes the first five, `FOOD`, `LIFESTYLE`, `ETHICS`, `SUPERPOWERS` and `ABSURD`, moves everything
filed under RANDOM to ABSURD and holds `question_categories` to it with a foreign key. RANDOM is no
category any more (§8b: *All* is no filter). Every categories field on the wire is plain ids,
`QuestionCategory` and its list serializer are gone (§5), and the JSON for the first ids is what it
was. `GET /v1/categories` lists every category, with its id and both names, oldest first, to anybody
(no bearer), limited per address. The moderator adds a category (`POST /v1/admin/categories`, the id
given or derived from the English name, 409 `CATEGORY_EXISTS`) and renames one (`POST
/v1/admin/category-renames`, 404 `CATEGORY_NOT_FOUND`); no delete. Submitting costs
`Scoring.SUBMISSION_COST`, 1 point until release (CLAUDE.md §8c): too few is 409
`NOT_ENOUGH_POINTS`, a rejection pays back what the question cost (V7 keeps it on the question,
`submission_cost`), and `GET /v1/me` reports `pointsSpent`, so the total is what the answers and
likes earned less that. An author's own like pays them, so liking their approved question gives
its cost back: kept (CLAUDE.md §8c, decided 2026-09-25). Every seed comes with made-up votes (V8, `questions.base_votes_a`/`_b`, CLAUDE.md §8d
*Seeds*), which every tally the server reports adds to the players' own, and the seeds are in
Serbian Cyrillic (V9 rewrote production's English ones by id; a new database is seeded in Serbian).

The clients on the same branch (four commits after the server's): categories are **server data on
the client too** (CLAUDE.md §8d, *Categories*, *The client*). `GET /v1/categories` is read through
`CategoryApi` behind `CategoryRepository` and `GetCategories` (`runApi` alone: no session ensured,
recovered or minted), kept in memory and read again when asked. The domain's `Category` enum, its
`OTHER` and the interim ABSURD-as-RANDOM mapping are gone: a question, a submission, a moderated
question, a selection and a filter hold category ids, sent in id order, and a screen names each by
the list last read, in Serbian for now (`categoryName` in `io.ntole.wyr.play`, one place for the
translations branch to choose the language), one not read yet by its id. The **Play** screen's
picker reads the list each time it opens; the **Submit** screen's chips are the list, read each time
the tab is shown; `NOT_ENOUGH_POINTS` is a `DomainError` of its own, and the Submit screen says that a
question costs 1 point, paid back if it is rejected (the 1 is `SubmissionRules.SUBMISSION_COST`,
pinned by `SubmissionLimitsTest` to `WyrApi.Limits.SUBMISSION_COST`, which `Scoring` charges). The
**moderation app** reads the categories before every Load, for its chips, and has a third tab,
**Categories**, which lists them and adds one (id typed or left to the
server) and puts one's names right (`AddCategory`, `RenameCategory`, `CategoryRules`,
`CATEGORY_EXISTS` and `CATEGORY_NOT_FOUND` as `DomainError`s). No client reads `pointsSpent` yet:
the Account screen's lines of answers and likes no longer add up to its points once a question is
pending or approved, which the Account redesign can show. `App.kt` is untouched: the Play screen's
two category values changed type under the same names (`PlayedCategories`, `CategoryPicking`).

**On `feat/category-picker`** (from 60b0d7f; merged into `merge/redesign`, above, nothing pushed):
the category picker is a **screen of its own** (the user, 2026-09-25: hundreds of categories,
several picked, *All* being every one, and a search; CLAUDE.md §8d, *Categories*, *The Categories
screen*; §8f), in `io.ntole.wyr.categories`. `Screen.Categories` opens from the Play screen's
categories under a back arrow (`BackTopBar`): a search field that finds a category by any part of
either name, in either script and any case (`searchKey`, both sides through `SerbianScript.toLatin`,
lower-cased); *Све* and every category in a `LazyColumn`, as many ticked as wanted, *Све* being none
ticked; and at the bottom *Изабрано: N* and **Играј**, which sets them in one `setCategories`, waits
for it, and goes back. Back plays nothing, and each visit starts from what is played
(`CategoriesViewModel.open`, called by Play's tap, so a rotation keeps the ticks). The list is read
as the screen is shown; a failed read says so with *Пробај опет*. The Play screen plays the
selection whoever sets it (`PlayViewModel`'s `init`), and `Category.nameIn(language)` names a
category in the language shown. The client alone changed. Commits: baf042d (`nameIn`), d1605eb (Play
follows the selection), 7df4d5f (the screen).

Each of its notes for the merge with `merge/redesign` is done there (above): the dialog deleted and
CLAUDE.md's *The Play screen* and *Navigation* brought up to date, the Play row wired to the screen,
the observer calling `load()`, one `BackTopBar`, and `nameIn` folded into `categoryName` rather than
the other way round, since `categoryName` already named a category not read yet by its id.

To ask the user, at the branch: the search counts accents (*nacin* does not find *Начин живота*;
folded since, at the merge), or fold č, ć, š, ž and đ; how many are ticked beside Play, rather than
their names; a query finds a category by either name whatever the language shown (*food* finds
*Храна* in Cyrillic); and *Све* in bold. Tests: `:app:shared` 240 (180 before; new
`CategoriesViewModelTest` 37 and `CategoriesScreenDrawTest` 12 in `io.ntole.wyr.categories`, not the
moderation app's of the same name, `CategoryNameTest` 4; `PlayViewModelTest` 4, `AppNavigationTest`
2 and `NavigatorTest` 1 more; `AppModuleTest` and `TopBarsDrawTest` check the new ViewModel and
bar). Not seen on a device: any of it on a phone, the keyboard over the list, and Android's back
from it.

**On `perf/play-animations`** (from f3bdce1; nothing pushed or merged): the reveal's count up is
**drawn, not composed** (the user, 2026-09-25: the dev build lagged, and the animations were why;
CLAUDE.md §8d, *The Play screen*). The count up used to read its animated value in composition, so
every frame for 2.5 seconds it recomposed both cards, laid their percentages out again as they
widened, and changed the semantics. The phone runs an accessibility service (Microsoft Launcher's),
which Compose then tells of every change. Now `CountedUpText` reads the number only where it is drawn,
over the final percentage laid out once. Nothing is composed or laid out per frame, and a screen
reader reads the final share. The look and timing are unchanged: the end is pixel-identical to the old
`Text`. The client alone changed. Tests: `:app:shared` 315 (309 before: `PlayScreenDrawTest` 2 more,
its count-up test now read by pixels, and `CountedUpTextDrawTest` 4 new). The new
`Recompositions` counts what a frame composes through the runtime's own `CompositionObserver`, from
once the first composition is applied, and fails if the composition cannot be observed. A test that
finds the count unchanged ends by recomposing a scope of its own and seeing it counted
(`assertCounting`), since an observer that never attached would count nothing too. The
pixel test steps the clock a frame at a time, as a phone does: jumping from 0 to halfway failed now
and then, when the desktop's snapshot manager, on Swing's thread, told the scene of the jump a
drawing late. **`devRelease` is how fast the game really is**
(`./gradlew :app:androidApp:assembleDevRelease`, not debuggable, and signed with the debug key since
f3bdce1, so it installs over `devDebug` and back with `adb install -r`, keeping the data). A debug
build's Compose is several times slower, so `devDebug` is not a measure of speed. It **should** now be
smooth enough to work in, since a frame of the count up composes, lays out and tells accessibility
nothing, but that is expected, not measured: no build with the change has been on the phone yet. To
compare two builds on the phone: `adb shell dumpsys gfxinfo io.ntole.wyr.dev reset`, play about ten
reveals, then `adb shell dumpsys gfxinfo io.ntole.wyr.dev`, and read *Janky frames*, the 50th and 90th
percentiles and *Number Slow UI thread*; record `devDebug`'s here once they exist. Seen there before
the change, in `devRelease`, and not the count up's doing: the first frames after a launch
take up to 750 ms on the UI thread (code not yet compiled, with no baseline profile), and the
RenderThread sometimes waits 50 to 300 ms on the display's buffers.

**`feat/analytics`** (from e603694; merged to main 2026-09-26, after `feat/server-engagement`): product analytics on PostHog, from
shared code on all four platforms (CLAUDE.md §8g): a port in the domain, a PostHog sender over its
HTTP API with the Ktor client (no SDK, no new library), the key per platform (none is off, as in
every test and CI build), the app's openings and every screen with its time, every tap by a stable
element name, the game's events from the ViewModels, identify on register and login and reset on
logout, and a **Статистика** switch on Account. The switch took Log out's place beside the language
menu, and Log out stands under that row now: *provisional*, CLAUDE.md §8b. Verified on this Mac: lint,
the verify job's client tests (`:core:domain` 76, `:core:data` 145, `:core:network` 106 and 112 as
Android host tests, `:app:shared` 383, `:app:adminApp` 106) and compiles, `assembleDebug`, the web
and desktop apps' compiles, and the ios job's Kotlin compiles. **Not verified:** nothing has been sent
to PostHog (no key here), nothing has run on a device, and the iOS Info.plist keys wait for CI's
`xcodebuild`. To see events: *Analytics*, under *Running it locally*. The dashboards to build are
listed in CLAUDE.md §8g, *Setting up PostHog*.

**`feat/server-safety`** (from e603694; merged to main 2026-09-26, 6e6fa23): the server side of Google Play readiness (the user's decisions,
2026-09-26). The clients adopt it on later branches; this one adds only the `:core` contract and the
smallest client compile fixes.
- **Request bodies** are capped at 64 KiB (CLAUDE.md §8b, *Request bodies*): 413 on a
  `Content-Length` over it, before the route runs, and a chunked body stopped one byte past it.
- **Guest minting** defaults to 60 an hour per address, was 10 (§8b, *Rate limiting*): a carrier's
  shared address or a school's Wi-Fi stands for many players. `RATE_LIMIT_GUESTS_PER_HOUR` still
  overrides it.
- **A minimum build** per platform (§8b, *Minimum client version*): `MIN_CLIENT_VERSION_ANDROID` and
  the rest make an older build's request 426 `UPGRADE_REQUIRED`, first thing, but for `/health`. No
  client sends `X-Client-Platform` and `X-Client-Version` yet, so nothing is refused until one does;
  the client branch that sends them gives `UPGRADE_REQUIRED` a `DomainError` of its own (it reads as
  `UNKNOWN` for now) and a screen that says to update.
- **Reports and hiding** (§8d, *Reports*; V11): `POST /v1/reports` with a reason, one per player and
  question, and `POST /v1/hidden-questions` and `/v1/hidden-authors`, each hiding from the player for
  good; the feed and the due count leave hidden questions out. To ask the user: hiding a seed's author
  hides that seed (provisional, the options were 409 or nothing), and nothing unhides yet.
- **The moderator's side** (§8d, *Moderation*, *Reports* and *Authors*; V12): `GET /v1/admin/reports`,
  most reported first with the reasons counted, and `POST /v1/admin/report-dismissals`; an opaque
  `authorId` on `AdminQuestionDto` and on the admin routes' `SubmissionDto`s (never a player's); and
  `POST /v1/admin/author-blocks` and `/author-unblocks`, a block rejecting whatever the author has
  pending, paid back, and refusing their submissions with 403 `SUBMISSIONS_BLOCKED`. The moderation
  app shows none of it yet. To ask the user: nothing retires a reported question by itself
  (provisional; a threshold was the other option).
- **An operator's trail** (§8b, *Logging*): one INFO line per stored submission and per admin action,
  ids only, never what anyone typed, nor a token or password.
- **Deleting an account** (§8a, *Deleting an account*; V13, V14): `POST /v1/me/deletion`, 204,
  deletes the player and all that is theirs, keeps their approved questions with nobody as author, and
  takes each like they held back from its author. The client then plays on as a fresh guest. A
  request of theirs racing it from another device is 401, never a 500 (§4).

Verified on this machine: `ktlintCheck`, `:server:test` (407 tests, H2 only), every `:core` module's
`jvmTest`, `:core:network:testAndroidHostTest`, `:app:shared:jvmTest`, `:app:adminApp:jvmTest`, the
verify job's client compiles, every `:core` module's and `:app:shared`'s iOS compiles, and the fat
jar booted on port 18110 on H2 (health, a 426 for an old build, a report, a hide, the reports list,
a 413, a deletion, and the feed, a vote, a skip and a registration after it, each 401). **Not
verified**: V11 to V14 and the new SQL on PostgreSQL (the `server-postgres` CI job runs them: the
feed's two `NOT EXISTS`, the reports list ordered by subqueries, the deletion's `INSERT ... SELECT`),
and no race test runs on PostgreSQL (they are H2's, as all are): a re-answer, re-skip or registration
waiting on the deletion is staged on H2, which leaves a lock read on a deleted row as PostgreSQL
does, but a hide of the author racing it is not, since H2's foreign key check waits on no lock, so
only its rerun is pinned. Migrations V11 to V14 are this branch's; `feat/server-engagement` starts at
V15.

**`feat/server-engagement`** (from e603694; merged to main 2026-09-26, after `feat/server-safety`): the
server side of four of the user's asks of 2026-09-26, the contract beside it, and the clients only
where the contract made them (the two new error codes in `ErrorMapper`). **The clients adopt all of
it later.** One commit each:
- **Home picks** (601aa65, V15; CLAUDE.md §8d *Home picks*): two counts for the Home screen's two
  Play buttons, `GET /v1/home-picks` with no session and `POST` with one, every tap counted.
- **Answer time** (4e4f95a, V16; §8b *Personalization*): `VoteRequest.answerMillis`, kept on the
  vote, 0 to 10 minutes or none. The personalization design is recorded there, not built.
- **Push on decisions** (8442abc, V17; §8a *Push tokens*, §8b *Push notifications*):
  `POST /v1/me/push-tokens` and `/push-token-removals`, and an approval or rejection pushed to the
  author's devices through FCM HTTP v1 once it has committed. A logout drops its device's tokens by
  itself (the foreign keys cascade). `io.ktor:ktor-client-cio` is the server's one new dependency.
- **Play Games sign-in** (8660e01, V18; §8a *Play Games sign-in*, §8b *Play Games sign-in*):
  `POST /v1/auth/play-games`, the no-click account; a linked player is registered, so may submit,
  and `GET /v1/me` says `playGamesLinked`. The register screen stays as the fallback, and the only way
  on iOS and the web for now.

**Both features are off until the user sets them up**, which is listed plainly in CLAUDE.md §8b
(*Push notifications*, *Play Games sign-in*): a Firebase project and its service account key for
`FCM_SERVICE_ACCOUNT_JSON`, and a Play Console game with Play Games Services and a game server OAuth
client for `PLAY_GAMES_CLIENT_ID` and `PLAY_GAMES_CLIENT_SECRET`, all set by hand on Render
(`render.yaml` declares them `sync: false`). Unset, each says so once at boot. Merged after `feat/server-safety`: an account's deletion takes
the player's push tokens and Play Games links by their cascades. Tests: `:server`
431 (365 before), `:core:data` 143 (`ErrorMapperTest`'s two new rows); the rest unchanged.

**A review's fixes** (611c129 to a371b0e): a 401 from FCM naming its own error code
(`THIRD_PARTY_AUTH_ERROR`, an iOS or web device without an APNs or web push key) fails that push alone
and keeps the access token; a 403 from Play Games' `players/me` (the API not enabled, say) is 502
`PLAY_GAMES_UNAVAILABLE`, warned of with Google's codes, not 422; a registration's pruning deletes only
its own player's tokens, so one another player moved meanwhile stays; the server closes its CIO
engine at stop. Docs only: the cascades, CIO, push timing and the §8b list of scripts corrected;
**for the user**, CLAUDE.md §8b *Linking Play Games to an account* (an access token alone links a
registered player, for good, with no unlink: provisional); and "stores nothing personal" is now "no
sensitive personal data", the privacy policy to name FCM and Play Games (§8b *Personalization*).

**On `feat/android-release`** (from 2a4f96e; nothing pushed): the Android build Google Play takes
(CLAUDE.md §8, *Release builds*). Prod's release build is signed with the **Play upload key** once
`local.properties` names it, and otherwise with the debug key, one warning line saying so, while
whatever signs prod's bundle (`bundleProdRelease`, and `signProdReleaseBundle` alone) refuses; dev's
and local's release builds always take the debug key, so they install over their debug builds and
back; **R8** shrinks the code and the resources; a placeholder **adaptive icon**
(pink over amber, a white question mark, a themed icon's layer too) and the prod label **Шта би
радије?** replace the wizard's; and the **window and Android 12's splash screen** are the page
background, light and dark, so a dark phone never flashes white. CI's verify job builds
`assembleProdRelease`, checks the signing rules (*Release signing*) and runs the app module's first
unit test, `WindowThemeTest`. **What you do**:
*Release builds and Google Play*, under *Running it locally*, the upload key and the Data safety form
among it. **For you to decide**: CLAUDE.md §8b, *The launcher icon and name* and *R8 and Kotlin 2.4's
metadata*.

### Verified working

- **`feat/android-release`**, on this machine: `ktlintCheck`; the verify job's tests (server 480,
  2 skipped; `:core:domain` 76, `:core:data` 145, `:core:network` 112 and 118 as Android host tests,
  `:app:shared` 395, `:app:adminApp` 106, all from the build cache, their modules untouched; and
  `:app:androidApp:testDevDebugUnitTest`, `WindowThemeTest` 5, which fails with a colour changed) and
  client compiles, `assembleDebug` and `assembleProdRelease` among them; `assembleDevRelease` and
  `assembleLocalRelease`; the ios job's Kotlin compiles. `bundleProdRelease` fails at once, naming
  the four settings, and `signingReport` names the upload config for every release variant once the
  four are given (placeholders, and a keystore that does not exist). The prod APK: label *Шта би
  радије?*, the adaptive icon with its monochrome layer, `Theme.Wyr` with the splash items on v31,
  and its one native library 16 KB aligned (`zipalign -c -P 16` and its ELF LOAD segments); an
  unsigned bundle (`packageLocalReleaseBundle`) carries R8's mapping, as
  `BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`. **On an
  emulator** (the `Emu` AVD, API 35, booted read-only, the app uninstalled after) against a local
  server: the shrunk `localRelease` APK opened Home, Play, a like, an answer, the next question, a
  skip, Account and the Categories screen, every request 200 or 204, and logcat showed no crash and
  no serialization error; the launcher showed the icon as *WYR Local*; a cold start in dark mode
  showed the splash in #161417 with the icon and then Home, no white frame.
- **`feat/server-engagement`**, on this machine, at 8660e01 and again at a371b0e after the review's
  fixes: `ktlintCheck`; `:server:test` (431 at a371b0e, 2 skipped: the PostgreSQL-only boots),
  `:core:domain:jvmTest`, `:core:data:jvmTest`, `:core:network:jvmTest`, `:core:network:testAndroidHostTest`, `:app:shared:jvmTest` and
  `:app:adminApp:jvmTest`; ci.yml's client compiles, `:app:androidApp:assembleDebug` and both web
  targets of `:app:shared` and `:app:adminApp` included; the ios job's Kotlin compiles
  (`:core:compileKotlinIosSimulatorArm64`, `:app:shared:compileKotlinIosSimulatorArm64` and the
  `compileTestKotlinIosSimulatorArm64` of `:app:shared` and the three `:core` modules); gradle's own
  exit code 0, read from its log. `WYR_SERVER_ONLY=1 ./gradlew :server:buildFatJar`, then the jar
  booted on JDK 21 with `PORT=18111` and no `DATABASE_URL`: V1 to V18 applied (14 scripts), `/health`,
  both home pick routes, a push token registered, a vote with `answerMillis`, `GET /v1/me` with
  `playGamesLinked`, and `/v1/auth/play-games` 404 while off; booted again with a throwaway service
  account key and fake Play Games credentials, the CIO engine made, both features on and neither
  secret in the log, and the Play Games route answering a malformed code 400 without calling Google.
  Every call to Google is tested with Ktor's MockEngine; nothing called Google or a deployed server.

- **`merge/redesign` after the review of the `feat/category-picker` merge** (6b44dc4, 5a8adf7 and
  48e3e0e), on this machine, at 48e3e0e, whose tree this note changes only in NEXT-SESSION.md: the
  verify job's lists exactly, `ktlintCheck` with `--rerun-tasks`; `:server:test :core:domain:jvmTest
  :core:data:jvmTest :core:network:jvmTest :core:network:testAndroidHostTest :app:shared:jvmTest
  :app:adminApp:jvmTest`, each with `--rerun`; the client compiles, `:app:androidApp:assembleDebug`
  and both web targets of `:app:shared` and `:app:adminApp` included; and the ios job's Kotlin
  compiles (`:app:shared:compileKotlinIosSimulatorArm64` and the
  `compileTestKotlinIosSimulatorArm64` of `:app:shared` and the three `:core` modules), each
  Gradle's own exit code 0. Test counts from `build/test-results`, as before the review:
  `:server:test` 346 (344 green, 2 skipped: the PostgreSQL-only boot races), `:core:domain:jvmTest`
  72, `:core:data:jvmTest` 142, `:core:network:jvmTest` 72, `:core:network:testAndroidHostTest` 78,
  `:app:shared:jvmTest` 309, `:app:adminApp:jvmTest` 106, no failure anywhere. 5a8adf7 passed
  `:app:shared`'s lint and whole JVM suite alone, and 6b44dc4 its lint and the language and
  categories tests. Changed, not added: `CategoriesScreenDrawTest`, `PlayScreenDrawTest`,
  `PlayScreenTest` and `AppNavigationTest` read the one *Све* and the one *Учитавање* from
  `Strings`, and the count through `fill`.
- **`merge/redesign` with `feat/category-picker` merged in** (9c4ecd4 and its five follow-ups), on
  this machine, at 7353691, whose tree this note changes only in CLAUDE.md and NEXT-SESSION.md: the
  verify job's lists exactly, `ktlintCheck`; `:server:test :core:domain:jvmTest :core:data:jvmTest
  :core:network:jvmTest :core:network:testAndroidHostTest :app:shared:jvmTest
  :app:adminApp:jvmTest`, each with `--rerun`, so none came from the build cache; the client
  compiles, `:app:androidApp:assembleDebug` and both web targets of `:app:shared` and
  `:app:adminApp` included; and the ios job's Kotlin compiles
  (`:app:shared:compileKotlinIosSimulatorArm64` and the `compileTestKotlinIosSimulatorArm64` of
  `:app:shared` and the three `:core` modules), each Gradle's own exit code 0. Test counts from
  `build/test-results`: `:server:test` 346 (344 green, 2 skipped: the PostgreSQL-only boot races),
  `:core:domain:jvmTest` 72, `:core:data:jvmTest` 142, `:core:network:jvmTest` 72,
  `:core:network:testAndroidHostTest` 78, `:app:shared:jvmTest` 309, `:app:adminApp:jvmTest` 106, no
  failure anywhere. The merge commit (327 in `:app:shared`), 0b50d1f, 008d0cc and 0daeb7a each
  passed `:app:shared`'s lint and whole JVM suite alone, and cfdf8af and 7353691 its lint and their
  own package's tests. New or changed: `PlayViewModelTest` 43 (a selection played from each state,
  the picker's 18 gone); `CategoriesViewModelTest` 42 (every category ticked played as all of them,
  the accents folded, the search key); `CategoriesScreenDrawTest` 12 (the game's Try again and Play,
  a failed read offline and otherwise in every language); `CategoryNamesTest` 3 (`nameIn`'s cases);
  `PlayScreenDrawTest` 20 and `PlayScreenTest` 8 (the picker's gone); `AppNavigationTest` 18 (Play,
  Categories and back, played or not, through the row's *Све*); `NavigatorTest` 13,
  `TopBarsDrawTest` 4 (one back bar for Account, Auth, Submit and Categories). `:server`, `:core`
  and `:app:adminApp` are untouched since the merge with `main`.
- **`merge/redesign` with `main` merged in** (60b0d7f merged `--no-ff`), on this machine, the
  merge commit's tree: the verify job's lists exactly, `ktlintCheck`; `:server:test
  :core:domain:jvmTest :core:data:jvmTest :core:network:jvmTest :core:network:testAndroidHostTest
  :app:shared:jvmTest :app:adminApp:jvmTest`, each with `--rerun`, so none came from the build
  cache; the client compiles, `:app:androidApp:assembleDebug` and both web targets of `:app:shared`
  and `:app:adminApp` included; and the ios job's Kotlin compiles
  (`:app:shared:compileKotlinIosSimulatorArm64` and the `compileTestKotlinIosSimulatorArm64` of
  `:app:shared` and the three `:core` modules), each Gradle's own exit code 0. Test counts from
  `build/test-results`: `:server:test` 346 (344 green, 2 skipped: the PostgreSQL-only boot races),
  `:core:domain:jvmTest` 72, `:core:data:jvmTest` 142, `:core:network:jvmTest` 72,
  `:core:network:testAndroidHostTest` 78, `:app:shared:jvmTest` 267, `:app:adminApp:jvmTest` 106, no
  failure anywhere. New or changed: `CategoryNamesTest` (each language's name, one not read by its
  id); `PlayScreenTest` (the categories played in each language, the picker's lines in each);
  `PlayScreenDrawTest` (the picker's words and the categories in each language and the server's
  order, its card within 599 in every language, its loading and failure lines in both themes and
  every language, the row's names in each language); `SubmitScreenTest` and `SubmitScreenDrawTest`
  (the chips in each language, a failed read of the categories with Try again, the refusal for
  points as one line, said once); `SubmitViewModelTest` (the categories read before the points, a
  refusal for points reading the points again); `AppNavigationTest` submits under a category the
  fake server lists. `:server`, `:core` and `:app:adminApp` are main's byte for byte, but for
  `SubmissionRules`' KDoc.
- **`feat/category-picker`** (baf042d, d1605eb, 7df4d5f), on this machine, at 7df4d5f: the verify
  job's lists exactly, `ktlintCheck`; `:server:test :core:domain:jvmTest :core:data:jvmTest
  :core:network:jvmTest :core:network:testAndroidHostTest :app:shared:jvmTest
  :app:adminApp:jvmTest`; the client compiles, `:app:androidApp:assembleDebug` and both web targets
  of `:app:shared` and `:app:adminApp` included; and the ios job's Kotlin compiles
  (`:app:shared:compileKotlinIosSimulatorArm64` and the `compileTestKotlinIosSimulatorArm64` of
  `:app:shared` and the three `:core` modules), each Gradle's own exit code 0. The untouched
  modules' suites came from the build cache, and `:app:shared:jvmTest` was run again with `--rerun`.
  Test counts from `build/test-results`: `:server:test` 346 (344 green, 2 skipped),
  `:core:domain:jvmTest` 72, `:core:data:jvmTest` 142, `:core:network:jvmTest` 72,
  `:core:network:testAndroidHostTest` 78, `:app:shared:jvmTest` 240, `:app:adminApp:jvmTest` 106, no
  failure anywhere. d1605eb and baf042d each passed `:app:shared`'s lint and JVM tests alone. The
  new Play tests fail with the observer taken out, and with its guard taken out; the fit test fails
  with a search hint too long for its line.
- **`feat/server-categories`, the clients** (4ceb426, 4a009e4, a4c05fb, c48aa95), on this machine. At
  the last, the verify job's lists exactly: `ktlintCheck`; `:server:test :core:domain:jvmTest
  :core:data:jvmTest :core:network:jvmTest :core:network:testAndroidHostTest :app:shared:jvmTest
  :app:adminApp:jvmTest`; the client compiles, `:app:androidApp:assembleDebug` and both web targets
  of `:app:shared` and `:app:adminApp` included; and the ios job's Kotlin compiles
  (`:app:shared:compileKotlinIosSimulatorArm64` and the `compileTestKotlinIosSimulatorArm64` of
  `:app:shared` and the three `:core` modules), each Gradle's own exit code 0. Test counts from
  `build/test-results`: `:server:test` 346 (344 green, 2 skipped), `:core:domain:jvmTest` 56,
  `:core:data:jvmTest` 142, `:core:network:jvmTest` 72, `:core:network:testAndroidHostTest` 78,
  `:app:shared:jvmTest` 128, `:app:adminApp:jvmTest` 106, no failure anywhere. At a4c05fb the same
  compiles and every client suite; at 4ceb426 lint and the JVM client suites; at 4a009e4 lint, the
  core suites and, built from `git archive` in the scratchpad, every client compile and suite above.
  New: `DefaultCategoryRepositoryTest` (the server's order, no session sent or minted, a read again
  that shows a category added meanwhile, a failed read keeping the list), `CategoryApiTest`,
  `GetCategoriesTest`, `DataModuleTest` (both modules read the categories from their own server),
  `CategoryRulesTest` and `CategoryLimitsTest`; `QuestionMapperTest` now pins ids kept in the
  server's order, ABSURD as itself and RANDOM an id like any other; `PlayViewModelTest` the picker's
  read on opening, its failure, and every category ticked; `SubmitViewModelTest` the chips' read and
  its failure; `CategoriesViewModelTest` the moderation app's add and rename; the refusal for points
  in `ErrorMapperTest`, `DefaultSubmissionRepositoryTest` and `SubmitScreenTest`. The fat jar
  (`WYR_SERVER_ONLY=1 ./gradlew :server:buildFatJar`) booted on JDK 21, port 18097, in-memory H2, a
  throwaway `ADMIN_TOKEN`: `/health`; the five categories in Serbian; a guest minted; a submission
  with 0 points 409 `NOT_ENOUGH_POINTS`; `seed-1` served under `FOOD`, and a vote on it answered
  213/158 (its made-up 212/158 and the vote) with 1 point; a submission 201, the total 0 and
  `pointsSpent` 1; a second one 409 for points; the moderator's queue listing it, a rejection, and the
  total back to 1 with `pointsSpent` 0; `FAST_FOOD` added from "Fast food", `FOOD` 409
  `CATEGORY_EXISTS`, a rename, and the list ending with it; `?category=RANDOM` 400; an admin route
  with no token 403. Stopped; nothing listens on 18097. No app ran against it: the clients' side of
  the wire is the tests' MockEngine and `FakeServer`, with the same DTOs.
- **`feat/server-categories`**, on this machine, at its last code commit: `ktlintCheck`, the verify
  job's tests and client compiles (`:app:androidApp:assembleDebug` and both web targets included)
  and the ios job's Kotlin compiles, all green; at each commit before it, lint, the server suite,
  `:core:data:jvmTest` and the same compiles. Test counts from `build/test-results`: `:server:test` 346 (344
  green, 2 skipped: the PostgreSQL-only boot races), `:core:domain:jvmTest` 54, `:core:data:jvmTest`
  139, `:core:network:jvmTest` 70, `:core:network:testAndroidHostTest` 76, `:app:shared:jvmTest`
  119, `:app:adminApp:jvmTest` 87. New: `CategoryStoreTest` (the order of categories, an id no
  category has refused, two creations racing for one id), `CategoryRulesTest` (names, ids, the
  derived id), `CategoryFlowTest` (the list to anybody, create, rename, 409, 404, 400, a new
  category submitted, approved and played under); the cost in `SubmissionStoreTest` (charged, too
  few refused with nothing stored, two submissions racing for a last point), `ModerationStoreTest`
  (a rejection pays back what was paid, a question that cost nothing pays back nothing, two
  rejections racing pay once), `StatsStoreTest` (`pointsSpent`, and a submission committed mid-read
  in both numbers or neither) and `ApiFlowTest`; the made-up votes in every tally test and
  `SeedTest`; `MigrationsTest` holds V6 to V9 to `Seed` on a database seeded as before
  (`seedAsBefore`). The fat jar (`WYR_SERVER_ONLY=1 ./gradlew :server:buildFatJar`) booted on JDK 21,
  port 18097, in-memory H2: `/health`, the five categories with Cyrillic names, a submission refused
  for points, one paid by a vote, the next refused, a rejection's refund in `GET /v1/me`, seed-1's
  tally with its made-up votes (212/158 plus the vote), a category created (409 the second time),
  renamed and listed last, RANDOM 400, 403 without the token. **Rollback, by hand:** `40550e9`'s fat
  jar seeded a file H2 database (V1 to V5, English, RANDOM, a submission under FOOD and RANDOM); this
  branch's jar migrated it (V6 to V9: the submission and five seeds under ABSURD, Serbian seeds,
  made-up votes, the old pending one's rejection paying nothing back); `40550e9` booted on it again
  and served: ABSURD shown as RANDOM, its RANDOM filter an empty batch, tallies without the made-up
  votes, the Serbian texts, a submission under FOOD stored and one under RANDOM a 500 (the foreign
  key), as CLAUDE.md §8b, *Rollbacks*, says.
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
  question's categories go out oldest first whatever their ids (`feat/server-categories`, which
  also added `CategoryStoreTest`: the order of categories, and an id no category has refused).
  `ApiFlowTest` covers the repeated `?category=`, the 400s for RANDOM, `UNKNOWN`, an unknown id, a
  comma-separated list and an empty value, and submitting under several (deduplicated and ordered,
  and listed back the same) with the 400s for none, RANDOM and `UNKNOWN`. `SubmissionStoreTest` pins
  the rows, and the author's list in the same number of statements whatever its length.
  `WyrJsonTest` and `ServerJsonTest` pin categories as plain ids on both sides, the first ones'
  JSON as the enum sent it. On the client, `QuestionMapperTest` pins a question's categories as a
  set of ids, each once in the server's order, one no build names kept as it came, and an empty or
  missing list as none; `QuestionApiTest`, one `?category=` per category, in the order given;
  `DefaultQuestionRepositoryTest`, a selection of several sent whole, in id order, with every refill,
  any change to it dropping the queue (a refill in flight included), the same set keeping it, and a
  category added after the build asked for by its id;
  and `PlayViewModelTest`, the Play screen's category picker: ticking, *All categories*, every
  category ticked played as all of them and never as none (`PlayScreenTest` names them, not *All*), the
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
  session), no refresh after a 403, and the token absent from a failure's message and from the trace
  (that half removed with the trace by `chore/remove-console`); `DefaultModerationRepositoryTest`
  the queue mapped as the author's list is, an approval's categories in declaration order and none
  keeping the author's, `OTHER` refused before sending, the trimmed reason, each refusal's
  `DomainError` with its message, `UNKNOWN` for moderation off, and that neither a 403 nor a 401
  replaces the player's session or makes one; `AdminTokenTest` and `RejectionReasonTest` the token's
  and the reason's rules; `ModerationMapperTest` the reason limit against the wire's; and
  `ModerationConsoleViewModelTest` the console's section then: nothing sent without a valid token,
  Reject off until the reason is valid, the queue read again after every decision, picks for a
  submission no longer listed dropped, and the token in neither the state's text nor the log. It
  went with that section on `feat/moderation-app`, whose `ModerationViewModelTest` and
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
  read: the console's kept only the likes-moved comparison, removed with the console by
  `chore/remove-console`, and `PlayViewModelTest` and `PlayScreenTest` cover the Play tab's Like.
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
  session kept. `RunApiOverHttpTest` pins a timeout as `NETWORK`, `HttpTraceTest` a timeout traced
  as one and a cancellation still as a cancellation (removed with the trace by
  `chore/remove-console`). `AndroidTokenStorageTest`, an Android host test, pins `commit()` over
  `apply()`, off the caller's thread, one commit at a time in order, a failed commit thrown, and a
  write whose caller was cancelled landing. `SessionStorageFailureTest` pins a session the store
  could not make durable, as a guest is minted, on a clear and in recovery, failing the call as
  `NETWORK` rather than as a bare exception, with no guest minted twice for it.
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
  by each; the defaults letting 50 answers in a row through (`the default limits let a quick run of
  answers through`, named for the console's *Answer N* until `chore/remove-console`); one INFO line
  per refusal, naming the limit and the player and neither the token nor a client address; no header
  read with none trusted, and behind `CF-Connecting-IP` one budget per address it names, a forged
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
  session and minting no guest; recovering on `RATE_LIMITED` fails them. The console needed nothing
  then: `resultOf` logged every `WyrException` as `err`, `RATE_LIMITED` included, and *Answer N*
  stopped at it (both removed with the console by `chore/remove-console`).
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

- **`feat/android-release`**: a build signed with a real upload key, and any upload to Play (the
  key is the user's to make); the themed icon on a launcher that themes icons (the emulator's does
  not); the icon on Android 7; a shrunk build on a physical phone, and on Play's pre-launch report;
  Play reading the mapping from the bundle.
- **`feat/server-engagement` against real Google.** No push has reached a phone and no Play Games
  code has been exchanged: the requests are built from Google's documentation (FCM HTTP v1's
  `messages:send`, the JWT bearer grant with the `firebase.messaging` scope, the authorization code
  grant with an empty redirect URI, `games/v1/players/me`), and only a MockEngine has answered them.
  Nor has CIO's TLS to Google been seen from Render. Once the user has set up Firebase (CLAUDE.md
  §8b), approve a question on dev whose author's phone registered a token; once Play Games is set up,
  sign in from a tester's phone. Also unchecked: which Android credentials the DEV and LOCAL flavors
  need to sign in with Play Games, and the `server-postgres` CI job on V15 to V18 (H2 only here).

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
  household or a mobile carrier's shared address (CGNAT) shares 60 new guests an hour, and an IPv6
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
- **The clients' categories on a device** (`feat/server-categories`). No build with them has been
  installed or run: the Categories screen, the Submit chips and the moderation app's Categories tab
  are drawn off screen (`CategoriesScreenDrawTest`, `SubmitScreenDrawTest`, `ScreensDrawTest`) and
  driven over fakes, and no app has read `GET /v1/categories` from a real server. Serbian names on a
  phone's fonts, the names in Latinica and English, a list of many more than five categories, and
  the Account card once a question has cost a point (its answers and likes then count more than its
  points; `pointsSpent` is shown nowhere, CLAUDE.md §8b), are unseen. Installed builds from before
  this branch against a server from it: a filter or a submission under RANDOM is 400, ABSURD and
  every new category show as `OTHER`, and a refusal for points reads as `UNKNOWN`, as the server's
  handoff says; not tried on a phone.
- **V6 to V9 on PostgreSQL, and on production** (`feat/server-categories`). They have run only on
  H2, here; `SchemaDriftTest` and `MigrationsTest` take them to PostgreSQL in the `server-postgres`
  job, not yet run on the branch. The next Manual Deploy runs V5 to V9 there in one boot: after it,
  read-only, the history should end `9 SQL`, `categories` hold five rows, no `question_categories`
  row name RANDOM, and `seed-1`'s options be Serbian with 212 and 158 made-up votes.
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
- **The Categories screen on a device** (`feat/category-picker`, merged into `merge/redesign`). The
  same holds for it: drawn off screen (`CategoriesScreenDrawTest`), both themes and every language,
  and driven over fakes, never on a phone. Nobody has typed a search on a phone's keyboard, seen the
  keyboard over the list, scrolled the lazy list under a finger, or gone back from it with
  Android's back. The dialog it replaced, and its pixel checks, are gone. On Android's 360x640 class
  a question not answered yet does not fit, since the categories share the row between the cards in
  every state: its option cards are squeezed below their least height (CLAUDE.md §8b, *The
  categories row on the Play screen*).
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
targets unless it names another environment (below); on Android that is the `localDebug` variant,
not the default. The **Account** tab's last line names the environment and the URL in use.
`WYR_API_BASE_URL`, which pointed the desktop client at any server, is retired (CLAUDE.md §8e): name
an environment instead (below). For the **web** client the server also needs `ALLOWED_WEB_ORIGINS`
set or CORS preflight will reject every request. The page's dev server takes the first free port
from 8080, so with the API already there it serves on 8081:

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
  *WYR Dev* and *WYR*. From the command line: `./gradlew :app:androidApp:installDevDebug`. To judge
  speed, use `devRelease` (`installDevRelease`): a debug build's Compose runs several times slower.
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
socket peer or the player. The one a developer meets first is 60 guests an hour: a sixty-first fresh
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
the console's likes-moved line (removed with the console by `chore/remove-console`). A screen
showing only the points, and the cycle swapped with what is left, each broke a test. Counts:
`:server` 311, 2 skipped (from the build cache: untouched); `:core:domain` 47; `:core:data` 146;
`:core:network` 82 (88 as Android host tests); `:app:shared` 135 (129 before); `:app:adminApp` 87.
Lint, the verify job's tests (client tasks forced to rerun) and client compiles, `assembleDebug`
included, and the ios job's Kotlin compiles pass. **Not verified:** nothing has run on a device or
against a server, and the renders were looked at on this Mac only.

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

### Submit from My questions

The game's **Submit** form, opened from *Ново питање* under *Моја питања* on the Account screen,
writes a question (CLAUDE.md §8d, *Submitting*), and My questions lists your own, in every build,
PROD's included. Submitting costs 1 point, paid back if the question is rejected; once approved,
each like the question holds pays you 1.

**To try it on a phone** (`devDebug`, against the dev server, whose in-memory H2 forgets everything on
a deploy or a spin-down):

1. `./gradlew :app:androidApp:installDevDebug`, open *WYR Dev*, and tap the account icon: *Моја
   питања* reads *Још ниједно.* for a fresh guest. Tap **Ново питање**.
2. Under *Шта би радије…*, type option A and B and tap one or more categories: the server's, read
   each time the form opens, and named in the language shown (*Храна*; *Hrana* in Latinica, *Food*
   in English). Each option says what is wrong as it is typed: nothing but spaces *Напиши нешто.*,
   a line break *Један ред, без прелома.*, over 200 characters *Највише 200 знакова.*, and B the
   same as A, ignoring case and the spaces at either end, *Опције морају да се разликују.* **Пошаљи ·
   1 П** stays off until nothing is wrong and a category is picked.
3. A fresh guest has no points: Send is off, with *Немаш довољно поена.* over it. Answer one on
   **Play**, then open the form again: Send is on. **Пошаљи**: the app goes back to My questions,
   the question on top, trimmed, *На чекању*, and the points 1 lower.
4. Approve it in the moderation app (*The moderation app*, below): `WYR_ENV=dev ./gradlew
   :app:adminApp:run`, type dev's `ADMIN_TOKEN` (its Environment tab on Render), *Load pending*,
   pick categories in place of yours if you like, and **Approve**. On the phone, leave the Account
   screen and come back: *Одобрено*, and **Play** serves it in the current cycle. A rejection shows
   as *Одбијено:* and the reason typed, and a question retired under *All questions* as *Повучено*.
5. The 21st question pending shows *Већ имаш 20 питања на чекању.* and keeps the form. Airplane mode,
   then **Пошаљи**: *Нема интернет везе.*, the form kept to send again. Open the form offline: the
   points and the categories both fail, and one *Нема интернет везе.* under Send says so, with
   **Покушај поново**, which reads both once back online.

Locally the same works against `ADMIN_TOKEN=... ./gradlew :server:run` (*Moderating*, below), with
`./gradlew :app:desktopApp:run` for the game and `./gradlew :app:adminApp:run` to approve.

### Skip and Like on Play

The game's **Play** screen skips and likes (CLAUDE.md §8d, *The Play screen*, *Skipping* and
*Likes*), in every build, PROD's included, laid out as `feat/play-redesign` left it: two cards and
one row between them, the phone in Serbian Cyrillic as it opens.

**To try it on a phone** (`devDebug`, against the dev server, whose in-memory H2 forgets everything on
a deploy or a spin-down; the server needs nothing new):

1. `./gradlew :app:androidApp:installDevDebug`, open *WYR Dev*, and tap **Играј** on Home.
2. On the right of the row between the cards: the heart and the like count, empty and `0` on a
   question nobody likes. Tap the heart: it fills and the count goes up by one; tap it again and
   both go back. The count is the server's, so another player's like shows only when the question is
   served again.
3. Tap the skip icon at the right end of the row, after the like count: the next question comes,
   and no points. The top bar holds only home and the account icon. The skipped one is not served
   again this cycle: with only the 24 seeds and no category picked, it comes back once the other 23
   are answered or skipped, in the next cycle.
4. Tap a card: both percentages count up over 2.5 seconds, your pick outlined, the points in the
   middle of the row move to the vote's total, and Skip goes, its place left empty, so the heart
   and the count stay where they were. The heart works the same there. A card does nothing for half a second after the reveal lands, so a double tap cannot skip
   it; then either card is the next question. With TalkBack on, a revealed card says *Следеће
   питање*, and the categories *Промени категорије*.
5. Airplane mode, then the heart: the question stays as it was and the points' place says *Игра није
   доступна.* without moving the cards, at a larger font size in the phone's settings too. Network
   back on, the heart again: it goes through. Skip offline moves on all the same while questions are
   queued, and shows *Игра није доступна.* with **Покушај поново** once they run out.
6. A like of your own question pays you a point: submit one (the account icon, then *Ново питање*
   under *Моја питања*), approve it in the moderation app, and play until it comes up. Like it: the points in
   the row show the point from the next vote on, or once Play is shown again (the home icon, then
   **Играј**).

### Categories on Play

The game's **Play** screen plays the categories picked on the **Categories** screen, opened from
the categories in the row between the cards (CLAUDE.md §8d, *The Play screen*, *Categories*, *The
Categories screen*), in every build, PROD's included. The selection lives in memory for the app's
life, so a launch plays every category again. The categories are the server's, read each time the
Categories screen opens, and named in the language shown: Serbian in Cyrillic, the same made Latin
in Latinica, and the English name in English (§8f).

**To try it on a phone** (`devDebug`, as for *Skip and Like on Play*; a server from
`feat/server-categories` or later, which `main` is):

1. `./gradlew :app:androidApp:installDevDebug`, open *WYR Dev*, and tap **Играј** on Home. On the
   left of the row between the cards: **Све** and a small chevron, which should read as something
   to tap.
2. Tap it: the **Categories** screen, a back arrow over *Претражи категорије*, then *Све* (ticked) and
   the server's categories, *Храна* to *Апсурдно* on a fresh server, and **Играј** at the bottom. Tick
   *Храна* and *Етика*: *Све* unticks, and *Изабрано: 2* shows beside **Играј**. Tap it: back on Play,
   the question on screen goes, and the next is filed under either; the row reads *Храна, Етика*.
   Try it from a question asked, from a revealed one and from a failure: each time a question from
   the new selection shows.
3. Open it again: *Храна* and *Етика* are ticked. Type *eti*, then *ети*, then *ETH*: *Етика* each
   time. Type *nacin*, with no accent, then *način*: *Начин живота* both times. Type *xyz*: *Нема
   резултата* under *Све*. Tick *Све*: the others untick. The back arrow, or Android's back: nothing
   changes, and opening it again shows *Храна* and *Етика* ticked. **Играј** with what is already
   played: the question stays.
4. On Account pick *Latinica*, then *English*: the list reads *Hrana*, *Način života*..., then
   *Food*, *Lifestyle*..., and the row *Hrana, Etika*, then *Food, Ethics*.
5. Offline, opening it says *Игра није доступна.* with **Покушај поново** over the categories read
   before, as Play says it offline. A category the moderation app adds shows the next time it opens.
   With the keyboard up, **Играј** should stay above it.
6. Tick every category: the row's names are cut short on their one line, and the points move right
   of the middle only as far as the names need; the points, the like count and Skip stay whole. That
   is the arrangement to judge (CLAUDE.md §8b, *The Play row's arrangement*): keep it, or the points
   always in the middle and the names cut shorter.
7. While a vote or a like is in flight the categories do nothing when tapped. *Све*, then **Играј**,
   goes back to the whole feed. Categories the server has no questions in would show *Нема више
   питања.* with **Покушај поново** and the categories as the way out; every category has seeds, so
   only a server without them shows it.

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
  -H 'Content-Type: application/json' -d '{"questionId":"<id from the queue>","categories":["SUPERPOWERS","ABSURD"]}'
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

To add a category, its id made from the English name when none is given (409 `CATEGORY_EXISTS` for
one a category has), and to put its names right; `GET /v1/categories` lists them to anybody:

```bash
curl -s -X POST localhost:8080/v1/admin/categories -H "X-Admin-Token: $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' -d '{"nameSr":"Брза храна","nameEn":"Fast food"}'
curl -s -X POST localhost:8080/v1/admin/category-renames -H "X-Admin-Token: $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' -d '{"id":"FAST_FOOD","nameSr":"Брза клопа","nameEn":"Fast food"}'
curl -s localhost:8080/v1/categories
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

Every *Load*, on any tab, reads the categories first (`GET /v1/categories`, no admin request and no
token spent): they are the chips, named in Serbian, and a category not listed shows by its id.
**Categories** (`feat/server-categories`) lists them, oldest first, each with its id and both names.
*Add a category* takes a Serbian name, an English name and an id, which the server makes from the
English name when left blank (*Fast food* is `FAST_FOOD`; a name of no Latin letter or digit needs
one typed); *Add* stays off until the names are one line of at most 40 and the id, if typed, is 1 to
32 of `A`-`Z`, `0`-`9` and `_`. *Rename...* opens a category's names in its card, its id fixed, and
*Save names* sends them. After an add or a rename the list is read again whatever became of it; an
id a category has already says `A category has that id already (409)` under the form. *Lock* forgets
what was typed there and keeps the categories read.

### Analytics

The game reports what players do to PostHog (CLAUDE.md §8g), from shared code, on all four
platforms, but only a build given a project's key: without one, as every test and CI build runs, it
sends nothing. The key is never committed.

**To see events from a phone** (`devDebug`, against the dev server):

1. Make a project on PostHog's **EU** cloud (https://eu.posthog.com), turn on *Settings → Project →
   IP data capture configuration → Discard client IP data* (CLAUDE.md §8g, *Where the player is*),
   and copy its *Project API key* (`phc_...`) from *Settings → Project → General*.
2. Put it in `local.properties`, at the repository's root (git ignores it), and install:

   ```properties
   wyr.posthog.key=phc_...
   ```

   ```bash
   ./gradlew :app:androidApp:installDevDebug
   ```

   A US project needs `wyr.posthog.host=us.i.posthog.com` beside it; none is the EU cloud.
3. Play a little, then put the app in the background (which sends at once; otherwise a batch goes
   every 20 events or 30 s), and open PostHog's *Activity*: `app_opened`, `$screen` for each screen,
   `tap` with its `element`, `question_shown`, `question_answered`, each with `environment: dev`.

The other platforms read the same key where they read their environment (CLAUDE.md §8e):

- **Web**: `local.properties` too, or `-Pwyr.posthog.key=phc_...` on the build.
- **Desktop**: `WYR_POSTHOG_KEY=phc_... WYR_ENV=dev ./gradlew :app:desktopApp:run`.
- **iOS**: `app/iosApp/Configuration/Local.xcconfig` (git ignores it), holding
  `WYR_POSTHOG_KEY=phc_...`, and a host, if any, without `https://`.

A key someone else can read is no harm: a project's key can only send events, never read them. A
key rotated in PostHog needs a build again.

Every event names the app's version, `wyr.app.version` in `gradle.properties` (1.0.0), which every
platform's build reads; a release bumps `MARKETING_VERSION` in `app/iosApp/Configuration/Config.xcconfig`
with it, or Gradle refuses to build.

### Release builds and Google Play

Prod's release build is signed with the Play upload key once it is set up (CLAUDE.md §8, *Release
builds*); until then with the debug key, saying so in one warning line, and `bundleProdRelease`, the
file Play takes, refuses at once, naming what is missing. Dev's and local's release builds are always
signed with the debug key, so `./gradlew :app:androidApp:installDevRelease` installs over `devDebug`
and back, keeping the phone's guest, before the key and after it.

**Play App Signing, in short.** Play keeps the *app signing key* and signs what phones install with
it; the *upload key*, yours, only proves an upload came from you. A lost or leaked upload key is reset
from the Play Console's *App signing* page (*Request upload key reset*), and the app and its players
are untouched. So the keystore below deserves a backup, but it is not the app.

**What you do, once:**

1. Make the upload key, outside the repository. keytool asks for a password, then a name and a place
   for the certificate (anything; Play shows none of it):

   ```bash
   keytool -genkeypair -v -keystore ~/wyr-upload.jks -alias upload \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

   keytool's own kind of keystore (PKCS12) has one password for the store and the key, so the same
   value goes in both password lines below. Back up `wyr-upload.jks` and the password, in a password
   manager say.
2. Name it in `local.properties` at the repository's root (git ignores it), by its absolute path, as
   `~` is not expanded there:

   ```properties
   wyr.upload.storeFile=/Users/<you>/wyr-upload.jks
   wyr.upload.storePassword=<the password>
   wyr.upload.keyAlias=upload
   wyr.upload.keyPassword=<the same password>
   ```

   A build machine can set `WYR_UPLOAD_STORE_FILE`, `WYR_UPLOAD_STORE_PASSWORD`,
   `WYR_UPLOAD_KEY_ALIAS` and `WYR_UPLOAD_KEY_PASSWORD` instead. `./gradlew
   :app:androidApp:signingReport` then says *Config: upload* for `prodRelease`, and *Config: debug*
   for every other variant.
3. Build the bundle: `./gradlew :app:androidApp:bundleProdRelease`, which writes
   `app/androidApp/build/outputs/bundle/prodRelease/androidApp-prod-release.aab`.
4. In the Play Console (the developer account is CLAUDE.md §8b *Play Games sign-in*'s step 1):
   *Create app*, then *Test and release → Testing → Internal testing → Create new release*. On the
   first release keep Play App Signing with a key Google makes (the default), upload the `.aab`, which
   registers your upload key, and roll it out to a list of testers' Google accounts, who install it
   from the opt-in link the page gives. Every later upload needs a higher `versionCode`.
5. From the Play Console's *App signing* page, copy the **app signing key's SHA-1**: Play Games'
   Android credential needs it (CLAUDE.md §8b, *Play Games sign-in*, step 3), beside the upload key's
   and the debug key's for builds made on a laptop, which `signingReport` prints.
6. R8's mapping travels inside the bundle, so Android vitals shows crashes in this code's names
   (*App bundle explorer → Downloads* lists it as the ReTrace mapping file). For a build made on the
   laptop it is `app/androidApp/build/outputs/mapping/prodRelease/mapping.txt`, which the SDK's
   `retrace` reads; keep it beside any APK you hand out, as the next build's differs.

**R8 breaks mostly at run time**, so after a library changes, play a shrunk build on an emulator
against a local server: `WYR_SERVER_ONLY=1 ./gradlew :server:buildFatJar`, then
`PORT=8080 java -jar server/build/libs/server-all.jar` (JDK 21), `./gradlew
:app:androidApp:installLocalRelease`, and in the app Home, Play, a like, an answer, a skip, Account
and the categories, with `adb logcat` open for `FATAL` and `Serializ`.

**What Google Play asks of this build** (checked 2026-09-26):

- *Target API* 36 (`android-targetSdk` in the catalog), Play's level for new apps and updates in
  2026. Play raises it every year: check its policy page before a release.
- *16 KB pages*: the APK's one native library, Compose's `libandroidx.graphics.path.so`, is 16 KB
  aligned for all four ABIs. After adding a library, check a release APK again:
  `$ANDROID_HOME/build-tools/36.1.0/zipalign -c -P 16 -v 4 <apk>` must end in *Verification
  successful*, and Android Studio's *Build → Analyze APK* flags a `.so` whose segments are not.
- *The Data safety form*, which is yours to fill in, from the list below.

**Data safety: what the app collects and sends.** No ads, no advertising id, nothing sold. Everything
travels over HTTPS (only the LOCAL flavor, which never ships, allows plain http), and a player can
delete their account in the app (`feat/account-client`) and through a web page Play asks you to link
(`docs/site`).

- *To the game's server* (Render), for the game to work, not optional:
  - **User IDs**: the player id, random and made by the server, for every player, a guest's
    included; the username, once a player registers; the Play Games player id, once Play Games
    sign-in lands (`feat/android-services`). The password is sent to register and to log in, and kept
    only as a salted hash.
  - **App activity**: answers, with the side picked and how long each took; skips; likes and
    dislikes; reports and hidden questions and authors (*App interactions*, *Other actions*); and the
    questions a player writes (*Other user-generated content*).
  - **Device or other IDs**: the device's push token (FCM, `feat/android-services`), to tell an
    author a moderator decided their question.
  - The address each request comes from, which the rate limits count by, in memory; the server logs
    no address (Render's and Cloudflare's own logs may) and derives no location from it.
- *To PostHog* (analytics, CLAUDE.md §8g), only while **Statistics** is on: on by default, and the
  player can turn it off, so it is optional:
  - **Device or other IDs**: a random id per install. **User IDs**: the player id, once the player
    registers or logs in.
  - **App activity → App interactions**: screens shown and for how long, taps by the button's name,
    questions shown, answered (the side and the time) and skipped, reactions, registrations, logins,
    logouts and the language picked.
  - **App info and performance → Diagnostics**: the error codes a screen shows.
  - With every event, the OS and its version, the device type, the app version and the platform. No
    location: every event says `$geoip_disable`, and the project discards the address once you turn
    that setting on (CLAUDE.md §8g, *Setting up PostHog*).
- *To Google*, by its own libraries once `feat/android-services` lands: Firebase Cloud Messaging (the
  push token, and Firebase's own installation id) and Play Games Services (sign-in). Google and PostHog
  process it for the game, as service providers, which Play's form does not count as sharing.
- *Not collected*: an email, a name, a phone number, contacts, photos or files, location, financial or
  health data, messages, audio, the calendar, browsing history, or crash logs (the app has no crash
  reporter; Android vitals is Play's own).

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
17. `chore/remove-console` *(on `main` at 60b0d7f, with `feat/server-categories`)* — the console
    goes, with nothing in its place; every build shows the game's screens, and a LOCAL or DEV build
    names its server on Account.
18. **Now:** `merge/redesign` — the user's redesign, the app's foundation, Play, Account and the
    Categories screen, on `main`'s server categories: every branch merged (*Where we are*), ready
    for `main`. Next: review, merge into `main`, push, read CI (the `server-postgres`,
    `docker-smoke` and `ios` jobs run only there), then try it on a phone (*Categories on Play*,
    *Trying a change*).

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

- **The wire enum rule (§5) is load-bearing and easy to break silently.** It covers the growable
  enums on the wire, `QuestionStatus` and `ErrorCode`, and only works because `WyrJson` sets
  `coerceInputValues = true` *and* `ServerJson` sets `encodeDefaults = true`. Remove either and the
  `UNKNOWN` defaults become decorative. `WyrJsonTest` in `:core:network` pins the client half. The
  server's `encodeDefaults` is pinned by `ApiFlowTest`'s fresh-guest stats test, which checks every
  stats field is sent at its default, and by `ServerJsonTest`, which checks an empty list of
  categories is sent too; a server-side slip only breaks client builds older than the server.
  Categories are no enum on the wire since `feat/server-categories` (§5): every categories field is
  a list of plain string ids, empty by default, so a category added server-side is an id an
  installed client has no name for, never a payload it fails to decode, and needs no serializer of
  its own. The client keeps the ids as sent, in the server's order (`QuestionMapper`), and a payload
  without them reads as filed under none; a screen names each id by the list `GET /v1/categories`
  last gave, and one not in it by its id. `WyrJsonTest` and `QuestionMapperTest` pin the decoding
  and the mapping. Should a list of a growable enum ever go on the wire, it needs a serializer that
  decodes an unknown element as `UNKNOWN` (§5), since coercion never reaches a list's elements.
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
