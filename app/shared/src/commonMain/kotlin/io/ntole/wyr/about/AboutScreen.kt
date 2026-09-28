package io.ntole.wyr.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.ntole.wyr.analytics.tapped
import io.ntole.wyr.language.LocalLanguage
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.language.fill
import io.ntole.wyr.theme.PageSurface
import io.ntole.wyr.theme.WyrIcons
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale
import io.ntole.wyr.theme.contentWidth

/**
 * The About screen (CLAUDE.md §8d, *About*), opened from the Account screen's top bar: the game's name,
 * its [version] and build number, the age it is for, links that open in the browser to the site's
 * privacy policy, terms and question rules, deleting an account and contact ([Site], in the language
 * shown), the player's [accountId], to copy and send when asking by email for their account to be
 * deleted (§8a, *Deleting an account*, *By a moderator*), none while no session is stored, the
 * Statistics switch, [statisticsOn] whether the player lets the game send analytics, which
 * [onStatisticsChange] changes (§8g, [StatisticsSwitch]), the
 * libraries the game ships with, each with its licence ([OPEN_SOURCE_LIBRARIES]), and last, quiet,
 * [deletion], the way to delete the account (`DeleteAccount`, CLAUDE.md §8a). It scrolls. Every
 * colour, space and size from the theme (§5b), every word from [LocalStrings] (§8f).
 */
@Composable
fun AboutScreen(
    version: AppVersion,
    accountId: String?,
    modifier: Modifier = Modifier,
    statisticsOn: Boolean = true,
    onStatisticsChange: (Boolean) -> Unit = {},
    deletion: @Composable () -> Unit = {},
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val shared = LocalStrings.current
    val strings = shared.aboutScreen
    val language = LocalLanguage.current

    PageSurface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(dimens.screenPadding)
                    .contentWidth(dimens.contentMaxWidth),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceSm),
        ) {
            Text(
                text = shared.gameName,
                color = colors.headingAccent,
                fontSize = WyrTypeScale.heading,
                fontWeight = FontWeight.ExtraBold,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm),
            ) {
                Text(text = strings.version.fill(versionText(version)), color = colors.muted)
                AgeRating()
            }

            HorizontalDivider(color = colors.orPillBackground)
            listOf(
                Triple(strings.privacy, SitePage.PRIVACY, "about.privacy"),
                Triple(strings.terms, SitePage.TERMS, "about.terms"),
                Triple(strings.deleteAccount, SitePage.DELETE_ACCOUNT, "about.delete_account"),
                Triple(strings.contact, SitePage.CONTACT, "about.contact"),
            ).forEach { (label, page, element) -> Link(label, Site.url(page, language), element) }
            accountId?.let { id -> AccountId(id) }

            HorizontalDivider(color = colors.orPillBackground)
            StatisticsSwitch(on = statisticsOn, onChange = onStatisticsChange)

            HorizontalDivider(color = colors.orPillBackground)
            Text(
                text = strings.licences,
                color = colors.primaryText,
                fontSize = WyrTypeScale.sectionTitle,
                fontWeight = FontWeight.Bold,
            )
            OPEN_SOURCE_LIBRARIES.forEach { library -> Library(library) }

            HorizontalDivider(color = colors.orPillBackground)
            deletion()
        }
    }
}

/** The version as the screen shows it: MAJOR.MINOR.PATCH and the build number in brackets, when known. */
internal fun versionText(version: AppVersion): String = version.number?.let { "${version.name} ($it)" } ?: version.name

/** The age the game is for, on a pill, as the store listing and the terms say it (CLAUDE.md §8b). */
@Composable
private fun AgeRating() {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens

    Surface(color = colors.orPillBackground, shape = RoundedCornerShape(dimens.radiusCard)) {
        Text(
            text = AGE_RATING,
            color = colors.orPillText,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = dimens.spaceSm, vertical = dimens.spaceXs),
        )
    }
}

/**
 * A line that opens [url] in the browser, reported as [element]'s tap (CLAUDE.md §8g), or does nothing
 * when nothing on the device opens it ([openIfAble]).
 */
@Composable
private fun Link(
    label: String,
    url: String,
    element: String,
) {
    val uriHandler = LocalUriHandler.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = tapped(element) { uriHandler.openIfAble(url) })
                .minimumInteractiveComponentSize(),
    ) {
        Text(
            text = label,
            color = WyrThemeAccessors.colors.headingAccent,
            textDecoration = TextDecoration.Underline,
        )
    }
}

/**
 * The player's account id, under a muted label, in a fixed width, and a button that copies it to the
 * clipboard, reported as `about.copy_account_id`'s tap (CLAUDE.md §8g); the label says it is copied
 * beside it, on the same line, so nothing moves. A guest or a player signed in with Play Games alone,
 * who has no username, sends it to have their account deleted by email.
 */
@Composable
private fun AccountId(accountId: String) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val strings = LocalStrings.current.aboutScreen

    // The one call Compose has for text in common code on every platform: its replacement's ClipEntry
    // has no common constructor, and is experimental on the desktop.
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var copied by remember(accountId) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm)) {
            Text(text = strings.accountId, color = colors.muted, fontSize = WyrTypeScale.statLabel)
            if (copied) Text(text = strings.copied, color = colors.headingAccent, fontSize = WyrTypeScale.statLabel)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = accountId,
                color = colors.primaryText,
                fontSize = WyrTypeScale.statLabel,
                fontFamily = WyrTypeScale.code,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick =
                    tapped("about.copy_account_id") {
                        clipboard.setText(AnnotatedString(accountId))
                        copied = true
                    },
            ) {
                Icon(
                    imageVector = WyrIcons.Copy,
                    contentDescription = strings.copyAccountId,
                    tint = colors.headingAccent,
                )
            }
        }
    }
}

/**
 * A library the game ships with, its licence, whose text a tap opens in the browser, and the copyright
 * notice its licence asks to ship with the app, where it asks for one.
 */
@Composable
private fun Library(library: Licensed) {
    val colors = WyrThemeAccessors.colors
    val uriHandler = LocalUriHandler.current

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.Button,
                    onClick = tapped("about.licence") { uriHandler.openIfAble(library.licenceUrl) },
                ).minimumInteractiveComponentSize(),
    ) {
        Text(text = library.name, color = colors.primaryText)
        Text(text = library.licence, color = colors.muted, fontSize = WyrTypeScale.statLabel)
        library.notice?.let { notice -> Text(text = notice, color = colors.muted, fontSize = WyrTypeScale.statLabel) }
    }
}
