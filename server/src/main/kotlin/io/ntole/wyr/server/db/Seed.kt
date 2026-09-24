package io.ntole.wyr.server.db

import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionStatus
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Starter question set, so a fresh database is playable immediately.
 *
 * Players submit questions of their own, which wait for a moderator (CLAUDE.md §8d); this is only
 * the bootstrap content. A seed has no author and is approved from the start, so every player is
 * served it. A few are filed under a second category, so a question in several is there to play
 * from the start.
 */
object Seed {
    /**
     * A check then an insert, and deliberately not a compare-and-set (CLAUDE.md §4): the primary
     * key already makes a second copy of the seeds impossible. Two servers seeding an empty
     * database at once, as two boots do once Flyway lets them past the migration, both find it
     * empty and both insert. The second's insert waits on the first's uncommitted keys and fails on
     * the primary key once they commit. Exposed then rolls back and re-runs the seed's transaction
     * (3 attempts by default), and this check now sees the committed seeds and returns, so both
     * servers boot. That recovery is Exposed's retry, not anything here. SeedTest stages it on H2;
     * on PostgreSQL only MigrationsTest's boots at once reach it, and only when they happen to
     * collide.
     */
    fun questionsIfEmpty() {
        if (Questions.selectAll().limit(1).any()) return

        val now = System.currentTimeMillis()

        val seeds = STARTERS.mapIndexed { index, starter -> "seed-${index + 1}" to starter }

        Questions.batchInsert(seeds) { (id, starter) ->
            this[Questions.id] = id
            this[Questions.optionA] = starter.optionA
            this[Questions.optionB] = starter.optionB
            this[Questions.authorPlayerId] = null
            this[Questions.status] = QuestionStatus.APPROVED
            this[Questions.submittedAt] = now
        }

        val filings = seeds.flatMap { (id, starter) -> starter.categories.map { category -> id to category } }
        QuestionCategories.batchInsert(filings) { (id, category) ->
            this[QuestionCategories.questionId] = id
            this[QuestionCategories.category] = category.name
        }
    }

    private data class Starter(
        val category: QuestionCategory,
        val optionA: String,
        val optionB: String,
        val alsoIn: QuestionCategory? = null,
    ) {
        val categories: List<QuestionCategory> get() = listOfNotNull(category, alsoIn)
    }

    private val STARTERS =
        listOf(
            Starter(QuestionCategory.FOOD, "Only ever eat pizza again", "Only ever eat sushi again"),
            Starter(
                QuestionCategory.FOOD,
                "Give up coffee forever",
                "Give up chocolate forever",
                alsoIn = QuestionCategory.LIFESTYLE,
            ),
            Starter(QuestionCategory.FOOD, "Always slightly too salty food", "Always slightly bland food"),
            Starter(QuestionCategory.LIFESTYLE, "Work four long days", "Work five short days"),
            Starter(QuestionCategory.LIFESTYLE, "Live without music", "Live without films"),
            Starter(QuestionCategory.LIFESTYLE, "Never be late again", "Never be tired again"),
            Starter(QuestionCategory.LIFESTYLE, "Move to a new city every year", "Never leave your home town"),
            Starter(
                QuestionCategory.ETHICS,
                "Always tell the truth",
                "Always be told the truth",
                alsoIn = QuestionCategory.LIFESTYLE,
            ),
            Starter(
                QuestionCategory.ETHICS,
                "Know when anyone lies to you",
                "Have everyone believe your lies",
                alsoIn = QuestionCategory.SUPERPOWERS,
            ),
            Starter(QuestionCategory.ETHICS, "Save one friend", "Save five strangers"),
            Starter(QuestionCategory.SUPERPOWERS, "Be able to fly", "Be able to turn invisible"),
            Starter(QuestionCategory.SUPERPOWERS, "Read minds", "See one week into the future"),
            Starter(QuestionCategory.SUPERPOWERS, "Teleport anywhere instantly", "Pause time for an hour a day"),
            Starter(
                QuestionCategory.SUPERPOWERS,
                "Never need sleep",
                "Never need to eat",
                alsoIn = QuestionCategory.FOOD,
            ),
            Starter(QuestionCategory.RANDOM, "Fight one horse-sized duck", "Fight a hundred duck-sized horses"),
            Starter(QuestionCategory.RANDOM, "Have fingers as long as legs", "Have legs as short as fingers"),
            Starter(QuestionCategory.RANDOM, "Always speak in rhyme", "Only ever whisper"),
            Starter(
                QuestionCategory.RANDOM,
                "Live in permanent summer",
                "Live in permanent winter",
                alsoIn = QuestionCategory.LIFESTYLE,
            ),
            Starter(QuestionCategory.LIFESTYLE, "Lose all your photos", "Lose all your messages"),
            Starter(QuestionCategory.ETHICS, "Be forgotten after you die", "Be remembered wrongly"),
            Starter(QuestionCategory.FOOD, "Eat only hot food", "Eat only cold food"),
            Starter(QuestionCategory.SUPERPOWERS, "Talk to animals", "Speak every human language"),
            Starter(QuestionCategory.RANDOM, "Have a permanent unexplained limp", "Have a permanent unexplained cough"),
            Starter(QuestionCategory.LIFESTYLE, "Win the lottery tomorrow", "Live twenty years longer"),
        )
}
