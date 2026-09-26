package io.ntole.wyr.language

import io.ntole.wyr.core.domain.report.ReportReason

/**
 * The words of the Play screen's menu about the question on screen (CLAUDE.md §8d, *The Play screen*,
 * *Reports*; §8f), a part of [PlayStrings] of their own: the menu's name for a screen reader, its three
 * choices, and the five reasons a report may give, each a few words.
 */
data class QuestionMenuStrings(
    /** The menu's icon's name, for a screen reader. */
    val name: String,
    /** Report the question to the moderator, which then asks why. */
    val report: String,
    /** Never show the player this question again. */
    val hideQuestion: String,
    /** Never show the player this question's author's questions again. */
    val hideAuthor: String,
    /** [ReportReason.OFFENSIVE]. */
    val offensive: String,
    /** [ReportReason.REAL_PERSON]. */
    val realPerson: String,
    /** [ReportReason.SPAM]. */
    val spam: String,
    /** [ReportReason.NOT_A_CHOICE]. */
    val notAChoice: String,
    /** [ReportReason.OTHER]. */
    val other: String,
) {
    /** [reason] in these words. */
    fun reason(reason: ReportReason): String =
        when (reason) {
            ReportReason.OFFENSIVE -> offensive
            ReportReason.REAL_PERSON -> realPerson
            ReportReason.SPAM -> spam
            ReportReason.NOT_A_CHOICE -> notAChoice
            ReportReason.OTHER -> other
        }

    /** These strings with [transform] applied to every one of them, as [Strings.map] does. */
    internal fun map(transform: (String) -> String): QuestionMenuStrings =
        QuestionMenuStrings(
            name = transform(name),
            report = transform(report),
            hideQuestion = transform(hideQuestion),
            hideAuthor = transform(hideAuthor),
            offensive = transform(offensive),
            realPerson = transform(realPerson),
            spam = transform(spam),
            notAChoice = transform(notAChoice),
            other = transform(other),
        )
}

/** The source text, written by hand; Serbian Latin is made from it with the rest of [Strings]. */
internal val SerbianCyrillicQuestionMenuStrings: QuestionMenuStrings =
    QuestionMenuStrings(
        name = "Опције питања",
        report = "Пријави питање",
        hideQuestion = "Не приказуј ми ово питање",
        hideAuthor = "Не приказуј питања овог аутора",
        offensive = "Увредљиво је",
        realPerson = "Помиње стварну особу",
        spam = "Реклама или спам",
        notAChoice = "Нема шта да се бира",
        other = "Нешто друго",
    )

internal val EnglishQuestionMenuStrings: QuestionMenuStrings =
    QuestionMenuStrings(
        name = "Question options",
        report = "Report question",
        hideQuestion = "Don't show me this question",
        hideAuthor = "Don't show this author's questions",
        offensive = "It's offensive",
        realPerson = "It's about a real person",
        spam = "Advert or spam",
        notAChoice = "Nothing to choose between",
        other = "Something else",
    )
