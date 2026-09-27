package io.ntole.wyr.about

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.Density
import io.ntole.wyr.RecordingClipboard
import io.ntole.wyr.RecordingUris
import io.ntole.wyr.descriptions
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
import kotlin.test.assertFalse
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
                            listOf(about.accountId, ACCOUNT_ID) +
                            OPEN_SOURCE_LIBRARIES.flatMap { listOfNotNull(it.name, it.licence, it.notice) }
                    expected.forEach { text -> assertTrue(text in shown, "$language: \"$text\" is not in $shown") }
                } finally {
                    scene.close()
                }
            }
        }
    }

    /**
     * The copy button puts the account id on the clipboard, whole, and the label says it is copied, in
     * every language; nothing else on the screen moves for it.
     */
    @Test
    fun `the account id is copied whole and the label says so`() {
        Language.entries.forEach { language ->
            val about = stringsOf(language).aboutScreen
            val clipboard = RecordingClipboard()
            val scene = scene(language, clipboard = clipboard)
            try {
                val before = scene.nodes().single { about.licences in it.texts }.boundsInRoot
                assertFalse(about.copied in scene.texts(), "$language: copied before any tap")

                scene.tap(about.copyAccountId)

                assertEquals(listOf(ACCOUNT_ID), clipboard.copied, "$language")
                assertTrue(about.copied in scene.texts(), "$language: ${scene.texts()}")
                assertEquals(before, scene.nodes().single { about.licences in it.texts }.boundsInRoot, "$language")
            } finally {
                scene.close()
            }
        }
    }

    /** With no session stored on the device there is no account to name, and the screen names none. */
    @Test
    fun `with no session stored no account id shows`() {
        val about = stringsOf(Language.DEFAULT).aboutScreen
        val scene = scene(Language.DEFAULT, accountId = null)
        try {
            val shown = scene.everyText()
            assertFalse(about.accountId in shown, "$shown")
            assertFalse(about.copyAccountId in shown, "$shown")
        } finally {
            scene.close()
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

    /**
     * A link nothing on the device opens, on a phone with no browser, does nothing: the screen stays,
     * a licence's too (CLAUDE.md §8d, *About*).
     */
    @Test
    fun `a link nothing on the device opens does nothing`() {
        val about = stringsOf(Language.DEFAULT).aboutScreen
        val uris = RecordingUris(opens = false)
        val scene = scene(Language.DEFAULT, uris = uris)
        try {
            listOf(about.privacy, about.terms, about.deleteAccount, about.contact).forEach(scene::tap)
            scene.tap(OPEN_SOURCE_LIBRARIES.first().name)

            assertTrue(about.contact in scene.texts(), "${scene.texts()}")
        } finally {
            scene.close()
        }
        assertEquals(5, uris.opened.size, "${uris.opened}")
    }

    /**
     * The links and the account id with its copy button show before any scrolling, at an iPhone SE's
     * height less the top bar; the licences scroll.
     */
    @Test
    fun `the links and the account id show before any scrolling`() {
        Language.entries.forEach { language ->
            val about = stringsOf(language).aboutScreen
            val scene = scene(language)
            try {
                val contact = scene.nodes().single { about.contact in it.texts }
                assertTrue(contact.boundsInRoot.bottom <= SHORT_PHONE_HEIGHT, "$language: ${contact.boundsInRoot}")
                val copy = scene.nodes().single { about.copyAccountId in it.descriptions }
                assertTrue(copy.boundsInRoot.bottom <= SHORT_PHONE_HEIGHT, "$language: ${copy.boundsInRoot}")
            } finally {
                scene.close()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun scene(
        language: Language,
        dark: Boolean = false,
        uris: UriHandler = RecordingUris(),
        accountId: String? = ACCOUNT_ID,
        clipboard: ClipboardManager = RecordingClipboard(),
    ): ImageComposeScene =
        ImageComposeScene(width = SHORT_PHONE_WIDTH, height = SHORT_PHONE_HEIGHT, density = Density(1f)) {
            CompositionLocalProvider(LocalUriHandler provides uris, LocalClipboardManager provides clipboard) {
                WyrTheme(darkTheme = dark) {
                    WyrStrings(language) { AboutScreen(AppVersion("1.0.0", 10000), accountId = accountId) }
                }
            }
        }.also { it.render() }

    private companion object {
        /** An iPhone SE (667 high) less its status bar (20) and the top bar above the screen (48). */
        const val SHORT_PHONE_WIDTH = 375
        const val SHORT_PHONE_HEIGHT = 599

        /** A player id as the server makes one, a UUID: the longest an account id is. */
        const val ACCOUNT_ID = "0f8fad5b-d9cb-469f-a165-70867728950e"
    }
}
