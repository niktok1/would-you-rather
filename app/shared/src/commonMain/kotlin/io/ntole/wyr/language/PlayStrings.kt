package io.ntole.wyr.language

/**
 * The Play screen's words (CLAUDE.md §8d, *The Play screen*; §8f), a part of [Strings] of their own
 * so the screen's texts stand together. Short, the user asking for less text: a failure is one
 * sentence, and the rest are names a screen reader says for an icon or for what a tap does.
 */
data class PlayStrings(
    /** The categories played when none is picked, which is every category. */
    val allCategories: String,
    /** What a tap on the categories played does, for a screen reader: opens the category picker. */
    val changeCategories: String,
    /** What a tap on either card does once the answer is revealed, for a screen reader. */
    val nextQuestion: String,
    /** The heart's name, for a screen reader: whether the player likes the question. */
    val like: String,
    /** The skip icon's name, for a screen reader: past the question without answering it. */
    val skip: String,
    /** The spinner's name, for a screen reader, while a question loads. */
    val loading: String,
    /**
     * No answer from the server, worded for both of the reasons it can have: the phone is offline,
     * or the server is down or too slow to answer.
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
    /** The button under a failure. */
    val tryAgain: String,
) {
    /** A side's share of the answers, as the reveal shows it: *70%*. */
    fun percent(value: Int): String = "$value%"

    /** These strings with [transform] applied to every one of them, as [Strings.map] does. */
    internal fun map(transform: (String) -> String): PlayStrings =
        PlayStrings(
            allCategories = transform(allCategories),
            changeCategories = transform(changeCategories),
            nextQuestion = transform(nextQuestion),
            like = transform(like),
            skip = transform(skip),
            loading = transform(loading),
            cannotReach = transform(cannotReach),
            outOfQuestions = transform(outOfQuestions),
            slowDown = transform(slowDown),
            questionGone = transform(questionGone),
            somethingWrong = transform(somethingWrong),
            tryAgain = transform(tryAgain),
        )
}

/** The source text, written by hand; Serbian Latin is made from it with the rest of [Strings]. */
internal val SerbianCyrillicPlayStrings: PlayStrings =
    PlayStrings(
        allCategories = "Све",
        changeCategories = "Промени категорије",
        nextQuestion = "Следеће питање",
        like = "Свиђа ми се",
        skip = "Прескочи",
        loading = "Учитавање",
        cannotReach = "Игра није доступна.",
        outOfQuestions = "Нема више питања.",
        slowDown = "Сачекај мало.",
        questionGone = "Тог питања више нема.",
        somethingWrong = "Нешто није успело.",
        tryAgain = "Пробај опет",
    )

internal val EnglishPlayStrings: PlayStrings =
    PlayStrings(
        allCategories = "All",
        changeCategories = "Change categories",
        nextQuestion = "Next question",
        like = "Like",
        skip = "Skip",
        loading = "Loading",
        cannotReach = "Can't reach the game.",
        outOfQuestions = "No more questions.",
        slowDown = "Wait a moment.",
        questionGone = "That question is gone.",
        somethingWrong = "Something went wrong.",
        tryAgain = "Try again",
    )
