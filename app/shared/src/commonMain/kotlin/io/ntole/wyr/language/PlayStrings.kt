package io.ntole.wyr.language

/**
 * The Play screen's words (CLAUDE.md §8d, *The Play screen*; §8f), a part of [Strings] of their own
 * so the screen's texts stand together. Short, the user asking for less text: a failure is one
 * sentence, and the rest are names a screen reader says for an icon or for what a tap does. The
 * categories' own names are the server's ([categoryName]), and the Categories screen's words are
 * [CategoryStrings]. Every category and the spinner's name are the game's, which the Categories
 * screen says too: [Strings.allCategories] and [Strings.loading].
 */
data class PlayStrings(
    /** What a tap on the categories played does, for a screen reader: opens the Categories screen. */
    val changeCategories: String,
    /** What a tap on either card does once the answer is revealed, for a screen reader. */
    val nextQuestion: String,
    /** The thumb up's name, for a screen reader: whether the player likes the question. */
    val like: String,
    /** The thumb down's name, for a screen reader: whether the player dislikes the question. */
    val dislike: String,
    /** The skip icon's name, for a screen reader: past the question without answering it. */
    val skip: String,
    /**
     * No answer from the server, worded for both of the reasons it can have: the phone is offline,
     * or the server is down or too slow to answer. The Categories screen, opened from this one, says
     * it too.
     */
    val cannotReach: String,
    /** Nothing to serve, in the categories played or at all. */
    val outOfQuestions: String,
    /** Rate limited. */
    val slowDown: String,
    /** The question is no longer in the game. */
    val questionGone: String,
    /** Anything else. */
    val somethingWrong: String,
    /** Above the cards, quietly, when the player has answered the question on screen before. */
    val answeredBefore: String,
    /** The menu about the question on screen, on the top bar. */
    val menu: QuestionMenuStrings,
    /** Sharing the question on screen, or one of the player's own (CLAUDE.md §8d, *Sharing*). */
    val share: ShareStrings,
) {
    /** A side's share of the answers, as the reveal shows it: *70%*. */
    fun percent(value: Int): String = "$value%"

    /** These strings with [transform] applied to every one of them, as [Strings.map] does. */
    internal fun map(transform: (String) -> String): PlayStrings =
        PlayStrings(
            changeCategories = transform(changeCategories),
            nextQuestion = transform(nextQuestion),
            like = transform(like),
            dislike = transform(dislike),
            skip = transform(skip),
            cannotReach = transform(cannotReach),
            outOfQuestions = transform(outOfQuestions),
            slowDown = transform(slowDown),
            questionGone = transform(questionGone),
            somethingWrong = transform(somethingWrong),
            answeredBefore = transform(answeredBefore),
            menu = menu.map(transform),
            share = share.map(transform),
        )
}

/** The source text, written by hand; Serbian Latin is made from it with the rest of [Strings]. */
internal val SerbianCyrillicPlayStrings: PlayStrings =
    PlayStrings(
        changeCategories = "Промени категорије",
        nextQuestion = "Следеће питање",
        like = "Свиђа ми се",
        dislike = "Не свиђа ми се",
        skip = "Прескочи",
        cannotReach = "Игра није доступна.",
        outOfQuestions = "Нема више питања.",
        slowDown = "Сачекај мало.",
        questionGone = "Тог питања више нема.",
        somethingWrong = "Нешто није успело.",
        answeredBefore = "Већ одговорено",
        menu = SerbianCyrillicQuestionMenuStrings,
        share = SerbianCyrillicShareStrings,
    )

internal val EnglishPlayStrings: PlayStrings =
    PlayStrings(
        changeCategories = "Change categories",
        nextQuestion = "Next question",
        like = "Like",
        dislike = "Dislike",
        skip = "Skip",
        cannotReach = "Can't reach the game.",
        outOfQuestions = "No more questions.",
        slowDown = "Wait a moment.",
        questionGone = "That question is gone.",
        somethingWrong = "Something went wrong.",
        answeredBefore = "Answered before",
        menu = EnglishQuestionMenuStrings,
        share = EnglishShareStrings,
    )
