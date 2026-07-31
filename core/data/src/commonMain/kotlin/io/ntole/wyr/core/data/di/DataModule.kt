package io.ntole.wyr.core.data.di

import io.ktor.client.HttpClient
import io.ntole.wyr.core.data.cache.InMemoryQuestionCache
import io.ntole.wyr.core.data.question.DefaultQuestionRepository
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.vote.DefaultVoteRepository
import io.ntole.wyr.core.domain.question.GetNextQuestion
import io.ntole.wyr.core.domain.question.QuestionCache
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.domain.vote.CastVote
import io.ntole.wyr.core.domain.vote.VoteRepository
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.TokenStorage
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.api.AuthApi
import io.ntole.wyr.core.network.api.QuestionApi
import io.ntole.wyr.core.network.api.VoteApi
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
        single<HttpClient> { WyrHttpClient.create(baseUrl = baseUrl, sessionStore = get()) }

        single { AuthApi(get()) }
        single { QuestionApi(get()) }
        single { VoteApi(get()) }

        single<QuestionCache> { InMemoryQuestionCache() }

        // Bound as the concrete type as well: DefaultVoteRepository needs reset(), which is
        // recovery machinery and deliberately not on the domain interface.
        single { DefaultSessionRepository(authApi = get(), sessionStore = get()) }
        single<SessionRepository> { get<DefaultSessionRepository>() }

        single<QuestionRepository> { DefaultQuestionRepository(api = get(), cache = get()) }
        single<VoteRepository> { DefaultVoteRepository(api = get(), session = get()) }

        factory { GetNextQuestion(questions = get()) }
        factory { CastVote(votes = get(), session = get()) }
    }
