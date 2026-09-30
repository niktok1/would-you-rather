package io.ntole.wyr.server.db

import io.ntole.wyr.core.question.QuestionStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select

/**
 * Starter question set, so a fresh database is playable immediately.
 *
 * Players submit questions of their own, which wait for a moderator (CLAUDE.md §8d); this is only
 * the bootstrap content. A seed has no author and is approved from the start, so every player is
 * served it. Some are filed under a second category, so a question in several is there to play
 * from the start.
 *
 * The seeds are in Serbian, in Cyrillic (CLAUDE.md §8d, *Seeds*): written for Serbian rather than
 * put word for word from the English they began in. V9 rewrote the English ones a database seeded
 * before holds, by id, into these same texts. The first 24 came first; the rest came on 2026-09-26,
 * with the categories after V6's.
 */
object Seed {
    /**
     * Writes what the database lacks of [seeds], each found by its id: first every category they are
     * filed under, then every seed. A new database gets them all, and one an earlier build seeded gets
     * the seeds added since, at the first boot of the build that added them. Nothing already there is
     * written again or changed: a seed a moderator retired stays retired, a category a moderator
     * renamed keeps its names, and a category a moderator added under a seed category's id stands for
     * it.
     *
     * [seeds] is every seed but in the server tests, which seed only the first ones (`TEST_SEEDS`).
     *
     * A check then an insert, and deliberately not a compare-and-set (CLAUDE.md §4): the primary
     * keys already make a second copy of a seed impossible. Two servers seeding one database at once,
     * as two boots do once Flyway lets them past the migration, both find the same seeds missing and
     * both insert. The second's insert waits on the first's uncommitted keys and fails on the primary
     * key once they commit. Exposed then rolls back and re-runs the seed's transaction (3 attempts by
     * default), and the check now finds the committed seeds and writes nothing, so both servers boot.
     * That recovery is Exposed's retry, not anything here. SeedTest stages it on H2; on PostgreSQL
     * only MigrationsTest's boots at once reach it, and only when they happen to collide.
     *
     * The checks name only the ids. V6 wrote the first categories ([CATEGORIES]) into every migrated
     * database, so a boot finds them there. A database the store tests build from the definitions
     * (`SchemaUtils.create`) has no category at all, and gets them here, as V6 would have written them.
     */
    fun writeMissing(seeds: List<Pair<String, Starter>> = SEEDS) {
        val filedUnder = seeds.flatMap { (_, starter) -> starter.categories }.toSet()
        categoriesMissing(ALL_CATEGORIES.filter { it.id in filedUnder })

        val present =
            Questions
                .select(Questions.id)
                .where { Questions.id inList seeds.map { (id, _) -> id } }
                .map { it[Questions.id] }
                .toSet()
        val missing = seeds.filterNot { (id, _) -> id in present }
        if (missing.isEmpty()) return

        val now = System.currentTimeMillis()

        Questions.batchInsert(missing) { (id, starter) ->
            this[Questions.id] = id
            this[Questions.optionA] = starter.optionA
            this[Questions.optionB] = starter.optionB
            this[Questions.authorPlayerId] = null
            this[Questions.status] = QuestionStatus.APPROVED
            this[Questions.submittedAt] = now
            this[Questions.baseVotesA] = starter.votes.first
            this[Questions.baseVotesB] = starter.votes.second
        }

        val filings = missing.flatMap { (id, starter) -> starter.categories.map { category -> id to category } }
        QuestionCategories.batchInsert(filings) { (id, category) ->
            this[QuestionCategories.questionId] = id
            this[QuestionCategories.category] = category
        }
    }

    /** Writes each of [categories] the database lacks, by id. */
    private fun categoriesMissing(categories: List<StartingCategory>) {
        val present =
            Categories
                .select(Categories.id)
                .where { Categories.id inList categories.map { it.id } }
                .map { it[Categories.id] }
                .toSet()
        val missing = categories.filterNot { it.id in present }
        if (missing.isEmpty()) return

        Categories.batchInsert(missing) { category ->
            this[Categories.id] = category.id
            this[Categories.nameSr] = category.nameSr
            this[Categories.nameEn] = category.nameEn
            this[Categories.createdAt] = category.createdAt
        }
    }

    /** A category as the seed writes it, and as V6 wrote the first ones. */
    internal data class StartingCategory(
        val id: String,
        val nameSr: String,
        val nameEn: String,
        val createdAt: Long,
    )

    /** When V6 says the first categories were added: 2026-09-25, a millisecond apart. */
    private const val FIRST_CATEGORY_AT = 1_790_294_400_000L

    /** When the categories that came with the second seeds were added: 2026-09-26, a millisecond apart. */
    private const val SECOND_CATEGORY_AT = 1_790_380_800_000L

    /** When [MISC] was added, with the seeds filed under it: 2026-09-30. */
    private const val MISC_CATEGORY_AT = 1_790_726_400_000L

