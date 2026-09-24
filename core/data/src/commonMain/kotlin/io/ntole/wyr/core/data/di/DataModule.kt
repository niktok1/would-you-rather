package io.ntole.wyr.core.data.di

import io.ktor.client.HttpClient
import io.ntole.wyr.core.data.cache.InMemoryQuestionCache
import io.ntole.wyr.core.data.moderation.DefaultModerationRepository
import io.ntole.wyr.core.data.player.DefaultPlayerRepository
import io.ntole.wyr.core.data.question.DefaultQuestionRepository
import io.ntole.wyr.core.data.session.DefaultSessionDiagnostics
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.submission.DefaultSubmissionRepository
import io.ntole.wyr.core.data.vote.DefaultVoteRepository
import io.ntole.wyr.core.domain.moderation.ApproveSubmission
import io.ntole.wyr.core.domain.moderation.GetPendingSubmissions
import io.ntole.wyr.core.domain.moderation.ModerationRepository
import io.ntole.wyr.core.domain.moderation.RejectSubmission
import io.ntole.wyr.core.domain.player.GetPlayerStats
import io.ntole.wyr.core.domain.player.PlayerRepository
import io.ntole.wyr.core.domain.question.GetNextQuestion
import io.ntole.wyr.core.domain.question.QuestionCache
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.question.SkipQuestion
import io.ntole.wyr.core.domain.session.SessionDiagnostics
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.domain.submission.GetMySubmissions
import io.ntole.wyr.core.domain.submission.SubmissionRepository
import io.ntole.wyr.core.domain.submission.SubmitQuestion
import io.ntole.wyr.core.domain.vote.CastVote
import io.ntole.wyr.core.domain.vote.VoteRepository
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.TokenStorage
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.api.AuthApi
import io.ntole.wyr.core.network.api.ModerationApi
import io.ntole.wyr.core.network.api.PlayerApi
import io.ntole.wyr.core.network.api.QuestionApi
import io.ntole.wyr.core.network.api.SubmissionApi
import io.ntole.wyr.core.network.api.VoteApi
import io.ntole.wyr.core.network.trace.HttpTrace
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Wiring for the network + data layers.
 *
 * Expects a [TokenStorage] to already be registered — that is the one binding only a platform
 * can supply, so it comes from `:app:shared`'s platform module.
 *
 * @param baseUrl root URL of the API, including scheme.
 */
public fun dataModule(baseUrl: String): Module =
    module {
        single { SessionStore(get<TokenStorage>()) }
        single { HttpTrace() }
        single<HttpClient> { WyrHttpClient.create(baseUrl = baseUrl, sessionStore = get(), trace = get()) }

        single { AuthApi(get()) }
        single { QuestionApi(get()) }
        single { VoteApi(get()) }
        single { PlayerApi(get()) }
        single { SubmissionApi(get()) }

        single<QuestionCache> { InMemoryQuestionCache() }

        // The moderator's, beside the player's rather than on top of them: no session, so no
        // recovery and no use case that ensures one (CLAUDE.md §8d, Moderation).
        single { ModerationApi(get()) }
        single<ModerationRepository> { DefaultModerationRepository(api = get()) }
        factory { GetPendingSubmissions(moderation = get()) }
        factory { ApproveSubmission(moderation = get()) }
        factory { RejectSubmission(moderation = get()) }

        // Bound as the concrete type as well: repositories recover a dead session through
        // withSessionRecovery, which is recovery machinery and deliberately not on the domain
        // interface.
        single { DefaultSessionRepository(authApi = get(), sessionStore = get()) }
        single<SessionRepository> { get<DefaultSessionRepository>() }
        single<SessionDiagnostics> { DefaultSessionDiagnostics(sessionStore = get()) }

        single<QuestionRepository> { DefaultQuestionRepository(api = get(), session = get(), cache = get()) }
        single<VoteRepository> { DefaultVoteRepository(api = get(), session = get()) }
        single<PlayerRepository> { DefaultPlayerRepository(api = get(), session = get()) }
        single<SubmissionRepository> { DefaultSubmissionRepository(api = get(), session = get()) }

        factory { GetNextQuestion(questions = get(), session = get()) }
        factory { SkipQuestion(questions = get(), session = get()) }
        factory { CastVote(votes = get(), session = get()) }
        factory { GetPlayerStats(players = get(), session = get()) }
        factory { SubmitQuestion(submissions = get(), session = get()) }
        factory { GetMySubmissions(submissions = get(), session = get()) }
    }
