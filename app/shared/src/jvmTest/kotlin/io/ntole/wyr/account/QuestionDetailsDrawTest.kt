package io.ntole.wyr.account

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionRules
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.descriptions
import io.ntole.wyr.everyNode
import io.ntole.wyr.everyText
import io.ntole.wyr.home.HOME_COUNT_UP_MILLIS
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.WyrStrings
import io.ntole.wyr.language.categoryName
import io.ntole.wyr.language.fill
import io.ntole.wyr.language.optionText
import io.ntole.wyr.language.stringsOf
import io.ntole.wyr.passTime
import io.ntole.wyr.pixels
import io.ntole.wyr.texts
import io.ntole.wyr.theme.WyrTheme
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A question of the player's own, whole (CLAUDE.md §8d, *Question details*), drawn off screen in each
 * theme and language, as an iPhone SE shows it: its options in full, however long, each side's share
 * and how many picked it once served, where it stands with a rejection's reason whole, when it was
 * sent, its categories by name, and its numbers, which a screen reader hears with their names.
 */
class QuestionDetailsDrawTest {
    @Test
    fun `every status draws in both themes and every language`() {
        listOf(false, true).forEach { dark ->
            Language.entries.forEach { language ->
                EVERY_STATUS.forEach { submission ->
                    val scene = scene(submission, language, dark = dark)
                    try {
                        val strings = stringsOf(language).accountScreens
                        val shown = scene.everyText()
                        assertTrue(statusText(submission, strings) in shown, "$language: $shown")
                        val sent = strings.sentOn.fill(dateText(submission.submittedAt, language))
                        assertTrue(sent in shown, "$language: \"$sent\" is not in $shown")
                    } finally {
                        scene.close()
                    }
                }
            }
        }
    }

    /** Both options show whole, the longest there can be included, never cut short as the table cuts them. */
    @Test
    fun `the options show whole however long`() {
        Language.entries.forEach { language ->
            val scene = scene(LONG, language)
            try {
                listOf(LONG.optionA, LONG.optionB).map { optionText(it, language) }.forEach { option ->
                    val node = scene.everyNode().single { option in it.texts }
                    val layouts = mutableListOf<TextLayoutResult>()
                    assertNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(layouts)
                    assertFalse(layouts.single().multiParagraph.didExceedMaxLines, "$language: cut short")
                }
                val strings = stringsOf(language).accountScreens
                assertTrue(strings.rejectedBecause.fill(REASON) in scene.everyText(), "$language: the reason whole")
            } finally {
                scene.close()
            }
        }
    }

    /**
     * A served question shows each side's share, how many picked it, and its likes, dislikes and answers,
     * each heard with its name; one never served shows none of them.
     */
    @Test
    fun `a served question shows how the answers split and its numbers`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            val scene = scene(APPROVED, language)
            try {
                scene.passTime(HOME_COUNT_UP_MILLIS.toLong())
                val shown = scene.everyText()
                listOf("75%", "25%").forEach { share -> assertTrue(share in shown, "$language: $shown") }
                val said = scene.descriptions()
                listOf(
                    "${strings.answers}: 30",
                    "${strings.answers}: 10",
                    "${strings.likes}: 7",
                    "${strings.dislikes}: 2",
                    "${strings.answers}: 40",
                ).forEach { value -> assertTrue(value in said, "$language: \"$value\" is not in $said") }
            } finally {
                scene.close()
            }

