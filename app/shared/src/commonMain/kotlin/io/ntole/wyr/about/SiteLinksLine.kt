package io.ntole.wyr.about

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import io.ntole.wyr.analytics.tapped
import io.ntole.wyr.language.LocalLanguage
import io.ntole.wyr.language.TemplatePart
import io.ntole.wyr.language.parts
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale

/** One link in a [SiteLinksLine]: its [label], the site's [page] it opens, and its tap's [element] (CLAUDE.md §8g). */
internal data class SiteLink(
    val label: String,
    val page: SitePage,
    val element: String,
)

/**
 * One short muted line whose template [line] puts each of [links] where its `{0}`, `{1}` and on are,
 * each a link that opens its page on the site in the browser, in the language shown ([Site]), or does
 * nothing when nothing on the device opens it ([openIfAble]): the Register form's terms line and the
 * Submit form's rules line (CLAUDE.md §8d).
 */
@Composable
internal fun SiteLinksLine(
    line: String,
    links: List<SiteLink>,
) {
    val colors = WyrThemeAccessors.colors
    val language = LocalLanguage.current
    val uriHandler = LocalUriHandler.current
    val linkStyle = TextLinkStyles(SpanStyle(color = colors.headingAccent, textDecoration = TextDecoration.Underline))
    val text =
        buildAnnotatedString {
            line.parts().forEach { part ->
                when (part) {
                    is TemplatePart.Text -> {
                        append(part.text)
                    }

                    is TemplatePart.Value -> {
                        // A placeholder with no link, which StringsTest keeps out of every language, as it stands.
                        val link = links.getOrNull(part.index)
                        if (link == null) {
                            append("{${part.index}}")
                        } else {
                            val open = tapped(link.element) { uriHandler.openIfAble(Site.url(link.page, language)) }
                            withLink(
                                LinkAnnotation.Clickable(link.element, linkStyle) { open() },
                            ) { append(link.label) }
                        }
                    }
                }
            }
        }

    Text(text = text, color = colors.muted, fontSize = WyrTypeScale.statLabel)
}
