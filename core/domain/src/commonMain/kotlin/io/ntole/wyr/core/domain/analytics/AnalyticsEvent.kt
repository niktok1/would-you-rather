package io.ntole.wyr.core.domain.analytics

/**
 * The name of every event the game reports (CLAUDE.md §8g), in one place, so a dashboard built on one
 * keeps working and what is sent is listed here. A name never changes once it is sent: a dashboard in
 * the service would lose it. The properties each carries are [AnalyticsProperty]'s.
 */
public object AnalyticsEvent {
    /** The app came to the foreground: at launch, and from the background ([AnalyticsProperty.FROM_BACKGROUND]). */
    public const val APP_OPENED: String = "app_opened"

    /** The app went to the background, after [AnalyticsProperty.DURATION_MS] in the foreground. */
    public const val APP_BACKGROUNDED: String = "app_backgrounded"

    /**
     * A screen was left, [AnalyticsProperty.SCREEN], after [AnalyticsProperty.DURATION_MS] on it: for
     * another screen, or for the background. A screen shown is the service's own `$screen`.
     */
    public const val SCREEN_LEFT: String = "screen_left"

    /** A button, a card or anything else tapped, named by [AnalyticsProperty.ELEMENT]. */
    public const val TAP: String = "tap"

    /** A question was asked on the Play screen. */
    public const val QUESTION_SHOWN: String = "question_shown"

    /** A question was answered, and the answer counted: [AnalyticsProperty.SIDE], [AnalyticsProperty.ANSWER_MS]. */
    public const val QUESTION_ANSWERED: String = "question_answered"

    /** A question was skipped, [AnalyticsProperty.RECORDED] whether the server heard of it. */
    public const val QUESTION_SKIPPED: String = "question_skipped"

    /** A like, a dislike or neither, [AnalyticsProperty.REACTION], was set on a question. */
    public const val REACTION_SET: String = "reaction_set"

    /** A question was reported to the moderator, for [AnalyticsProperty.REASON], from the Play screen's menu. */
    public const val QUESTION_REPORTED: String = "question_reported"

    /** A question was hidden from the player, from the Play screen's menu. */
    public const val QUESTION_HIDDEN: String = "question_hidden"

    /** A question's author was hidden from the player, from the Play screen's menu. */
    public const val AUTHOR_HIDDEN: String = "author_hidden"

    /** Categories were played from the Categories screen, none being every category. */
    public const val CATEGORIES_CHANGED: String = "categories_changed"

    /** The Account screen was shown. */
    public const val ACCOUNT_OPENED: String = "account_opened"

    /** A registration was sent. */
    public const val REGISTER_STARTED: String = "register_started"

    /** A registration worked: the player is an account's now. */
    public const val REGISTER_COMPLETED: String = "register_completed"

    /** A login worked. */
    public const val LOGIN_COMPLETED: String = "login_completed"

    /**
     * The player signed in with Google Play Games Services (CLAUDE.md §8a, *Play Games sign-in*):
     * [AnalyticsProperty.AUTOMATIC] whether at launch with no tap, and [AnalyticsProperty.SWITCHED]
     * whether it made this device another player's.
     */
    public const val PLAY_GAMES_SIGNED_IN: String = "play_games_signed_in"

    /** The player tapped a notification of a moderator's decision, which opens the Account screen. */
    public const val NOTIFICATION_OPENED: String = "notification_opened"

    /** The player logged out, and plays on as a fresh guest. */
    public const val LOGOUT: String = "logout"

    /** The player deleted their account, and plays on as a fresh guest. */
    public const val ACCOUNT_DELETED: String = "account_deleted"

    /** The Submit screen's form was shown. */
    public const val SUBMIT_OPENED: String = "submit_opened"

    /** A question was submitted, and stored for a moderator. */
    public const val SUBMIT_SENT: String = "submit_sent"

    /** A question submitted was refused, [AnalyticsProperty.CODE] saying why. */
    public const val SUBMIT_REFUSED: String = "submit_refused"

    /** The game showed the player a failure, [AnalyticsProperty.CODE], of [AnalyticsProperty.ACTION]. */
    public const val ERROR_SHOWN: String = "error_shown"

    /** The player picked a language to play in, [AnalyticsProperty.LANGUAGE]. */
    public const val LANGUAGE_CHANGED: String = "language_changed"
}
