package io.ntole.wyr.core.domain.moderation

/**
 * Why a player reported a question, as the moderator sees each counted (CLAUDE.md §8d, *Reports*).
 *
 * [UNKNOWN] is where a reason this build cannot name lands: one added server-side later arrives as the
 * wire's own unknown and maps here, so the list of reported questions still loads. It is not [OTHER],
 * which is a reason a player gave, *anything else*.
 */
public enum class ReportReason {
    /** Hateful, sexual, violent or otherwise not fit for the game. */
    OFFENSIVE,

    /** It names or targets a real, private person. */
    REAL_PERSON,

    /** An advert, a link, or the same question over and over. */
    SPAM,

    /** Not a choice between two options. */
    NOT_A_CHOICE,

    /** Anything else, the player's own words. */
    OTHER,

    UNKNOWN,
}
