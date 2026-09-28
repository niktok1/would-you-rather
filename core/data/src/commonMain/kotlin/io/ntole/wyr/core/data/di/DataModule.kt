package io.ntole.wyr.core.data.di

import io.ktor.client.HttpClient
import io.ntole.wyr.core.data.account.DefaultAccountRepository
import io.ntole.wyr.core.data.cache.InMemoryQuestionCache
import io.ntole.wyr.core.data.category.DefaultCategoryRepository
import io.ntole.wyr.core.data.home.DefaultHomePickRepository
import io.ntole.wyr.core.data.moderation.DefaultModerationRepository
import io.ntole.wyr.core.data.notice.StoredSeenDecisions
import io.ntole.wyr.core.data.player.DefaultPlayerRepository
import io.ntole.wyr.core.data.playgames.DefaultPlayGamesRepository
import io.ntole.wyr.core.data.push.DefaultPushTokenRepository
import io.ntole.wyr.core.data.question.DefaultQuestionRepository
import io.ntole.wyr.core.data.reaction.DefaultReactionRepository
import io.ntole.wyr.core.data.report.DefaultReportRepository
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.session.PlayGamesSettled
import io.ntole.wyr.core.data.shop.DefaultShopRepository
import io.ntole.wyr.core.data.submission.DefaultSubmissionRepository
import io.ntole.wyr.core.data.vote.DefaultVoteRepository
import io.ntole.wyr.core.domain.account.AccountRepository
import io.ntole.wyr.core.domain.account.DeleteAccount
import io.ntole.wyr.core.domain.account.LogIn
import io.ntole.wyr.core.domain.account.LogOut
import io.ntole.wyr.core.domain.account.RegisterAccount
import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.category.CategoryRepository
import io.ntole.wyr.core.domain.category.GetCategories
import io.ntole.wyr.core.domain.home.GetHomePicks
import io.ntole.wyr.core.domain.home.HomePickRepository
import io.ntole.wyr.core.domain.home.PickOnHome
import io.ntole.wyr.core.domain.moderation.AddCategory
import io.ntole.wyr.core.domain.moderation.ApproveSubmission
import io.ntole.wyr.core.domain.moderation.BlockAuthor
import io.ntole.wyr.core.domain.moderation.DeletePlayerAccount
import io.ntole.wyr.core.domain.moderation.DismissReports
import io.ntole.wyr.core.domain.moderation.GetPendingSubmissions
import io.ntole.wyr.core.domain.moderation.GetQuestions
import io.ntole.wyr.core.domain.moderation.GetReportedQuestions
import io.ntole.wyr.core.domain.moderation.ModerationRepository
import io.ntole.wyr.core.domain.moderation.RejectSubmission
import io.ntole.wyr.core.domain.moderation.RenameCategory
import io.ntole.wyr.core.domain.moderation.RestoreQuestion
import io.ntole.wyr.core.domain.moderation.RetireQuestion
import io.ntole.wyr.core.domain.moderation.UnblockAuthor
import io.ntole.wyr.core.domain.notice.DecisionNotices
import io.ntole.wyr.core.domain.notice.SeenDecisionsStore
import io.ntole.wyr.core.domain.player.GetPlayerStats
import io.ntole.wyr.core.domain.player.PlayerRepository
import io.ntole.wyr.core.domain.playgames.LinkPlayGames
import io.ntole.wyr.core.domain.playgames.PlayGames
import io.ntole.wyr.core.domain.playgames.PlayGamesRepository
import io.ntole.wyr.core.domain.push.DevicePush
import io.ntole.wyr.core.domain.push.KeepPushTokenRegistered
import io.ntole.wyr.core.domain.push.PushTokenRepository
import io.ntole.wyr.core.domain.question.GetNextQuestion
import io.ntole.wyr.core.domain.question.QuestionCache
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.question.SkipQuestion
import io.ntole.wyr.core.domain.reaction.ReactionRepository
import io.ntole.wyr.core.domain.reaction.SetReaction
import io.ntole.wyr.core.domain.report.HideAuthor
import io.ntole.wyr.core.domain.report.HideQuestion
import io.ntole.wyr.core.domain.report.ReportQuestion
import io.ntole.wyr.core.domain.report.ReportRepository
import io.ntole.wyr.core.domain.session.CurrentSession
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.domain.shop.BuyTheme
import io.ntole.wyr.core.domain.shop.GetShop
import io.ntole.wyr.core.domain.shop.ShopRepository
import io.ntole.wyr.core.domain.submission.GetMySubmissions
import io.ntole.wyr.core.domain.submission.SubmissionRepository
import io.ntole.wyr.core.domain.submission.SubmitQuestion
import io.ntole.wyr.core.domain.update.AppUpdate
import io.ntole.wyr.core.domain.vote.CastVote
import io.ntole.wyr.core.domain.vote.VoteRepository
import io.ntole.wyr.core.network.ClientBuild
import io.ntole.wyr.core.network.InMemoryTokenStorage
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.TokenStorage
import io.ntole.wyr.core.network.UpgradeSignal
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.analytics.PostHogAnalytics
import io.ntole.wyr.core.network.analytics.PostHogConfig
import io.ntole.wyr.core.network.api.AuthApi
import io.ntole.wyr.core.network.api.CategoryApi
import io.ntole.wyr.core.network.api.HomePickApi
import io.ntole.wyr.core.network.api.ModerationApi
import io.ntole.wyr.core.network.api.PlayerApi
import io.ntole.wyr.core.network.api.PushApi
import io.ntole.wyr.core.network.api.QuestionApi
import io.ntole.wyr.core.network.api.ReactionApi
import io.ntole.wyr.core.network.api.ReportApi
import io.ntole.wyr.core.network.api.ShopApi
import io.ntole.wyr.core.network.api.SubmissionApi
import io.ntole.wyr.core.network.api.VoteApi
import io.ntole.wyr.core.network.environment.WyrEnvironment
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Wiring for the network + data layers of the game: the player's, and nothing of the moderator's,
 * which is [moderationDataModule]'s alone (CLAUDE.md §8d, *Moderation*).
 *
 * Expects a [TokenStorage] to already be registered — that is the one binding only a platform
 * can supply, so it comes from `:app:shared`'s platform module.
 *
 * @param environment the server environment the build targets: every request goes to its
 *   [WyrEnvironment.apiBaseUrl], and its session is kept apart from every other environment's in
 *   that storage ([SessionStore]), as its analytics id is.
 * @param analytics the PostHog project the build sends its analytics to (CLAUDE.md §8g), or none,
 *   when nothing is sent: the [Analytics] bound keeps only the player's switch then.
 * @param build the build every request names (CLAUDE.md §8b, *Minimum client version*), or none, for
 *   a build that could not read its own number, whose requests name nothing. An answer that the build
 *   is too old raises the [AppUpdate] bound, whichever call it was.
 * @param playGames Google Play Games Services on this device (CLAUDE.md §8a, *Play Games sign-in*):
 *   an Android build's that has it set up, and [PlayGames.None] everywhere else.
 * @param push this device's pushes (CLAUDE.md §8a, *Push tokens*): an Android build's that has Firebase
 *   set up, and [DevicePush.None] everywhere else.
 */
