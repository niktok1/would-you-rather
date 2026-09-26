package io.ntole.wyr.core.report

import kotlinx.serialization.Serializable

/**
 * Why a player reports a question (CLAUDE.md §8d, *Reports*), which the moderator sees counted per
 * reason.
 *
 * A growable wire enum (CLAUDE.md §5): [UNKNOWN] is what a reason added server-side later decodes as
 * on an older client, so every property of this type declares it as its default, and the client's
 * `Json` coerces into it. Never stored, and a report that gives it is refused.
 */
@Serializable
public enum class ReportReason {
    /** Hateful, sexual, violent or otherwise not fit for the game. */
    OFFENSIVE,

    /** It names or targets a real, private person. */
    REAL_PERSON,

    /** An advert, a link, or the same question over and over. */
    SPAM,

    /** Not a choice between two options: nothing to pick between, or one side the same as the other. */
    NOT_A_CHOICE,

    /** Anything else. */
    OTHER,

    UNKNOWN,
}
