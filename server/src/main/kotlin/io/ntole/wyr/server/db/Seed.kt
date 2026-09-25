package io.ntole.wyr.server.db

import io.ntole.wyr.core.question.QuestionStatus
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select

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
     *
     * It never changes a question already there, so a seed a moderator retired stays retired through
     * every boot, and is never written again. The check names only the id.
     *
     * The seeds are filed under [CATEGORIES], which V6 wrote into every migrated database, so a boot
     * finds them there. A database the store tests build from the definitions (`SchemaUtils.create`)
     * has no category at all, and gets them here first, as V6 would have written them.
     */
    fun questionsIfEmpty() {
        categoriesIfNone()
        if (Questions.select(Questions.id).limit(1).any()) return

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
            this[QuestionCategories.category] = category
        }
    }

    /** Writes [CATEGORIES] into a database with no category, as V6 writes them into every other. */
    private fun categoriesIfNone() {
        if (Categories.select(Categories.id).limit(1).any()) return

        Categories.batchInsert(CATEGORIES) { category ->
            this[Categories.id] = category.id
            this[Categories.nameSr] = category.nameSr
            this[Categories.nameEn] = category.nameEn
            this[Categories.createdAt] = category.createdAt
        }
    }

    /** A category as V6 wrote it. */
    internal data class StartingCategory(
        val id: String,
        val nameSr: String,
        val nameEn: String,
        val createdAt: Long,
    )

    /** When V6 says the first categories were added: 2026-09-25, a millisecond apart. */
    private const val FIRST_CATEGORY_AT = 1_790_294_400_000L

    const val FOOD: String = "FOOD"
    const val LIFESTYLE: String = "LIFESTYLE"
    const val ETHICS: String = "ETHICS"
    const val SUPERPOWERS: String = "SUPERPOWERS"
    const val ABSURD: String = "ABSURD"

    /**
     * The categories V6 writes, exactly: the four the wire's enum named that stay, under the same ids,
     * in its declaration order, then [ABSURD], which took RANDOM's questions (CLAUDE.md §8d,
     * *Categories*). MigrationsTest holds V6 to this list.
     */
    internal val CATEGORIES: List<StartingCategory> =
        listOf(
            StartingCategory(FOOD, "Храна", "Food", FIRST_CATEGORY_AT),
            StartingCategory(LIFESTYLE, "Начин живота", "Lifestyle", FIRST_CATEGORY_AT + 1),
            StartingCategory(ETHICS, "Етика", "Ethics", FIRST_CATEGORY_AT + 2),
            StartingCategory(SUPERPOWERS, "Супермоћи", "Superpowers", FIRST_CATEGORY_AT + 3),
            StartingCategory(ABSURD, "Апсурдно", "Absurd", FIRST_CATEGORY_AT + 4),
        )

    private data class Starter(
        val category: String,
        val optionA: String,
        val optionB: String,
        val alsoIn: String? = null,
    ) {
        /** In the order of [CATEGORIES], as every list of a question's categories is. */
        val categories: List<String> get() = CATEGORIES.map { it.id }.filter { it == category || it == alsoIn }
    }

    private val STARTERS =
        listOf(
            Starter(FOOD, "Only ever eat pizza again", "Only ever eat sushi again"),
            Starter(
                FOOD,
                "Give up coffee forever",
                "Give up chocolate forever",
                alsoIn = LIFESTYLE,
            ),
            Starter(FOOD, "Always slightly too salty food", "Always slightly bland food"),
            Starter(LIFESTYLE, "Work four long days", "Work five short days"),
            Starter(LIFESTYLE, "Live without music", "Live without films"),
            Starter(LIFESTYLE, "Never be late again", "Never be tired again"),
            Starter(LIFESTYLE, "Move to a new city every year", "Never leave your home town"),
            Starter(
                ETHICS,
                "Always tell the truth",
                "Always be told the truth",
                alsoIn = LIFESTYLE,
            ),
            Starter(
                ETHICS,
                "Know when anyone lies to you",
                "Have everyone believe your lies",
                alsoIn = SUPERPOWERS,
            ),
            Starter(ETHICS, "Save one friend", "Save five strangers"),
            Starter(SUPERPOWERS, "Be able to fly", "Be able to turn invisible"),
            Starter(SUPERPOWERS, "Read minds", "See one week into the future"),
            Starter(SUPERPOWERS, "Teleport anywhere instantly", "Pause time for an hour a day"),
            Starter(
                SUPERPOWERS,
                "Never need sleep",
                "Never need to eat",
                alsoIn = FOOD,
            ),
            Starter(ABSURD, "Fight one horse-sized duck", "Fight a hundred duck-sized horses"),
            Starter(ABSURD, "Have fingers as long as legs", "Have legs as short as fingers"),
            Starter(ABSURD, "Always speak in rhyme", "Only ever whisper"),
            Starter(
                ABSURD,
                "Live in permanent summer",
                "Live in permanent winter",
                alsoIn = LIFESTYLE,
            ),
            Starter(LIFESTYLE, "Lose all your photos", "Lose all your messages"),
            Starter(ETHICS, "Be forgotten after you die", "Be remembered wrongly"),
            Starter(FOOD, "Eat only hot food", "Eat only cold food"),
            Starter(SUPERPOWERS, "Talk to animals", "Speak every human language"),
            Starter(ABSURD, "Have a permanent unexplained limp", "Have a permanent unexplained cough"),
            Starter(LIFESTYLE, "Win the lottery tomorrow", "Live twenty years longer"),
        )
}
