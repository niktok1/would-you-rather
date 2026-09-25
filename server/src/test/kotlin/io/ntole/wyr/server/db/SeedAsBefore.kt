package io.ntole.wyr.server.db

import org.jetbrains.exposed.v1.jdbc.JdbcTransaction

/**
 * Writes the seeds exactly as every build before V6 wrote them, which is what the production database
 * holds until V6 and the scripts after it run there: English options, filed under the enum's names,
 * RANDOM among them, with no made-up votes. Plain SQL naming V1's columns alone, so it runs on a
 * database at V1, where no table has a column a later script added.
 *
 * MigrationsTest migrates a database seeded so and holds the result to what `Seed` writes into an
 * empty one.
 */
internal fun JdbcTransaction.seedAsBefore(submittedAt: Long = SEEDED_AT) {
    SEEDS_BEFORE.forEachIndexed { index, (categories, options) ->
        val id = "seed-${index + 1}"
        exec(
            "INSERT INTO questions (id, option_a, option_b, author_player_id, status, submitted_at) " +
                "VALUES (${sql(id)}, ${sql(options.first)}, ${sql(options.second)}, NULL, 'APPROVED', $submittedAt)",
        )
        categories.forEach { category ->
            exec("INSERT INTO question_categories (question_id, category) VALUES (${sql(id)}, ${sql(category)})")
        }
    }
}

private const val SEEDED_AT = 1_000L

private fun sql(text: String): String = "'" + text.replace("'", "''") + "'"

/** Each seed as the builds before V6 wrote it, `seed-1` first: its categories, then its two options. */
private val SEEDS_BEFORE: List<Pair<List<String>, Pair<String, String>>> =
    listOf(
        listOf("FOOD") to ("Only ever eat pizza again" to "Only ever eat sushi again"),
        listOf("FOOD", "LIFESTYLE") to ("Give up coffee forever" to "Give up chocolate forever"),
        listOf("FOOD") to ("Always slightly too salty food" to "Always slightly bland food"),
        listOf("LIFESTYLE") to ("Work four long days" to "Work five short days"),
        listOf("LIFESTYLE") to ("Live without music" to "Live without films"),
        listOf("LIFESTYLE") to ("Never be late again" to "Never be tired again"),
        listOf("LIFESTYLE") to ("Move to a new city every year" to "Never leave your home town"),
        listOf("LIFESTYLE", "ETHICS") to ("Always tell the truth" to "Always be told the truth"),
        listOf("ETHICS", "SUPERPOWERS") to ("Know when anyone lies to you" to "Have everyone believe your lies"),
        listOf("ETHICS") to ("Save one friend" to "Save five strangers"),
        listOf("SUPERPOWERS") to ("Be able to fly" to "Be able to turn invisible"),
        listOf("SUPERPOWERS") to ("Read minds" to "See one week into the future"),
        listOf("SUPERPOWERS") to ("Teleport anywhere instantly" to "Pause time for an hour a day"),
        listOf("FOOD", "SUPERPOWERS") to ("Never need sleep" to "Never need to eat"),
        listOf("RANDOM") to ("Fight one horse-sized duck" to "Fight a hundred duck-sized horses"),
        listOf("RANDOM") to ("Have fingers as long as legs" to "Have legs as short as fingers"),
        listOf("RANDOM") to ("Always speak in rhyme" to "Only ever whisper"),
        listOf("LIFESTYLE", "RANDOM") to ("Live in permanent summer" to "Live in permanent winter"),
        listOf("LIFESTYLE") to ("Lose all your photos" to "Lose all your messages"),
        listOf("ETHICS") to ("Be forgotten after you die" to "Be remembered wrongly"),
        listOf("FOOD") to ("Eat only hot food" to "Eat only cold food"),
        listOf("SUPERPOWERS") to ("Talk to animals" to "Speak every human language"),
        listOf("RANDOM") to ("Have a permanent unexplained limp" to "Have a permanent unexplained cough"),
        listOf("LIFESTYLE") to ("Win the lottery tomorrow" to "Live twenty years longer"),
    )
