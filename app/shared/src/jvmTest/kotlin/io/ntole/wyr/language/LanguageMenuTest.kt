package io.ntole.wyr.language

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.network.InMemoryTokenStorage
import io.ntole.wyr.renderAt
import io.ntole.wyr.theme.WyrTheme
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The language menu on the Account screen (CLAUDE.md §8f), drawn off screen and read through its
 * semantics, as a screen reader would, with no Compose UI test library in the tree.
 */
@OptIn(ExperimentalComposeUiApi::class)
class LanguageMenuTest {
    @Test
    fun `closed it shows the language shown by its own name and a screen reader hears what it is`() {
        Language.entries.forEach { shown ->
            val scene = scene(shown, selected = shown)
            try {
                val menu = menuNode(scene)
                assertEquals("${stringsOf(shown).language}: ${shown.ownName}", descriptionOf(menu), "shown in $shown")
                assertEquals(emptyList(), options(scene), "no language is listed until it is opened")
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun `opened it names every language in itself whatever language is shown`() {
        Language.entries.forEach { shown ->
            val scene = opened(shown, selected = shown)
            try {
                val names = options(scene).map { it.name }
                assertEquals(listOf("Ћирилица", "Latinica", "English"), names, "shown in $shown")
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun `the language shown is the one selected and no other`() {
        Language.entries.forEach { selected ->
            val scene = opened(selected, selected = selected)
            try {
                assertEquals(listOf(selected.ownName), options(scene).filter { it.selected }.map { it.name })
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun `a tap on a language picks it and closes the menu`() {
        Language.entries.forEach { tapped ->
            val picked = mutableListOf<Language>()
            val scene = opened(Language.DEFAULT, selected = Language.DEFAULT, onSelect = picked::add)
            try {
                val option = nodes(scene).single { textOf(it) == tapped.ownName && isOption(it) }
                val click = assertNotNull(option.config.getOrNull(SemanticsActions.OnClick)?.action)
                click()
                // Past the menu's closing animation, which keeps it drawn until it ends.
                scene.frames(from = OPENED)
                assertEquals(listOf(tapped), picked)
                assertEquals(emptyList(), options(scene), "closed once one is picked")
            } finally {
                scene.close()
            }
        }
    }

    /** Nothing reads the device's locale, so an English or a German device opens in Cyrillic too. */
    @Test
    fun `a first launch shows Serbian Cyrillic whatever the device's language`() {
        val before = Locale.getDefault()
        try {
            listOf(Locale.ENGLISH, Locale.GERMANY, Locale.forLanguageTag("sr-Latn-RS")).forEach { locale ->
                Locale.setDefault(locale)
                val shown = LanguageViewModel(InMemoryTokenStorage(), Analytics.None).language.value
                assertEquals(Language.SERBIAN_CYRILLIC, shown, "on $locale")
            }
        } finally {
            Locale.setDefault(before)
        }
    }

    private data class Option(
        val name: String,
        val selected: Boolean,
    )

    /** The menu's languages while it is open, top to bottom, each with whether it is the one selected. */
    private fun options(scene: ImageComposeScene): List<Option> =
        nodes(scene)
            .filter(::isOption)
            .sortedBy { it.positionInRoot.y }
            .map { Option(textOf(it), it.config[SemanticsProperties.Selected]) }

    private fun isOption(node: SemanticsNode): Boolean = node.config.getOrNull(SemanticsProperties.Selected) != null

    /** The menu itself, closed or open: what a screen reader hears it named by. */
    private fun menuNode(scene: ImageComposeScene): SemanticsNode =
        nodes(scene).single { node -> Language.entries.any { descriptionOf(node).endsWith(": ${it.ownName}") } }

    /** [scene] with the menu opened by a tap, as a player opens it. */
    private fun opened(
        shown: Language,
        selected: Language,
        onSelect: (Language) -> Unit = {},
    ): ImageComposeScene =
        scene(shown, selected, onSelect).also { scene ->
            val open = assertNotNull(menuNode(scene).config.getOrNull(SemanticsActions.OnClick)?.action)
            open()
            scene.frames(from = 0)
            assertTrue(options(scene).isNotEmpty(), "the menu opens")
        }

    private fun scene(
        shown: Language,
        selected: Language,
        onSelect: (Language) -> Unit = {},
    ): ImageComposeScene =
        ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
            WyrTheme { WyrStrings(shown) { LanguageMenu(selected = selected, onSelect = onSelect) } }
        }.also { it.render() }

    /**
     * Draws the scene a frame at a time, at 60 a second, for a second of its clock from [from], so what
     * the last tap changed has shown, an animation's end included: drawn once, a frame can miss what the
     * desktop's snapshot manager does meanwhile (`renderAt`), and a scene's clock stands still unless
     * it is given.
     */
    private fun ImageComposeScene.frames(from: Long) {
        generateSequence(from) { it + FRAME }.takeWhile { it <= from + OPENED }.forEach { renderAt(it) }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { owner -> owner.getAllSemanticsNodes(mergingEnabled = true) }

    private fun descriptionOf(node: SemanticsNode): String =
        node.config
            .getOrNull(SemanticsProperties.ContentDescription)
            .orEmpty()
            .joinToString(" ")

    private fun textOf(node: SemanticsNode): String =
        node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .joinToString("") { it.text }

    private companion object {
        const val WIDTH = 375
        const val HEIGHT = 300

        /** One frame at 60 a second, in nanoseconds. */
        const val FRAME = 1_000_000_000L / 60

        /** A second of the scene's clock, in nanoseconds: the menu opened and its animation done by then. */
        const val OPENED = 1_000_000_000L
    }
}