public fun dataModule(
    environment: WyrEnvironment,
    analytics: PostHogConfig?,
    build: ClientBuild?,
    playGames: PlayGames = PlayGames.None,
    push: DevicePush = DevicePush.None,
): Module =
    module {
        single { SessionStore(get<TokenStorage>(), environment) }
        single<Analytics> { PostHogAnalytics(config = analytics, storage = get(), environment = environment) }
        single { UpgradeSignal() }
        single<AppUpdate> { get<UpgradeSignal>() }
        single<HttpClient> {
            WyrHttpClient.create(
                baseUrl = environment.apiBaseUrl,
                sessionStore = get(),
                build = build,
                upgrade = get(),
            )
        }

        single { AuthApi(get()) }
        single { QuestionApi(get()) }
        single { VoteApi(get()) }
        single { PlayerApi(get()) }
        single { SubmissionApi(get()) }
        single { ReactionApi(get()) }
        single { ReportApi(get()) }
        single { HomePickApi(get()) }
        single { ShopApi(get()) }
        single { CategoryApi(get()) }
        single { PushApi(get()) }

        single<QuestionCache> { InMemoryQuestionCache() }

        // Bound as the concrete type as well: repositories recover a dead session through
        // withSessionRecovery, which is recovery machinery and deliberately not on the domain
        // interface.
        single {
            DefaultSessionRepository(
                authApi = get(),
                sessionStore = get(),
                playGamesSettled = PlayGamesSettled(get<TokenStorage>(), environment),
            )
        }
        single<SessionRepository> { get<DefaultSessionRepository>() }
        single<CurrentSession> { get<DefaultSessionRepository>() }

        single<QuestionRepository> { DefaultQuestionRepository(api = get(), session = get(), cache = get()) }
        single<VoteRepository> { DefaultVoteRepository(api = get(), session = get()) }
        single<PlayerRepository> { DefaultPlayerRepository(api = get(), session = get()) }
        single<SubmissionRepository> { DefaultSubmissionRepository(api = get(), session = get()) }
        single<ReactionRepository> { DefaultReactionRepository(api = get(), session = get()) }
        single<ReportRepository> { DefaultReportRepository(api = get(), session = get()) }
        single<HomePickRepository> { DefaultHomePickRepository(api = get(), session = get()) }
        single<ShopRepository> { DefaultShopRepository(api = get(), session = get()) }
        single<AccountRepository> { DefaultAccountRepository(api = get(), session = get()) }
        single<CategoryRepository> { DefaultCategoryRepository(api = get()) }
        single<PlayGamesRepository> { DefaultPlayGamesRepository(api = get(), session = get()) }
        single<DevicePush> { push }
        single<PushTokenRepository> { DefaultPushTokenRepository(api = get()) }

        factory { GetNextQuestion(questions = get(), session = get()) }
        factory { SkipQuestion(questions = get(), session = get()) }
        factory { CastVote(votes = get(), session = get()) }
        factory { GetPlayerStats(players = get(), session = get()) }
        factory { SubmitQuestion(submissions = get(), session = get()) }
        factory { GetMySubmissions(submissions = get(), session = get()) }
        factory { SetReaction(reactions = get(), session = get()) }
        factory { ReportQuestion(reports = get(), session = get()) }
        factory { HideQuestion(reports = get(), session = get()) }
        factory { HideAuthor(reports = get(), questions = get(), session = get()) }
        factory { GetHomePicks(picks = get()) }
        factory { PickOnHome(picks = get(), session = get()) }
        factory { GetShop(shop = get(), session = get()) }
        factory { BuyTheme(shop = get(), session = get()) }
        factory { RegisterAccount(accounts = get(), session = get(), analytics = get()) }
        factory { LogIn(accounts = get(), questions = get(), session = get(), analytics = get()) }
        factory { LogOut(accounts = get(), questions = get(), analytics = get()) }
        factory { DeleteAccount(accounts = get(), questions = get(), analytics = get()) }
        factory { GetCategories(categories = get()) }
        factory { KeepPushTokenRegistered(push = get(), tokens = get(), session = get()) }
        single<SeenDecisionsStore> { StoredSeenDecisions(get<TokenStorage>(), environment) }
        // Once for the app: the badge on the account icon is its unseen, whoever reads it.
        single { DecisionNotices(submissions = get(), session = get(), seen = get()) }
        // Once for the app: the launch's sign-in and a tap on the Auth page's button take turns.
        single {
            LinkPlayGames(playGames = playGames, link = get(), session = get(), questions = get(), analytics = get())
        }
    }