    const val FOOD: String = "FOOD"
    const val LIFESTYLE: String = "LIFESTYLE"
    const val ETHICS: String = "ETHICS"
    const val SUPERPOWERS: String = "SUPERPOWERS"
    const val ABSURD: String = "ABSURD"
    const val TRAVEL: String = "TRAVEL"
    const val WORK: String = "WORK"
    const val MONEY: String = "MONEY"
    const val LOVE: String = "LOVE"
    const val TECHNOLOGY: String = "TECHNOLOGY"
    const val SPORTS: String = "SPORTS"
    const val ANIMALS: String = "ANIMALS"
    const val GROSS: String = "GROSS"
    const val MISC: String = "MISC"

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
     * Every category a seed is filed under, in the order of categories: V6's, then those that came
     * with the second seeds, which no migration writes. Each id is its English name's, as V6's are.
     * [writeMissing] writes a category only with a seed filed under it, and SeedTest holds every one
     * of these to having one.
     */
    internal val ALL_CATEGORIES: List<StartingCategory> =
        CATEGORIES +
            listOf(
                StartingCategory(TRAVEL, "Путовања", "Travel", SECOND_CATEGORY_AT),
                StartingCategory(WORK, "Посао", "Work", SECOND_CATEGORY_AT + 1),
                StartingCategory(MONEY, "Новац", "Money", SECOND_CATEGORY_AT + 2),
                StartingCategory(LOVE, "Љубав", "Love", SECOND_CATEGORY_AT + 3),
                StartingCategory(TECHNOLOGY, "Технологија", "Technology", SECOND_CATEGORY_AT + 4),
                StartingCategory(SPORTS, "Спорт", "Sports", SECOND_CATEGORY_AT + 5),
                StartingCategory(ANIMALS, "Животиње", "Animals", SECOND_CATEGORY_AT + 6),
                StartingCategory(GROSS, "Бљак", "Yuck", SECOND_CATEGORY_AT + 7),
                // The one a moderator files a question under when no other fits, and no new category is
                // worth making for it (CLAUDE.md §8d, *Categories*, *Nothing fits*).
                StartingCategory(MISC, "Разно", "Misc", MISC_CATEGORY_AT),
            )

    /**
     * A seed: its options, its categories, and the made-up votes it starts with, [votes] for A then B,
     * so its split looks like a crowd's from the first answer (CLAUDE.md §8d, *Seeds*).
     */
    data class Starter(
        val category: String,
        val optionA: String,
        val optionB: String,
        val alsoIn: String? = null,
        val votes: Pair<Int, Int>,
    ) {
        /** In the order of [ALL_CATEGORIES], as every list of a question's categories is. */
        val categories: List<String> get() = ALL_CATEGORIES.map { it.id }.filter { it == category || it == alsoIn }
    }

    /**
     * Every seed by its id, `seed-1` first: the ids every build has given them, which V8 and V9 find
     * them by in a database seeded before, and by which [writeMissing] finds those a database lacks.
     */
    internal val SEEDS: List<Pair<String, Starter>> by lazy {
        STARTERS.mapIndexed { index, starter -> "seed-${index + 1}" to starter }
    }

