package io.ntole.wyr.core.domain.analytics

/** The name of every property an [AnalyticsEvent] carries of its own (CLAUDE.md §8g). */
public object AnalyticsProperty {
    /** A screen's name, as the navigator keeps it: `home`, `play`, `account`, `auth`, `submit` or `categories`. */
    public const val SCREEN: String = "screen"

    /** How long something lasted, in whole milliseconds. */
    public const val DURATION_MS: String = "duration_ms"

    /** What was tapped: a stable name, never the text it shows, which changes with the language. */
    public const val ELEMENT: String = "element"

    /** A question's id, never its text. */
    public const val QUESTION_ID: String = "question_id"

    /** Category ids: those a question is filed under, or those played. */
    public const val CATEGORIES: String = "categories"

    /** One category's id: the one tapped, say. */
    public const val CATEGORY: String = "category"

    /** How many of something: categories played, say. */
    public const val COUNT: String = "count"

    /** The side answered, or the Home screen's Play button tapped, in that card's colour: `A` or `B`. */
    public const val SIDE: String = "side"

    /** From the question shown to the tap that answered it, in whole milliseconds. */
    public const val ANSWER_MS: String = "answer_ms"

    /** Whether the side answered is the one more players picked, a tie included. */
    public const val AGREED_WITH_MAJORITY: String = "agreed_with_majority"

    /** Whether the server heard of a skip; one that failed moves on all the same. */
    public const val RECORDED: String = "recorded"

    /** A reaction: `like`, `dislike` or `none`. */
    public const val REACTION: String = "reaction"

    /** Why a question was reported: `offensive`, `real_person`, `spam`, `not_a_choice` or `other`. */
    public const val REASON: String = "reason"

    /** Whether the question was answered already. */
    public const val ANSWERED: String = "answered"

    /** Whether a shared question's image showed its results. */
    public const val WITH_RESULTS: String = "with_results"

    /** What sharing did: `opened` a share sheet, `copied` to the clipboard or `saved` the image. */
    public const val OUTCOME: String = "outcome"

    /** A failure's code, the domain's name for it (`NETWORK`, `USERNAME_TAKEN`...). */
    public const val CODE: String = "code"

    /**
     * What failed: `question`, `vote`, `reaction`, `report`, `hide_question`, `hide_author`,
     * `account`, `my_questions`, `register`, `log_in`, `play_games`, `log_out`, `delete_account`,
     * `submit`, `points` or `categories`.
     */
    public const val ACTION: String = "action"

    /** Whether the app came from the background rather than being launched. */
    public const val FROM_BACKGROUND: String = "from_background"

    /** Whether something happened by itself, with no tap: a Play Games sign-in at launch. */
    public const val AUTOMATIC: String = "automatic"

    /** Whether a sign-in made this device another player's, the one before left behind. */
    public const val SWITCHED: String = "switched"

    /** A language's tag: `sr-Cyrl`, `sr-Latn` or `en`. */
    public const val LANGUAGE: String = "language"
}
