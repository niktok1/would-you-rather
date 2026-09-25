package io.ntole.wyr.language

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import io.ntole.wyr.core.network.InMemoryTokenStorage
import io.ntole.wyr.theme.WyrTheme
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The language switch on the Account screen (CLAUDE.md §8f), drawn off screen and read through its
 * semantics, as a screen reader would, with no Compose UI test library in the tree.
 */
@OptIn(ExperimentalComposeUiApi::class)
class LanguageSwitchTest {
    @Test
    fun `the switch names every language in itself whatever language is shown`() {
        Language.entries.forEach { shown ->
            val names = options(shown, selected = shown).map { it.name }
            assertEquals(listOf("Ћирилица", "Latinica", "English"), names, "shown in $shown")
        }
    }

    @Test
    fun `the language shown is the one selected and no other`() {
        Language.entries.forEach { selected ->
            val options = options(selected, selected = selected)
            assertEquals(listOf(selected.ownName), options.filter { it.selected }.map { it.name })
        }
    }

    @Test
    fun `a tap on a language picks it`() {
        Language.entries.forEach { tapped ->
            val picked = mutableListOf<Language>()
            val scene = scene(Language.DEFAULT, selected = Language.DEFAULT, onSelect = picked::add)
            try {
                val option = nodes(scene).single { textOf(it) == tapped.ownName }
                val click = assertNotNull(option.config.getOrNull(SemanticsActions.OnClick)?.action)
                click()
                assertEquals(listOf(tapped), picked)
            } finally {
                scene.close()
            }
        }
    }

    /** No label on screen, but a screen reader hears what the switch is, in the language shown. */
    @Test
    fun `a screen reader hears the switch named in the language shown`() {
        Language.entries.forEach { shown ->
            val scene = scene(shown, selected = shown)
            try {
                val descriptions =
                    nodes(scene).flatMap { node ->
                        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    }
                assertTrue(stringsOf(shown).language in descriptions, "$shown: $descriptions")
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
                val shown = LanguageViewModel(InMemoryTokenStorage()).language.value
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

    private fun options(
        shown: Language,
        selected: Language,
    ): List<Option> {
        val scene = scene(shown, selected)
        return try {
            nodes(scene)
                .filter { it.config.getOrNull(SemanticsProperties.Selected) != null }
                .sortedBy { it.positionInRoot.x }
                .map { Option(textOf(it), it.config[SemanticsProperties.Selected]) }
        } finally {
            scene.close()
        }
    }

    private fun scene(
        shown: Language,
        selected: Language,
        onSelect: (Language) -> Unit = {},
    ): ImageComposeScene =
        ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
            WyrTheme { WyrStrings(shown) { LanguageSwitch(selected = selected, onSelect = onSelect) } }
        }.also { it.render() }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { owner -> owner.getAllSemanticsNodes(mergingEnabled = true) }

    private fun textOf(node: SemanticsNode): String =
        node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .joinToString("") { it.text }

    private companion object {
        const val WIDTH = 375
        const val HEIGHT = 100
    }
}