/**
 * Wiring for a client that only moderates (CLAUDE.md §8d, *Moderation*): the moderator's repository
 * and use cases, and the categories, which need no session either, over an HTTP client of its own,
 * and nothing of the player's. Every request goes to [environment]'s [WyrEnvironment.apiBaseUrl].
 *
 * Needs no [TokenStorage]: the client's session store is in memory and nothing ever writes to it, so
 * no request carries a bearer token, the Auth plugin has nothing to refresh, and nothing is written
 * to a platform's storage. No session repository is bound either, so nothing can mint a guest. The
 * admin token goes on each call as it is handed to the use case.
 *
 * The only moderation wiring there is: the game's [dataModule] binds none of it. Its requests name no
 * build (CLAUDE.md §8b, *Minimum client version*), so no minimum ever refuses the moderator.
 */
public fun moderationDataModule(environment: WyrEnvironment): Module =
    module {
        single<HttpClient> {
            WyrHttpClient.create(
                baseUrl = environment.apiBaseUrl,
                sessionStore = SessionStore(InMemoryTokenStorage(), environment),
            )
        }
        single { ModerationApi(get()) }
        single { CategoryApi(get()) }
        single<ModerationRepository> { DefaultModerationRepository(api = get()) }
        single<CategoryRepository> { DefaultCategoryRepository(api = get()) }
        factory { GetPendingSubmissions(moderation = get()) }
        factory { ApproveSubmission(moderation = get()) }
        factory { RejectSubmission(moderation = get()) }
        factory { GetQuestions(moderation = get()) }
        factory { RetireQuestion(moderation = get()) }
        factory { RestoreQuestion(moderation = get()) }
        factory { AddCategory(moderation = get()) }
        factory { RenameCategory(moderation = get()) }
        factory { GetReportedQuestions(moderation = get()) }
        factory { DismissReports(moderation = get()) }
        factory { BlockAuthor(moderation = get()) }
        factory { UnblockAuthor(moderation = get()) }
        factory { DeletePlayerAccount(moderation = get()) }
        factory { GetCategories(categories = get()) }
    }
