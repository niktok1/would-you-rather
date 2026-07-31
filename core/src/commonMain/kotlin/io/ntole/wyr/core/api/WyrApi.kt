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

        public const val QUESTIONS: String = "/$VERSION/questions"
        public const val VOTES: String = "/$VERSION/votes"
    }

    public object Query {
        /** Opaque page cursor; omit for the first page. */
        public const val CURSOR: String = "cursor"

        /** Max questions to return in one page. */
        public const val LIMIT: String = "limit"

        /** Optional [io.ntole.wyr.core.question.QuestionCategory] name filter. */
        public const val CATEGORY: String = "category"
    }

    public object Limits {
        public const val DEFAULT_PAGE_SIZE: Int = 20
        public const val MAX_PAGE_SIZE: Int = 100
    }
}
