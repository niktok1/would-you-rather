package io.ntole.wyr.home

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.ntole.wyr.descriptions
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.WyrStrings
import io.ntole.wyr.language.stringsOf
import io.ntole.wyr.sizeNeeded
import io.ntole.wyr.tap
import io.ntole.wyr.texts
import io.ntole.wyr.theme.WyrTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Home screen (CLAUDE.md §8d, *Navigation*) drawn off screen at two phones' sizes, in each theme
 * and each language, and read through its semantics: the game's name, Play and the account icon, and
 * nothing else.
 */
class HomeScreenDrawTest {
    @Test
    fun `the screen draws in both themes and every language`() {
        listOf(false, true).forEach { dark ->
            Language.entries.forEach { language ->
                listOf(WIDTH to HEIGHT, SHORT_PHONE_WIDTH to SHORT_PHONE_HEIGHT).forEach { (width, height) ->
                    val scene = scene(language, dark, width, height)
                    try {
                        assertEquals(width, scene.render().width)
                    } finally {
                        scene.close()
                    }
                }
            }
        }
    }

    /**
     * An iPhone SE (667 high) less its status bar (20) and 48 more, as the other screens are held:
     * the Home screen has no bar above it, so this leaves it room to spare. Measured at 375 wide, the
     * name wrapping as it will on the narrowest phone: on this Mac it needs 276, the name on two lines
     * in Cyrillic and English, and 230 in Latin, on one.
     */
    @Test
    fun `the screen fits a short phone in every language`() {
        Language.entries.forEach { language ->
            val (_, height) =
                sizeNeeded(SHORT_PHONE_WIDTH, SHORT_PHONE_HEIGHT) {
                    WyrTheme { WyrStrings(language) { HomeScreen(onPlay = {}, onAccount = {}) } }
                }
            assertTrue(height <= SHORT_PHONE_HEIGHT, "$language needs $height of $SHORT_PHONE_HEIGHT")
        }
    }

    /** The user asked for less text: the name and Play are all there is to read. */
    @Test
    fun `the screen shows only the name and Play and the account icon`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language)
            val scene = scene(language)
            try {
                assertEquals(listOf(strings.gameName, strings.play), scene.texts(), "$language")
                assertEquals(listOf(strings.account), scene.descriptions(), "$language")
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun `Play and the account icon each do what they say`() {
        Language.entries.forEach { language ->
            val tapped = mutableListOf<String>()
            val scene = scene(language, onPlay = { tapped += "play" }, onAccount = { tapped += "account" })
            try {
                scene.tap(stringsOf(language).play)
                scene.tap(stringsOf(language).account)
                assertEquals(listOf("play", "account"), tapped, "$language")
            } finally {
                scene.close()
            }
        }
    }

    private fun scene(
        language: Language,
        dark: Boolean = false,
        width: Int = SHORT_PHONE_WIDTH,
        height: Int = SHORT_PHONE_HEIGHT,
        onPlay: () -> Unit = {},
        onAccount: () -> Unit = {},
    ): ImageComposeScene =
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            WyrTheme(darkTheme = dark) { WyrStrings(language) { HomeScreen(onPlay = onPlay, onAccount = onAccount) } }
        }.also { it.render() }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 900
        const val SHORT_PHONE_WIDTH = 375
        const val SHORT_PHONE_HEIGHT = 599
    }
}
