package io.ntole.wyr.core.domain.submission

/**
 * The server's rules for a submitted question's options (CLAUDE.md §8d, *Submitting*), so a form can
 * say what is wrong as it is typed, before anything is sent, and what submitting costs.
 *
 * An option is trimmed, then must be non-blank, at most [MAX_OPTION_LENGTH] long, counted as
 * `String.length` counts, in UTF-16 code units, and one line: no control character, nor U+2028 or
 * U+2029, the line and paragraph separators, left anywhere once trimmed. The two options, trimmed,
 * must differ ignoring case. The server's `checkedSubmission` exactly, trimming first as it does; it
 * still rules on what is sent, and refuses what breaks these as `INVALID_SUBMISSION`.
 *
 * The numbers are the server's `WyrApi.Limits`, which this module cannot see (CLAUDE.md §3), so
 * `:core:data`'s tests pin each copy to the wire's.
 */
public object SubmissionRules {
    public const val MAX_OPTION_LENGTH: Int = 200

    /** Most submissions one player may have waiting for a moderator at once. */
    public const val MAX_PENDING_SUBMISSIONS: Int = 20

    /**
     * What submitting a question costs, in points, until the server has said (CLAUDE.md §8c): the
     * wire's default, 1, which is what a server that names no cost charges. The server's own cost comes
     * with the player's stats (`PlayerStats.submissionCost`), and the Submit screen shows that on its
     * button and holds the button off by it while the player has fewer; the server charges it, and
     * refuses too few as `NOT_ENOUGH_POINTS`.
     */
    public const val SUBMISSION_COST: Int = 1

    /** What is wrong with [option] by the server's rules, or null when a question can have it. */
    public fun optionProblem(option: String): OptionProblem? {
        val trimmed = option.trim()
        return when {
            trimmed.isEmpty() -> OptionProblem.BLANK
            trimmed.length > MAX_OPTION_LENGTH -> OptionProblem.TOO_LONG
            trimmed.any { it.isISOControl() || it.category in LINE_SEPARATORS } -> OptionProblem.NOT_ONE_LINE
            else -> null
        }
    }

    /** Whether the server would refuse [optionA] and [optionB] as the same option, trimmed and ignoring case. */
    public fun sameOptions(
        optionA: String,
        optionB: String,
    ): Boolean = optionA.trim().equals(optionB.trim(), ignoreCase = true)

    /** The categories of U+2028 and U+2029, the only characters in either. */
    private val LINE_SEPARATORS = setOf(CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR)
}

/** What [SubmissionRules.optionProblem] finds wrong with an option. */
public enum class OptionProblem {
    /** Nothing but whitespace, or nothing at all. */
    BLANK,

    /** Longer than [SubmissionRules.MAX_OPTION_LENGTH] once trimmed. */
    TOO_LONG,

    /** A line break, a tab or another control character inside it once trimmed. */
    NOT_ONE_LINE,
}
