package io.ntole.wyr.core.api

/**
 * The single definition of every path and parameter name on the wire.
 *
 * Both `:server` (route declarations) and `:core:network` (request building) read these, so a
 * path can never drift between the two sides — which is the whole point of the Kotlin-everywhere
 * rule in CLAUDE.md §2. Never hardcode a route string on either side.
 */
public object WyrApi {
    public const val VERSION: String = "v1"

    public object Paths {
        public const val HEALTH: String = "/health"

        public const val AUTH_GUEST: String = "/$VERSION/auth/guest"
        public const val AUTH_REFRESH: String = "/$VERSION/auth/refresh"

        /**
         * The next batch of questions for the player the bearer token names. Requires a session,
         * because the feed is per player (CLAUDE.md §8d): it runs in cycles, serving each question
         * once per cycle in a new random order, and a batch holds only what the player has not yet
         * answered in the current one. There is no cursor; asking again is how to get the next batch.
         */
        public const val QUESTIONS: String = "/$VERSION/questions"
        public const val VOTES: String = "/$VERSION/votes"
    }

    public object Query {
        /** Max questions to return in one batch. */
        public const val LIMIT: String = "limit"

        /** Optional [io.ntole.wyr.core.question.QuestionCategory] name filter. */
        public const val CATEGORY: String = "category"
    }

    public object Limits {
        public const val DEFAULT_PAGE_SIZE: Int = 20
        public const val MAX_PAGE_SIZE: Int = 100

        /** Longest [io.ntole.wyr.core.vote.VoteRequest.attemptId] the server accepts. A UUID is 36. */
        public const val MAX_ATTEMPT_ID_LENGTH: Int = 64
    }
}
