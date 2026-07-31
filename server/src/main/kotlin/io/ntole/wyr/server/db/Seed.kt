package io.ntole.wyr.server.db

import io.ntole.wyr.core.question.QuestionCategory
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Starter question set, so a fresh database is playable immediately.
 *
 * Player-submitted questions will land through the submit-question screen; this is only the
 * bootstrap content.
 */
object Seed {
    fun questionsIfEmpty() {
        if (Questions.selectAll().limit(1).any()) return

        val now = System.currentTimeMillis()

        Questions.batchInsert(STARTERS.withIndex().toList()) { (index, starter) ->
            val ordinal = index + 1
            this[Questions.id] = "seed-$ordinal"
            this[Questions.seq] = ordinal.toLong()
            this[Questions.optionA] = starter.optionA
            this[Questions.optionB] = starter.optionB
            this[Questions.category] = starter.category.name
            this[Questions.createdAt] = now
        }
    }

    private data class Starter(
        val category: QuestionCategory,
        val optionA: String,
        val optionB: String,
    )

    private val STARTERS =
        listOf(
            Starter(QuestionCategory.FOOD, "Only ever eat pizza again", "Only ever eat sushi again"),
            Starter(QuestionCategory.FOOD, "Give up coffee forever", "Give up chocolate forever"),
            Starter(QuestionCategory.FOOD, "Always slightly too salty food", "Always slightly bland food"),
            Starter(QuestionCategory.LIFESTYLE, "Work four long days", "Work five short days"),
            Starter(QuestionCategory.LIFESTYLE, "Live without music", "Live without films"),
            Starter(QuestionCategory.LIFESTYLE, "Never be late again", "Never be tired again"),
            Starter(QuestionCategory.LIFESTYLE, "Move to a new city every year", "Never leave your home town"),
            Starter(QuestionCategory.ETHICS, "Always tell the truth", "Always be told the truth"),
            Starter(QuestionCategory.ETHICS, "Know when anyone lies to you", "Have everyone believe your lies"),
            Starter(QuestionCategory.ETHICS, "Save one friend", "Save five strangers"),
            Starter(QuestionCategory.SUPERPOWERS, "Be able to fly", "Be able to turn invisible"),
            Starter(QuestionCategory.SUPERPOWERS, "Read minds", "See one week into the future"),
            Starter(QuestionCategory.SUPERPOWERS, "Teleport anywhere instantly", "Pause time for an hour a day"),
            Starter(QuestionCategory.SUPERPOWERS, "Never need sleep", "Never need to eat"),
            Starter(QuestionCategory.RANDOM, "Fight one horse-sized duck", "Fight a hundred duck-sized horses"),
            Starter(QuestionCategory.RANDOM, "Have fingers as long as legs", "Have legs as short as fingers"),
            Starter(QuestionCategory.RANDOM, "Always speak in rhyme", "Only ever whisper"),
            Starter(QuestionCategory.RANDOM, "Live in permanent summer", "Live in permanent winter"),
            Starter(QuestionCategory.LIFESTYLE, "Lose all your photos", "Lose all your messages"),
            Starter(QuestionCategory.ETHICS, "Be forgotten after you die", "Be remembered wrongly"),
            Starter(QuestionCategory.FOOD, "Eat only hot food", "Eat only cold food"),
            Starter(QuestionCategory.SUPERPOWERS, "Talk to animals", "Speak every human language"),
            Starter(QuestionCategory.RANDOM, "Have a permanent unexplained limp", "Have a permanent unexplained cough"),
            Starter(QuestionCategory.LIFESTYLE, "Win the lottery tomorrow", "Live twenty years longer"),
        )
}
