package io.ntole.wyr.about

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.Density
import io.ntole.wyr.RecordingUris
import io.ntole.wyr.everyText
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.WyrStrings
import io.ntole.wyr.language.fill
import io.ntole.wyr.language.stringsOf
import io.ntole.wyr.nodes
import io.ntole.wyr.tap
import io.ntole.wyr.texts
import io.ntole.wyr.theme.WyrTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The About screen (CLAUDE.md §8d, *About*) drawn off screen in each theme and language: the game's
 * name, its version and build number, 16+, the site's four pages, which open in the browser in the
 * language shown, and every library with its licence. Links open through a handler of the test's own,
 * so nothing here reaches a browser or the site.
 */
class AboutScreenDrawTest {
    @Test
    fun `the screen shows the name the version the age the links and every licence`() {
        listOf(false, true).forEach { dark ->
            Language.entries.forEach { language ->
                val strings = stringsOf(language)
                val about = strings.aboutScreen
                val scene = scene(language, dark = dark)
                try {
                    val shown = scene.everyText()
                    val expected =
                        listOf(strings.gameName, about.version.fill("1.0.0 (10000)"), AGE_RATING) +
                            listOf(about.privacy, about.terms, about.deleteAccount, about.contact, about.licences) +
                            OPEN_SOURCE_LIBRARIES.flatMap { listOf(it.name, it.licence) }
                    expected.forEach { text -> assertTrue(text in shown, "$language: \"$text\" is not in $shown") }
                } finally {
                    scene.close()
                }
            }
        }
    }

    /** Each link opens its page, the Serbian one for either script and the English one under /en/. */
    @Test
    fun `each link opens its page on the site in the language shown`() {
        Language.entries.forEach { language ->
            val about = stringsOf(language).aboutScreen
            val uris = RecordingUris()
            val scene = scene(language, uris = uris)
            try {
                listOf(about.privacy, about.terms, about.deleteAccount, about.contact).forEach(scene::tap)
            } finally {
                scene.close()
            }
            val pages = listOf(SitePage.PRIVACY, SitePage.TERMS, SitePage.DELETE_ACCOUNT, SitePage.CONTACT)
            assertEquals(pages.map { Site.url(it, language) }, uris.opened, "$language")
        }
    }

    /** The links show before any scrolling, at an iPhone SE's height less the top bar; the licences scroll. */
    @Test
    fun `the links show before any scrolling`() {
        Language.entries.forEach { language ->
            val scene = scene(language)
            try {
                val contact = scene.nodes().single { stringsOf(language).aboutScreen.contact in it.texts }
                assertTrue(contact.boundsInRoot.bottom <= SHORT_PHONE_HEIGHT, "$language: ${contact.boundsInRoot}")
            } finally {
                scene.close()
            }
        }
    }

    private fun scene(
        language: Language,
        dark: Boolean = false,
        uris: UriHandler = RecordingUris(),
    ): ImageComposeScene =
        ImageComposeScene(width = SHORT_PHONE_WIDTH, height = SHORT_PHONE_HEIGHT, density = Density(1f)) {
            CompositionLocalProvider(LocalUriHandler provides uris) {
                WyrTheme(darkTheme = dark) { WyrStrings(language) { AboutScreen(AppVersion("1.0.0", 10000)) } }
            }
        }.also { it.render() }

    private companion object {
        /** An iPhone SE (667 high) less its status bar (20) and the top bar above the screen (48). */
        const val SHORT_PHONE_WIDTH = 375
        const val SHORT_PHONE_HEIGHT = 599
    }
}
