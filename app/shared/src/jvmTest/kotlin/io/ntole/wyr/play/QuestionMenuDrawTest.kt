package io.ntole.wyr.play

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import io.ntole.wyr.core.domain.report.ReportReason
import io.ntole.wyr.descriptions
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.language.WyrStrings
import io.ntole.wyr.language.stringsOf
import io.ntole.wyr.navigation.PlayTopBar
import io.ntole.wyr.nodes
import io.ntole.wyr.tap
import io.ntole.wyr.texts
import io.ntole.wyr.theme.WyrTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The menu about the question on screen, on the Play screen's top bar (CLAUDE.md §8d, *The Play
 * screen*, *Reports*), drawn off screen under the bar at a short phone's size, in each theme and each
 * language, read and tapped through its semantics.
 */
class QuestionMenuDrawTest {
    @Test
    fun `the menu draws open in both themes and every language`() {
        listOf(false, true).forEach { dark ->
            Language.entries.forEach { language ->
                withMenu(language, dark = dark) { scene, _ ->
                    val menu = stringsOf(language).playScreen.menu
                    scene.tap(menu.name)
                    assertEquals(WIDTH, scene.render().width)
                    scene.tap(menu.report)
                    assertEquals(WIDTH, scene.render().width)
                }
            }
        }
    }

    /** Beside the account icon, before it, and named for a screen reader in the language shown. */
    @Test
    fun `the menu stands beside the account icon`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language)
            withMenu(language) { scene, _ ->
                assertEquals(listOf(strings.home, strings.playScreen.menu.name, strings.account), scene.descriptions())
            }
        }
    }

    /**
     * Three choices, and Report's five reasons in their place, each a tap, in the language shown; a
     * choice closes the menu, which opens again on the three.
     */
    @Test
    fun `each choice and each reason is one tap`() {
        Language.entries.forEach { language ->
            val menu = stringsOf(language).playScreen.menu
            withMenu(language) { scene, picked ->
                scene.tap(menu.name)
                assertTrue(
                    scene.texts().containsAll(listOf(menu.report, menu.hideQuestion, menu.hideAuthor)),
                    "${scene.texts()}",
                )
                scene.tap(menu.hideQuestion)
                scene.tap(menu.name)
                scene.tap(menu.hideAuthor)
                ReportReason.entries.forEach { reason ->
                    scene.tap(menu.name)
                    scene.tap(menu.report)
                    val reasons = ReportReason.entries.map(menu::reason)
                    assertTrue(scene.texts().containsAll(reasons), "${scene.texts()} in $language")
                    assertTrue(menu.hideQuestion !in scene.texts(), "the reasons take the choices' place")
                    scene.tap(menu.reason(reason))
                }

                assertEquals(
                    listOf(MenuChoice.HideQuestion, MenuChoice.HideAuthor) +
                        ReportReason.entries.map(MenuChoice::Report),
                    picked,
                    "$language",
                )
            }
        }
    }

    /** Off, it opens nothing: with no question on screen, or while anything is in flight. */
    @Test
    fun `the menu is off while it may not be used`() {
        val menu = stringsOf(Language.DEFAULT).playScreen.menu
        withMenu(Language.DEFAULT, enabled = false) { scene, _ ->
            val icon = assertNotNull(scene.nodes().singleOrNull { menu.name in it.descriptions })
            assertTrue(icon.config.getOrNull(SemanticsProperties.Disabled) != null, "the menu is on")
            assertTrue(menu.report !in scene.texts())
        }
    }

    /** [test] on Play's top bar with the menu, in [language], and every choice picked from it. */
    private fun withMenu(
        language: Language,
        dark: Boolean = false,
        enabled: Boolean = true,
        test: (ImageComposeScene, List<MenuChoice>) -> Unit,
    ) {
        val picked = mutableListOf<MenuChoice>()
        val scene =
            ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
                WyrTheme(darkTheme = dark) {
                    WyrStrings(language) {
                        PlayTopBar(
                            onHome = {},
                            onAccount = {},
                            menu = { QuestionMenu(enabled = enabled, onPick = { picked += it }) },
                        ) {
                            CategoriesPlayed(text = LocalStrings.current.allCategories, enabled = true, onClick = {})
                        }
                    }
                }
            }
        try {
            scene.render()
            test(scene, picked)
        } finally {
            scene.close()
        }
    }

    private companion object {
        /** An iPhone SE's width, and its height less the status bar: room for the menu under the bar. */
        const val WIDTH = 375
        const val HEIGHT = 647
    }
}
