package io.ntole.wyr.core.domain.report

/**
 * Why a player reports a question to the moderator (CLAUDE.md §8d, *Reports*), one of the five the
 * Play screen's menu offers.
 *
 * Deliberately not the wire's enum: domain code never sees a DTO (CLAUDE.md §3), and the mapping lives
 * in `:core:data`. A player only ever sends one, so it has none of the wire's `UNKNOWN`, which no
 * report may give.
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

    /** Anything else. */
    OTHER,
}