            val pending = scene(PENDING, language)
            try {
                assertFalse(pending.everyText().any { it.endsWith("%") }, "$language: no share never served")
                assertTrue(pending.descriptions().none { it.startsWith(strings.likes) }, "$language: no numbers")
            } finally {
                pending.close()
            }
        }
    }

    /**
     * The screen made anew, as an Android activity is on a rotation, its saved state restored, resumes
     * the count where it was: its first frame is what was drawn as the state was saved, part way or
     * done, never the count from 0 again (CLAUDE.md §8d, *Question details*).
     */
    @Test
    fun `a count restored in a screen made anew resumes where it was`() {
        val start = scene(APPROVED, Language.DEFAULT).let { scene -> scene.pixels().also { scene.close() } }
        listOf(HOME_COUNT_UP_MILLIS * 2L / 5, HOME_COUNT_UP_MILLIS + 100L).forEach { savedAt ->
            val registry = SaveableStateRegistry(null) { true }
            val first = scene(APPROVED, Language.DEFAULT, registry = registry)
            val asSaved: IntArray
            val saved: Map<String, List<Any?>>
            try {
                first.passTime(savedAt)
                asSaved = first.pixels()
                saved = registry.performSave()
            } finally {
                first.close()
            }
            assertFalse(asSaved.contentEquals(start), "saved at $savedAt ms: nothing counted yet")

            val again = scene(APPROVED, Language.DEFAULT, registry = SaveableStateRegistry(saved) { true })
            try {
                assertTrue(again.pixels().contentEquals(asSaved), "saved at $savedAt ms: not where it was")
            } finally {
                again.close()
            }
        }
    }

    /** The categories are named in the language shown, and one not read yet by its id. */
    @Test
    fun `the categories are named in the language shown`() {
        Language.entries.forEach { language ->
            val scene = scene(APPROVED.copy(categories = setOf("FOOD", "NEW")), language)
            try {
                val named = "${categoryName(FOOD, language)}, NEW"
                assertTrue(named in scene.everyText(), "$language: ${scene.everyText()}")
            } finally {
                scene.close()
            }
        }
    }

    /** A date in numbers, as each language writes it, on the day of the zone asked for. */
    @Test
    fun `a date is written as each language writes one`() {
        val sent = Instant.parse("2026-09-25T23:30:00Z")
        assertEquals("25. 9. 2026.", dateText(sent, Language.SERBIAN_CYRILLIC, TimeZone.UTC))
        assertEquals("25. 9. 2026.", dateText(sent, Language.SERBIAN_LATIN, TimeZone.UTC))
        assertEquals("25/9/2026", dateText(sent, Language.ENGLISH, TimeZone.UTC))
        assertEquals("26. 9. 2026.", dateText(sent, Language.SERBIAN_CYRILLIC, TimeZone.of("Europe/Belgrade")))
    }

    private fun scene(
        submission: Submission,
        language: Language,
        dark: Boolean = false,
        registry: SaveableStateRegistry? = null,
    ): ImageComposeScene =
        ImageComposeScene(width = SHORT_PHONE_WIDTH, height = SHORT_PHONE_HEIGHT, density = Density(1f)) {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                WyrTheme(darkTheme = dark) {
                    WyrStrings(language) { QuestionDetailsScreen(submission = submission, categories = listOf(FOOD)) }
                }
            }
        }.also { it.render() }

    private companion object {
        /** An iPhone SE (667 high) less its status bar (20) and the top bar above the screen (48). */
        const val SHORT_PHONE_WIDTH = 375
        const val SHORT_PHONE_HEIGHT = 599

        val FOOD = Category(id = "FOOD", nameSr = "Храна", nameEn = "Food")

        val LONGEST = "Be able to fly ".repeat(20).take(SubmissionRules.MAX_OPTION_LENGTH)

        val REASON = "x".repeat(200)

        val PENDING =
            Submission(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = setOf("FOOD"),
                status = SubmissionStatus.PENDING,
                rejectionReason = null,
                submittedAt = Instant.parse("2026-09-25T12:00:00Z"),
            )

        val APPROVED =
            PENDING.copy(
                status = SubmissionStatus.APPROVED,
                likeCount = 7,
                dislikeCount = 2,
                answerCount = 40,
                tally = Tally(votesA = 30, votesB = 10),
            )

        val LONG =
            PENDING.copy(
                optionA = LONGEST,
                optionB = LONGEST.reversed(),
                status = SubmissionStatus.REJECTED,
                rejectionReason = REASON,
            )

        val EVERY_STATUS =
            listOf(
                PENDING,
                APPROVED,
                LONG,
                APPROVED.copy(status = SubmissionStatus.RETIRED),
                PENDING.copy(status = SubmissionStatus.OTHER),
                // Served, and nobody has answered it yet.
                PENDING.copy(status = SubmissionStatus.APPROVED),
            )
    }
}