    private val STARTERS =
        listOf(
            Starter(FOOD, "До краја живота јести само пицу", "До краја живота јести само суши", votes = 212 to 158),
            Starter(
                FOOD,
                "Заувек се одрећи кафе",
                "Заувек се одрећи чоколаде",
                alsoIn = LIFESTYLE,
                votes = 97 to 143,
            ),
            Starter(
                FOOD,
                "Да ти храна увек буде мало пресољена",
                "Да ти храна увек буде мало бљутава",
                votes =
                    188 to 61,
            ),
            Starter(
                LIFESTYLE,
                "Радити четири дуга дана у недељи",
                "Радити пет кратких дана у недељи",
                votes =
                    264 to 119,
            ),
            Starter(LIFESTYLE, "Живети без музике", "Живети без филмова", votes = 52 to 301),
            Starter(LIFESTYLE, "Никад више не закаснити", "Никад више не осетити умор", votes = 77 to 246),
            Starter(
                LIFESTYLE,
                "Сваке године се селити у нови град",
                "Никад не напустити родни град",
                votes = 134 to 171,
            ),
            Starter(
                ETHICS,
                "Увек говорити истину",
                "Да ти сви увек говоре истину",
                alsoIn = LIFESTYLE,
                votes = 156 to 139,
            ),
            Starter(
                ETHICS,
                "Увек знати кад те неко лаже",
                "Да ти свако поверује у сваку лаж",
                alsoIn = SUPERPOWERS,
                votes = 283 to 88,
            ),
            Starter(ETHICS, "Спасти једног пријатеља", "Спасти пет непознатих људи", votes = 201 to 176),
            Starter(SUPERPOWERS, "Имати моћ летења", "Имати моћ невидљивости", votes = 318 to 205),
            Starter(SUPERPOWERS, "Читати туђе мисли", "Видети недељу дана унапред", votes = 143 to 231),
            Starter(
                SUPERPOWERS,
                "Телепортовати се било где у трену",
                "Сваког дана зауставити време на сат",
                votes =
                    252 to 190,
            ),
            Starter(
                SUPERPOWERS,
                "Никад више не морати да спаваш",
                "Никад више не морати да једеш",
                alsoIn = FOOD,
                votes = 219 to 97,
            ),
            Starter(
                ABSURD,
                "Борити се са једном патком величине коња",
                "Борити се са сто коња величине патке",
                votes =
                    167 to 274,
            ),
            Starter(ABSURD, "Имати прсте дугачке као ноге", "Имати ноге кратке као прсти", votes = 58 to 73),
            Starter(ABSURD, "Увек говорити у стиховима", "Увек само шапутати", votes = 121 to 94),
            Starter(
                ABSURD,
                "Живети у вечном лету",
                "Живети у вечној зими",
                alsoIn = LIFESTYLE,
                votes = 239 to 82,
            ),
            Starter(LIFESTYLE, "Изгубити све своје фотографије", "Изгубити све своје поруке", votes = 108 to 196),
            Starter(ETHICS, "Да те после смрти сви забораве", "Да те памте по погрешном", votes = 149 to 131),
            Starter(FOOD, "Јести само топлу храну", "Јести само хладну храну", votes = 176 to 68),
            Starter(SUPERPOWERS, "Разговарати са животињама", "Говорити све људске језике", votes = 162 to 245),
            Starter(
                ABSURD,
                "Заувек шепати без икаквог разлога",
                "Заувек кашљати без икаквог разлога",
                votes = 44 to 61,
            ),
            Starter(LIFESTYLE, "Сутра добити на лутрији", "Живети двадесет година дуже", votes = 186 to 233),
            // Путовања
            Starter(
                TRAVEL,
                "Обићи сваку државу на свету, у свакој само по један дан",
                "Упознати само једну државу, али сваки њен кутак",
                votes =
                    60 to 73,
            ),
            Starter(TRAVEL, "Никад више не ући у авион", "Никад више не ући у аутомобил", votes = 74 to 45),
            Starter(
                TRAVEL,
                "Провести месец дана на пустом острву",
                "Провести месец дана у граду који никад не спава",
                votes =
                    57 to 47,
            ),
            Starter(
                TRAVEL,
                "Отпутовати на месец дана без телефона",
                "Отпутовати на месец дана без пртљага",
                votes =
                    151 to 245,
            ),
            Starter(
                TRAVEL,
                "Имати бесплатне авионске карте до краја живота",
                "Имати бесплатан смештај било где на свету",
                votes =
                    100 to 113,
            ),
            Starter(TRAVEL, "Видети поларну светлост", "Видети потпуно помрачење Сунца", votes = 340 to 191),
            Starter(TRAVEL, "Живети годину дана у Јапану", "Живети годину дана у Бразилу", votes = 205 to 149),
            Starter(TRAVEL, "Отпутовати у свемир", "Спустити се на дно океана", votes = 300 to 227),
            Starter(
                TRAVEL,
                "На путовању све испланирати до у минут",
                "На путовању ништа не планирати",
                votes =
                    66 to 124,
            ),
            Starter(TRAVEL, "Сваког лета ићи на море", "Сваког лета ићи на планину", votes = 284 to 133),
            Starter(
                TRAVEL,
                "Никад се не изгубити у непознатом граду",
                "У сваком граду наћи најбољи ресторан",
                alsoIn = FOOD,
                votes =
                    50 to 64,
            ),
            Starter(TRAVEL, "Преспавати у хотелу од леда", "Преспавати у кућици на дрвету", votes = 80 to 74),
            Starter(TRAVEL, "Имати кућу на плажи", "Имати брвнару у планини", alsoIn = LIFESTYLE, votes = 172 to 114),
            Starter(
                TRAVEL,
                "Сваке године ићи на одмор на исто место",
                "Никад двапут не отићи на исто место",
                votes =
                    105 to 244,
            ),
            Starter(TRAVEL, "Пропутовати свет бициклом", "Пропутовати свет аутостопом", votes = 269 to 220),
            Starter(
                TRAVEL,
                "Да ти на сваком путовању касни лет",
                "Да ти се на сваком путовању изгуби кофер",
                votes =
                    158 to 64,
            ),
            // Посао
            Starter(
                WORK,
                "Радити посао из снова за малу плату",
                "Радити досадан посао за огромну плату",
                votes =
                    256 to 277,
            ),
            Starter(WORK, "Имати шефа који стално виче", "Имати шефа који те никад не примећује", votes = 46 to 165),
            Starter(WORK, "Заувек радити од куће", "Никад више не радити од куће", votes = 101 to 60),
            Starter(
                WORK,
                "Имати три месеца одмора годишње",
                "Имати дупло већу плату и само недељу дана одмора",
                votes =
                    256 to 192,
            ),
            Starter(
                WORK,
                "Пешачити до посла сат времена",
                "Возити се до посла сат времена у гужви",
                votes = 316 to 163,
            ),
            Starter(
                WORK,
                "Никад више не имати ниједан састанак",
                "Никад више не отворити пословни мејл",
                votes =
                    246 to 209,
            ),
            Starter(WORK, "Водити сопствену фирму", "Имати сигуран посао у великој фирми", votes = 60 to 62),
            Starter(
                WORK,
                "Радити ноћу, а спавати дању",
                "Радити викендом, а одмарати се радним данима",
                alsoIn = LIFESTYLE,
                votes =
                    167 to 297,
            ),
            Starter(WORK, "Имати колеге који су ти најбољи пријатељи", "Радити без иједног колеге", votes = 175 to 79),
            Starter(WORK, "Мењати посао сваке године", "Цео живот радити на истом послу", votes = 189 to 273),
            Starter(WORK, "Радити у канцеларији без прозора", "Радити напољу по сваком времену", votes = 176 to 233),
            Starter(
                WORK,
                "Радити пола радног времена за пола плате",
                "Радити прековремено за дупло већу плату",
                alsoIn = MONEY,
                votes =
                    280 to 248,
            ),
            Starter(
                WORK,
                "Отићи у пензију са четрдесет година",
                "Радити посао који волиш до осамдесете",
                votes =
                    121 to 77,
            ),
            Starter(WORK, "Сваки дан ићи на посао у оделу", "Сваки дан ићи на посао у тренерци", votes = 133 to 259),
            Starter(
                WORK,
                "Имати посао са много путовања",
                "Имати посао одмах поред куће",
                alsoIn = TRAVEL,
                votes =
                    185 to 218,
            ),
            Starter(WORK, "Радити за најбољег пријатеља", "Да најбољи пријатељ ради за тебе", votes = 190 to 298),
            // Новац
            Starter(
                MONEY,
                "Одмах добити милион евра",
                "Добијати две хиљаде евра месечно до краја живота",
                votes =
                    183 to 143,
            ),
            Starter(
                MONEY,
                "Удвостручити сав новац који имаш",
                "Добити десет хиљада евра на поклон",
                votes = 167 to 285,
            ),
            Starter(
                MONEY,
                "Имати много пара и мало слободног времена",
                "Имати мало пара и много слободног времена",
                alsoIn = LIFESTYLE,
                votes =
                    91 to 88,
            ),
            Starter(MONEY, "Никад више не плаћати станарину", "Никад више не плаћати храну", votes = 124 to 70),
            Starter(
                MONEY,
                "Добити милион евра и не смети никоме да кажеш",
                "Добити милион евра и да то одмах сазна цео свет",
                votes =
                    440 to 103,
            ),
            Starter(
                MONEY,
                "Потрошити милион евра за месец дана",
                "Уштедети милион евра за двадесет година",
                votes =
                    292 to 210,
            ),
            Starter(
                MONEY,
                "Сваког јутра наћи двадесет евра у џепу",
                "Једном годишње добити пет хиљада евра",
                votes =
                    107 to 52,
            ),
            Starter(MONEY, "Наследити кућу на мору", "Наследити стан у центру града", votes = 164 to 200),
            Starter(
                MONEY,
                "Позајмити пријатељу хиљаду евра и никад их више не видети",
                "Одбити пријатеља и изгубити пријатељство",
                alsoIn = ETHICS,
                votes =
                    154 to 107,
            ),
            Starter(
                MONEY,
                "Знати колико зарађује свако кога познајеш",
                "Да нико никад не сазна колико зарађујеш",
                votes =
                    115 to 157,
            ),
            Starter(MONEY, "Добијати плату сваке недеље", "Добијати целу годишњу плату одједном", votes = 99 to 125),
            Starter(
                MONEY,
                "Имати милион евра и живети скромно",
                "Немати ништа и живети као да имаш милион",
                votes =
                    161 to 57,
            ),
            Starter(MONEY, "Никад више не гледати цене у продавници", "Свуда плаћати пола цене", votes = 129 to 139),
            Starter(
                MONEY,
                "Добити сто хиљада евра за себе",
                "Да твој најбољи пријатељ добије милион евра",
                alsoIn = ETHICS,
                votes =
                    56 to 46,
            ),
            Starter(
                MONEY,
                "Добити повишицу од двадесет одсто",
                "Добити још један слободан дан у недељи",
                alsoIn = WORK,
                votes =
                    144 to 168,
            ),
            Starter(MONEY, "Пронаћи закопано благо", "Добити наследство од непознатог рођака", votes = 345 to 203),
            // Љубав
            Starter(LOVE, "Никад се не заљубити", "Заљубљивати се сваког месеца у неког новог", votes = 92 to 187),
            Starter(
                LOVE,
                "Упознати љубав живота са двадесет година",
                "Упознати љубав живота са педесет година",
                votes =
                    287 to 91,
            ),
            Starter(LOVE, "Да ти партнер чита мисли", "Да ти партнер чита све поруке", votes = 180 to 293),
            Starter(LOVE, "Имати први састанак у позоришту", "Имати први састанак на утакмици", votes = 51 to 57),
            Starter(LOVE, "Венчати се на плажи", "Венчати се у старом замку", votes = 261 to 240),
            Starter(LOVE, "Имати свадбу са петсто гостију", "Венчати се тајно, без иједног госта", votes = 41 to 100),
            Starter(LOVE, "Да те партнер стално пита где си", "Да партнера уопште не занима где си", votes = 81 to 106),
            Starter(
                LOVE,
                "Годину дана живети у вези на даљину",
                "Годину дана живети са партнером у једној соби",
                votes =
                    194 to 186,
            ),
            Starter(
                LOVE,
                "Знати тачан дан кад ћеш упознати љубав живота",
                "Да те љубав живота потпуно изненади",
                votes =
                    104 to 184,
            ),
            Starter(
                LOVE,
                "Да ти бивша љубав живи у суседном стану",
                "Да ти бивша љубав ради у истој фирми",
                votes =
                    201 to 243,
            ),
            Starter(
                LOVE,
                "Да ти неко отпева серенаду испод прозора",
                "Да ти неко напише песму која постане хит",
                votes =
                    117 to 226,
            ),
            Starter(
                LOVE,
                "Ићи на састанак на слепо",
                "Ићи на састанак са особом коју ти изабере мама",
                votes =
                    294 to 181,
            ),
            Starter(
                LOVE,
                "Бити у вези са неким ко савршено кува",
                "Бити у вези са неким ко савршено плеше",
                votes =
                    358 to 132,
            ),
            Starter(
                LOVE,
                "Никад се не посвађати са партнером",
                "После сваке свађе се помирити за пет минута",
                votes =
                    220 to 289,
            ),
            Starter(LOVE, "Имати једну везу за цео живот", "Имати много кратких веза", votes = 128 to 57),
            Starter(LOVE, "Да те воли неко кога ти не волиш", "Да волиш неког ко тебе не воли", votes = 176 to 131),
            // Технологија
            Starter(
                TECHNOLOGY,
                "Годину дана живети без интернета",
                "Годину дана живети без топле воде",
                votes =
                    183 to 263,
            ),
            Starter(
                TECHNOLOGY,
                "Имати телефон који никад не мора да се пуни",
                "Имати интернет који никад не пуца",
                votes =
                    82 to 68,
            ),
            Starter(
                TECHNOLOGY,
                "Никад више не користити друштвене мреже",
                "Никад више не гледати телевизију",
                votes =
                    65 to 107,
            ),
            Starter(
                TECHNOLOGY,
                "Да сви виде твоју историју претраге",
                "Да сви прочитају твоје поруке",
                votes =
                    147 to 153,
            ),
            Starter(TECHNOLOGY, "Имати робота који чисти кућу", "Имати робота који кува", votes = 102 to 73),
            Starter(TECHNOLOGY, "Возити се у аутомобилу без возача", "Летети авионом без пилота", votes = 136 to 40),
            Starter(
                TECHNOLOGY,
                "Живети у свету пре мобилних телефона",
                "Живети у свету какав ће бити за сто година",
                votes =
                    213 to 253,
            ),
            Starter(
                TECHNOLOGY,
                "Да ти телефон увек има један одсто батерије",
                "Да ти екран телефона увек буде напукнут",
                votes =
                    140 to 378,
            ),
            Starter(
                TECHNOLOGY,
                "Да ти вештачка интелигенција пише све поруке",
                "Да ти вештачка интелигенција бира сву одећу",
                votes =
                    174 to 324,
            ),
            Starter(
                TECHNOLOGY,
                "Прочитати све поруке у којима те неко спомиње",
                "Никад не сазнати шта други пишу о теби",
                votes =
                    104 to 89,
            ),
            Starter(
                TECHNOLOGY,
                "Сваке године имати најновији телефон",
                "Десет година имати исти телефон",
                votes =
                    84 to 53,
            ),
            Starter(
                TECHNOLOGY,
                "Никад више не морати да памтиш ниједну лозинку",
                "Никад више не чекати да се нешто учита",
                votes =
                    284 to 260,
            ),
            Starter(
                TECHNOLOGY,
                "Живети у паметној кући која све ради сама",
                "Живети у колиби без струје",
                votes =
                    187 to 96,
            ),
            Starter(
                TECHNOLOGY,
                "Да сваки твој разговор буде снимљен",
                "Да свака твоја фотографија буде јавна",
                votes =
                    206 to 274,
            ),
            Starter(
                TECHNOLOGY,
                "Никад више не послати гласовну поруку",
                "Никад више не послати писану поруку",
                votes =
                    84 to 32,
            ),
            Starter(
                TECHNOLOGY,
                "Имати чип у мозгу са целим интернетом",
                "Имати наочаре које преводе сваки језик",
                alsoIn = SUPERPOWERS,
                votes =
                    193 to 326,
            ),
            // Спорт
            Starter(
                SPORTS,
                "Освојити олимпијско злато у спорту који нико не гледа",
                "Изгубити финале светског првенства",
                votes =
                    81 to 46,
            ),
            Starter(SPORTS, "Истрчати маратон", "Попети се на Килиманџаро", votes = 129 to 203),
            Starter(
                SPORTS,
                "Играти кошарку у најбољој лиги на свету",
                "Играти фудбал у најбољој лиги на свету",
                votes =
                    271 to 241,
            ),
            Starter(
                SPORTS,
                "Сваког јутра трчати пет километара",
                "Сваке вечери пливати два километра",
                votes =
                    101 to 113,
            ),
            Starter(
                SPORTS,
                "Одиграти једну утакмицу за репрезентацију",
                "Годину дана тренирати са најбољим тимом на свету",
                votes =
                    137 to 97,
            ),
            Starter(SPORTS, "Никад више не гледати спорт", "Никад се више не бавити спортом", votes = 192 to 123),
            Starter(
                SPORTS,
                "Судити финале светског првенства",
                "Коментарисати финале светског првенства",
                votes =
                    134 to 298,
            ),
            Starter(
                SPORTS,
                "Погодити тројку за победу у последњој секунди",
                "Дати гол за победу у последњем минуту",
                votes =
                    264 to 275,
            ),
            Starter(SPORTS, "Скочити падобраном из авиона", "Спустити се низ дивљу реку у чамцу", votes = 236 to 289),
            Starter(
                SPORTS,
                "Седети на клупи најбољег тима на свету",
                "Играти сваку утакмицу за најслабији тим у лиги",
                votes =
                    48 to 67,
            ),
            Starter(
                SPORTS,
                "Победити светског шампиона у шаху",
                "Победити светског шампиона у тенису",
                votes =
                    250 to 197,
            ),
            Starter(
                SPORTS,
                "Навијати за клуб који никад ништа не освоји",
                "Навијати за клуб који све осваја, а сви га мрзе",
                votes =
                    56 to 95,
            ),
            Starter(SPORTS, "Скијати сваког викенда", "Сурфовати сваког викенда", alsoIn = TRAVEL, votes = 149 to 126),
            Starter(SPORTS, "Имати кондицију маратонца", "Имати снагу дизача тегова", votes = 98 to 50),
            Starter(SPORTS, "Имати базен у дворишту", "Имати фудбалски терен у дворишту", votes = 115 to 41),
            Starter(SPORTS, "Да твој клуб освоји Лигу шампиона", "Да освојиш олимпијску медаљу", votes = 132 to 244),
            // Животиње
            Starter(ANIMALS, "Имати пса који говори", "Имати мачку која говори", votes = 74 to 52),
            Starter(
                ANIMALS,
                "Имати слона величине мачке",
                "Имати мачку величине слона",
                alsoIn = ABSURD,
                votes =
                    284 to 114,
            ),
            Starter(ANIMALS, "Јахати змаја", "Јахати једнорога", votes = 163 to 46),
            Starter(
                ANIMALS,
                "Пливати са делфинима",
                "Пливати са ајкулама у кавезу",
                alsoIn = TRAVEL,
                votes = 340 to 167,
            ),
            Starter(ANIMALS, "Живети са десет мачака", "Живети са једним огромним псом", votes = 75 to 132),
            Starter(
                ANIMALS,
                "Претворити се у орла на један дан",
                "Претворити се у делфина на један дан",
                alsoIn = SUPERPOWERS,
                votes =
                    264 to 162,
            ),
            Starter(ANIMALS, "Да те пчеле никад не убоду", "Да те комарци никад не уједу", votes = 106 to 255),
            Starter(ANIMALS, "Имати змију за кућног љубимца", "Имати паука за кућног љубимца", votes = 208 to 121),
            Starter(ANIMALS, "Провести дан у телу мачке", "Провести дан у телу пса", votes = 165 to 133),
            Starter(ANIMALS, "Знати шта ти љубимац мисли", "Да ти љубимац разуме све што кажеш", votes = 76 to 58),
            Starter(ANIMALS, "Да ти љубимац живи колико и ти", "Да ти љубимац заувек остане штене", votes = 328 to 154),
            Starter(
                ANIMALS,
                "Наћи се лицем у лице са медведом",
                "Наћи се лицем у лице са чопором вукова",
                votes =
                    235 to 302,
            ),
            Starter(
                ANIMALS,
                "Живети на фарми са стотину животиња",
                "Живети у стану без иједне животиње",
                votes =
                    108 to 59,
            ),
            Starter(ANIMALS, "Имати реп као мачка", "Имати уши као зец", alsoIn = ABSURD, votes = 54 to 58),
            Starter(
                ANIMALS,
                "Да те сваког јутра буди петао",
                "Да те сваке ноћи буди пас из комшилука",
                alsoIn = LIFESTYLE,
                votes =
                    161 to 115,
            ),
            Starter(ANIMALS, "Видети живог диносауруса", "Видети живог мамута", votes = 305 to 119),
            // Бљак
            Starter(GROSS, "Да ти дах увек мало смрди", "Да ти ноге увек мало смрде", votes = 110 to 235),
            Starter(
                GROSS,
                "Појести сиров црни лук као јабуку",
                "Попити чашу сока од киселих краставаца",
                alsoIn = FOOD,
                votes =
                    125 to 181,
            ),
            Starter(GROSS, "Годину дана не прати косу", "Месец дана не прати зубе", votes = 155 to 179),
            Starter(GROSS, "Лизнути под у градском превозу", "Лизнути кваку у јавном тоалету", votes = 256 to 235),
            Starter(GROSS, "Јести инсекте за доручак", "Јести пужеве за вечеру", alsoIn = FOOD, votes = 63 to 163),
            Starter(
                GROSS,
                "Месец дана спавати у истој постељини",
                "Недељу дана носити исте чарапе",
                votes = 148 to 107,
            ),
            Starter(GROSS, "Да ти цео дан цури нос", "Да цео дан штуцаш", votes = 235 to 150),
            Starter(
                GROSS,
                "Загрлити некога ко се месец дана не купа",
                "Руковати се са неким ко никад не пере руке",
                votes =
                    157 to 211,
            ),
            Starter(GROSS, "Наћи длаку у свакој супи", "Наћи мушицу у сваком соку", alsoIn = FOOD, votes = 141 to 218),
            Starter(
                GROSS,
                "Завући руку у теглу пуну глиста",
                "Обути ципеле пуне желеа",
                alsoIn = ABSURD,
                votes =
                    132 to 249,
            ),
            Starter(GROSS, "Заувек мирисати на бели лук", "Заувек мирисати на мокрог пса", votes = 285 to 128),
            Starter(
                GROSS,
                "Појести колач који ти је пао на под",
                "Појести колач који је неко већ загризао",
                votes =
                    178 to 135,
            ),
            Starter(
                GROSS,
                "Да ти сваки пољубац има укус црног лука",
                "Да ти сваки загрљај мирише на зној",
                alsoIn = LOVE,
                votes =
                    118 to 134,
            ),
            Starter(
                GROSS,
                "Очистити тоалет на музичком фестивалу",
                "Очистити кавез са мајмунима у зоолошком врту",
                alsoIn = WORK,
                votes =
                    62 to 120,
            ),
            Starter(GROSS, "Да те сваког јутра испрља голуб", "Да сваког јутра нагазиш на жваку", votes = 62 to 98),
            Starter(
                GROSS,
                "Цео дан ходати у мокрим чарапама",
                "Цео дан ходати са каменчићем у ципели",
                votes =
                    133 to 166,
            ),
            // Храна
            Starter(FOOD, "До краја живота јести само слатко", "До краја живота јести само слано", votes = 122 to 268),
            Starter(FOOD, "Никад више не јести хлеб", "Никад више не јести сир", votes = 47 to 53),
            Starter(FOOD, "Никад више не јести месо", "Никад више не јести слаткише", votes = 189 to 325),
            Starter(
                FOOD,
                "Сваке недеље кувати за двадесет људи",
                "Сваке недеље прати судове за двадесет људи",
                votes =
                    250 to 134,
            ),
            Starter(FOOD, "Сваке вечери јести палачинке", "Сваког јутра јести пасуљ", votes = 158 to 62),
            Starter(
                FOOD,
                "Знати да спремиш свако јело на свету",
                "Јести колико хоћеш и никад се не угојити",
                alsoIn = SUPERPOWERS,
                votes =
                    146 to 201,
            ),
            Starter(
                FOOD,
                "Појести најљућу папричицу на свету",
                "Појести најкиселији лимун на свету",
                votes = 66 to 117,
            ),
            Starter(FOOD, "До краја живота пити само воду", "До краја живота пити само млеко", votes = 175 to 46),
            Starter(
                FOOD,
                "Да ти бака сваки дан кува ручак",
                "Да ти сваки дан стиже храна из најбољег ресторана",
                votes =
                    229 to 113,
            ),
            Starter(
                FOOD,
                "Имати бесплатну пицу до краја живота",
                "Имати бесплатан сладолед до краја живота",
                votes =
                    253 to 184,
            ),
            Starter(
                FOOD,
                "Никад више не пробати ново јело",
                "Никад више не појести своје омиљено јело",
                votes =
                    219 to 187,
            ),
            Starter(FOOD, "Заувек се одрећи соли", "Заувек се одрећи шећера", votes = 160 to 307),
            Starter(FOOD, "Јести шпагете штапићима", "Јести супу виљушком", alsoIn = ABSURD, votes = 81 to 26),
            Starter(FOOD, "Сваког дана јести исти ручак", "Никад двапут не јести исто јело", votes = 87 to 246),
            Starter(FOOD, "Никад више не јести у ресторану", "Никад више не јести код куће", votes = 191 to 78),
            // Начин живота
            Starter(
                LIFESTYLE,
                "Сваког дана устајати у пет ујутру",
                "Сваког дана спавати до поднева",
                votes = 86 to 144,
            ),
            Starter(LIFESTYLE, "Живети на селу", "Живети у центру великог града", votes = 114 to 137),
            Starter(LIFESTYLE, "Живети без огледала", "Живети без сата", votes = 186 to 164),
            Starter(LIFESTYLE, "Имати личног кувара", "Имати личног возача", votes = 245 to 128),
            Starter(LIFESTYLE, "Живети са родитељима до тридесете", "Живети са пет цимера", votes = 108 to 80),
            Starter(LIFESTYLE, "Никад више не обути ципеле", "Никад више не обући фармерке", votes = 71 to 223),
            Starter(
                LIFESTYLE,
                "Сваког дана имати један слободан сат више",
                "Сваке године имати један слободан месец више",
                votes =
                    53 to 58,
            ),
            Starter(LIFESTYLE, "Никад више не усисавати", "Никад више не пеглати", votes = 174 to 221),
            Starter(LIFESTYLE, "Имати велику башту", "Имати терасу са погледом на цео град", votes = 259 to 211),
            Starter(
                LIFESTYLE,
                "Да ти сваки викенд траје три дана",
                "Да ти сваки одмор траје дупло дуже",
                votes =
                    155 to 86,
            ),
            Starter(LIFESTYLE, "Имати сто пријатеља", "Имати једног правог пријатеља", votes = 78 to 353),
            Starter(LIFESTYLE, "Знати сваки трач у крају", "Никад не чути ниједан трач", votes = 123 to 144),
            Starter(LIFESTYLE, "Никад више не журити", "Никад се више не досађивати", votes = 132 to 91),
            Starter(LIFESTYLE, "Да те сви препознају на улици", "Да те нико никад не препозна", votes = 38 to 68),
            Starter(
                LIFESTYLE,
                "Живети у свету без реклама",
                "Живети у свету без саобраћајних гужви",
                votes = 172 to 236,
            ),
            // Етика
            Starter(ETHICS, "Рећи пријатељу болну истину", "Слагати пријатеља да га поштедиш", votes = 317 to 186),
            Starter(
                ETHICS,
                "Живети дуго и досадно",
                "Живети кратко и узбудљиво",
                alsoIn = LIFESTYLE,
                votes = 159 to 197,
            ),
            Starter(ETHICS, "Знати истину која боли", "Живети у лепој лажи", votes = 82 to 39),
            Starter(ETHICS, "Пријавити друга који вара на испиту", "Прећутати и помоћи му да прође", votes = 42 to 83),
            Starter(
                ETHICS,
                "Имати моћ да зауставиш сваки рат",
                "Имати моћ да излечиш сваку болест",
                alsoIn = SUPERPOWERS,
                votes =
                    178 to 237,
            ),
            Starter(
                ETHICS,
                "Добити милион евра, а да твој непријатељ добије два",
                "Да ни ти ни твој непријатељ не добијете ништа",
                votes =
                    220 to 91,
            ),
            Starter(ETHICS, "Живети поштено и скромно", "Живети у богатству стеченом преваром", votes = 219 to 65),
            Starter(
                ETHICS,
                "Прочитати дневник најбољег пријатеља",
                "Да најбољи пријатељ прочита твој дневник",
                votes =
                    135 to 139,
            ),
            Starter(
                ETHICS,
                "Сазнати шта пријатељи стварно мисле о теби",
                "Никад не сазнати шта пријатељи мисле о теби",
                votes =
                    272 to 193,
            ),
            Starter(ETHICS, "Да ти сви увек опросте", "Да увек можеш свима да опростиш", votes = 168 to 192),
            Starter(
                ETHICS,
                "Да неко други добије заслуге за твој рад",
                "Да ти добијеш заслуге за туђи рад",
                alsoIn = WORK,
                votes =
                    146 to 113,
            ),
            Starter(ETHICS, "Живети у свету без лажи", "Живети у свету без тајни", votes = 308 to 191),
            Starter(
                ETHICS,
                "Помоћи човеку на улици и закаснити на разговор за посао",
                "Стићи на разговор за посао и проћи поред њега",
                alsoIn = WORK,
                votes =
                    180 to 102,
            ),
            Starter(
                ETHICS,
                "Никад више не изговорити ни најмању лаж",
                "Једном дневно слагати без икаквих последица",
                votes =
                    152 to 237,
            ),
            // Супермоћи
            Starter(
                SUPERPOWERS,
                "Имати моћ исцељења",
                "Имати моћ да вратиш време пет минута уназад",
                votes = 166 to 154,
            ),
            Starter(SUPERPOWERS, "Моћи да дишеш под водом", "Моћи да дишеш у свемиру", votes = 289 to 183),
            Starter(SUPERPOWERS, "Имати савршено памћење", "Моћи да заборавиш шта год пожелиш", votes = 178 to 210),
            Starter(SUPERPOWERS, "Пролазити кроз зидове", "Прескочити сваку зграду у једном скоку", votes = 305 to 233),
            Starter(
                SUPERPOWERS,
                "Путовати кроз време у прошлост",
                "Путовати кроз време у будућност",
                votes = 142 to 183,
            ),
            Starter(SUPERPOWERS, "Имати снагу десет људи", "Имати брзину гепарда", votes = 217 to 309),
            Starter(SUPERPOWERS, "Управљати ватром", "Управљати водом", votes = 59 to 98),
            Starter(
                SUPERPOWERS,
                "Одлучивати какво ће време бити сутра",
                "Никад се више не разболети",
                votes = 71 to 239,
            ),
            Starter(
                SUPERPOWERS,
                "Моћи да уђеш у сваку видео игру",
                "Моћи да уђеш у сваки филм",
                alsoIn = TECHNOLOGY,
                votes =
                    220 to 300,
            ),
            Starter(
                SUPERPOWERS,
                "Живети хиљаду година",
                "Живети сто савршених година",
                alsoIn = LIFESTYLE,
                votes =
                    40 to 92,
            ),
            Starter(
                SUPERPOWERS,
                "Имати клона који уместо тебе иде на посао",
                "Имати клона који уместо тебе иде у теретану",
                alsoIn = WORK,
                votes =
                    126 to 47,
            ),
            Starter(SUPERPOWERS, "Видети у потпуном мраку", "Чути шапат са километар даљине", votes = 178 to 144),
            Starter(
                SUPERPOWERS,
                "Летети, али само метар изнад земље",
                "Нестати, али само кад нико не гледа",
                alsoIn = ABSURD,
                votes =
                    287 to 200,
            ),
            Starter(
                SUPERPOWERS,
                "Имати моћ да увек нађеш изгубљене ствари",
                "Имати моћ да увек нађеш паркинг",
                votes =
                    265 to 228,
            ),
            // Апсурдно
            Starter(ABSURD, "Имати руке уместо ногу", "Имати ноге уместо руку", votes = 232 to 252),
            Starter(ABSURD, "Да сваки твој корак пишти", "Да свако твоје кијање звучи као труба", votes = 41 to 55),
            Starter(ABSURD, "Одговарати на свако питање новим питањем", "Певати уместо да говориш", votes = 79 to 90),
            Starter(ABSURD, "Имати трећу руку", "Имати треће око", votes = 80 to 59),
            Starter(ABSURD, "Живети у кући од чоколаде", "Живети у кући од сира", alsoIn = FOOD, votes = 144 to 83),
            Starter(
                ABSURD,
                "Да ти коса сваког дана порасте метар",
                "Да ти нокти сваког дана порасту метар",
                votes =
                    164 to 67,
            ),
            Starter(
                ABSURD,
                "Да ти глава буде величине поморанџе",
                "Да ти глава буде величине лубенице",
                votes =
                    144 to 120,
            ),
            Starter(
                ABSURD,
                "Сваког јутра се пробудити у другом граду",
                "Сваког јутра се пробудити у другој деценији",
                alsoIn = TRAVEL,
                votes =
                    88 to 98,
            ),
            Starter(
                ABSURD,
                "Да те свуда прати мали оркестар",
                "Да ти после сваке реченице неко аплаудира",
                votes =
                    103 to 93,
            ),
            Starter(ABSURD, "Да ти порасте нос кад год слажеш", "Да поцрвениш кад год неко слаже", votes = 68 to 84),
            Starter(ABSURD, "Заувек ходати уназад", "Заувек говорити уназад", votes = 86 to 44),
            Starter(ABSURD, "Делити стан са пингвином", "Делити стан са ракуном", alsoIn = ANIMALS, votes = 325 to 204),
            Starter(
                ABSURD,
                "Окупати се у кади пуној пасуља",
                "Окупати се у кади пуној желеа",
                alsoIn = GROSS,
                votes =
                    38 to 59,
            ),
            Starter(ABSURD, "Живети у мјузиклу", "Живети у немом филму", votes = 309 to 183),
            Starter(MISC, "Да увек буде лето", "Да увек буде зима", votes = 403 to 131),
            Starter(
                MISC,
                "До краја живота слушати само једну песму",
                "Никад више не слушати музику",
                votes =
                    237 to 71,
            ),
            Starter(MISC, "Заувек носити само црно", "Заувек носити само бело", votes = 288 to 94),
            Starter(
                MISC,
                "Немати ни брата ни сестру",
                "Имати петоро браће и сестара",
                votes =
                    120 to 176,
            ),
            Starter(
                MISC,
                "Поново кренути у први разред",
                "Поново полагати све испите на факултету",
                votes =
                    149 to 109,
            ),
            Starter(MISC, "Гледати само филмове", "Гледати само серије", votes = 158 to 197),
            Starter(
                MISC,
                "Знати да свираш сваки инструмент",
                "Имати савршен глас за певање",
                votes =
                    196 to 162,
            ),
        )
}
