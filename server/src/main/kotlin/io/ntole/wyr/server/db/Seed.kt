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

        Questions.batchInsert(SEEDS) { (id, starter) ->
            this[Questions.id] = id
            this[Questions.optionA] = starter.optionA
            this[Questions.optionB] = starter.optionB
            this[Questions.authorPlayerId] = null
            this[Questions.status] = QuestionStatus.APPROVED
            this[Questions.submittedAt] = now
            this[Questions.baseVotesA] = starter.votes.first
            this[Questions.baseVotesB] = starter.votes.second
        }

        val filings = SEEDS.flatMap { (id, starter) -> starter.categories.map { category -> id to category } }
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

    /**
     * A seed: its options, its categories, and the made-up votes it starts with, [votes] for A then B,
     * so its split looks like a crowd's from the first answer (CLAUDE.md §8d, *Seeds*).
     */
    internal data class Starter(
        val category: String,
        val optionA: String,
        val optionB: String,
        val alsoIn: String? = null,
        val votes: Pair<Int, Int>,
    ) {
        /** In the order of [CATEGORIES], as every list of a question's categories is. */
        val categories: List<String> get() = CATEGORIES.map { it.id }.filter { it == category || it == alsoIn }
    }

    /**
     * Every seed by its id, `seed-1` first: the ids every build has given them, which V8 and V9 find
     * them by in a database seeded before.
     */
    internal val SEEDS: List<Pair<String, Starter>> by lazy {
        STARTERS.mapIndexed { index, starter -> "seed-${index + 1}" to starter }
    }

    private val STARTERS =
        listOf(
            Starter(FOOD, "Only ever eat pizza again", "Only ever eat sushi again", votes = 212 to 158),
            Starter(
                FOOD,
                "Give up coffee forever",
                "Give up chocolate forever",
                alsoIn = LIFESTYLE,
                votes = 97 to 143,
            ),
            Starter(FOOD, "Always slightly too salty food", "Always slightly bland food", votes = 188 to 61),
            Starter(LIFESTYLE, "Work four long days", "Work five short days", votes = 264 to 119),
            Starter(LIFESTYLE, "Live without music", "Live without films", votes = 52 to 301),
            Starter(LIFESTYLE, "Never be late again", "Never be tired again", votes = 77 to 246),
            Starter(LIFESTYLE, "Move to a new city every year", "Never leave your home town", votes = 134 to 171),
            Starter(
                ETHICS,
                "Always tell the truth",
                "Always be told the truth",
                alsoIn = LIFESTYLE,
                votes = 156 to 139,
            ),
            Starter(
                ETHICS,
                "Know when anyone lies to you",
                "Have everyone believe your lies",
                alsoIn = SUPERPOWERS,
                votes = 283 to 88,
            ),
            Starter(ETHICS, "Save one friend", "Save five strangers", votes = 201 to 176),
            Starter(SUPERPOWERS, "Be able to fly", "Be able to turn invisible", votes = 318 to 205),
            Starter(SUPERPOWERS, "Read minds", "See one week into the future", votes = 143 to 231),
            Starter(SUPERPOWERS, "Teleport anywhere instantly", "Pause time for an hour a day", votes = 252 to 190),
            Starter(
                SUPERPOWERS,
                "Never need sleep",
                "Never need to eat",
                alsoIn = FOOD,
                votes = 219 to 97,
            ),
            Starter(ABSURD, "Fight one horse-sized duck", "Fight a hundred duck-sized horses", votes = 167 to 274),
            Starter(ABSURD, "Have fingers as long as legs", "Have legs as short as fingers", votes = 58 to 73),
            Starter(ABSURD, "Always speak in rhyme", "Only ever whisper", votes = 121 to 94),
            Starter(
                ABSURD,
                "Live in permanent summer",
                "Live in permanent winter",
                alsoIn = LIFESTYLE,
                votes = 239 to 82,
            ),
            Starter(LIFESTYLE, "Lose all your photos", "Lose all your messages", votes = 108 to 196),
            Starter(ETHICS, "Be forgotten after you die", "Be remembered wrongly", votes = 149 to 131),
            Starter(FOOD, "Eat only hot food", "Eat only cold food", votes = 176 to 68),
            Starter(SUPERPOWERS, "Talk to animals", "Speak every human language", votes = 162 to 245),
            Starter(
                ABSURD,
                "Have a permanent unexplained limp",
                "Have a permanent unexplained cough",
                votes = 44 to 61,
            ),
            Starter(LIFESTYLE, "Win the lottery tomorrow", "Live twenty years longer", votes = 186 to 233),
        )
}
