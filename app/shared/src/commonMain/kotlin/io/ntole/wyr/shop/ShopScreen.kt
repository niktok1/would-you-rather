package io.ntole.wyr.shop

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.ntole.wyr.analytics.tapped
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.shop.Shop
import io.ntole.wyr.core.domain.shop.ShopTheme
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.language.Strings
import io.ntole.wyr.language.fill
import io.ntole.wyr.loading.LoadingSpinner
import io.ntole.wyr.points.PointsAmount
import io.ntole.wyr.points.PointsText
import io.ntole.wyr.theme.GameTheme
import io.ntole.wyr.theme.GameThemes
import io.ntole.wyr.theme.PageSurface
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale
import io.ntole.wyr.theme.contentWidth

/**
 * The shop (CLAUDE.md §8d, *The shop*): its name and the player's points; then *Твоје теме*, the
 * themes the player owns, the game's own first, a horizontal picker of small previews, a tap putting one
 * on ([onWear]) and the one [worn] marked; then *У понуди*, the rest, each on a card with a preview
 * ([ThemePreview]) and *Купи* with its price, which opens a dialog showing it larger before anything is
 * bought, over which a guest is told why they cannot buy, with the way to the Auth page, [onOpenAuth].
 * A theme bought moves up into the picker. Under them, that more is coming. A theme the server sells
 * that this build has no colours for is not shown. It scrolls.
 *
 * Every colour, space and size from the theme (§5b), every word from [LocalStrings] (§8f).
 */
@Composable
fun ShopScreen(
    state: ShopState,
    actions: ShopActions,
    worn: GameTheme,
    onWear: (String) -> Unit,
    onOpenAuth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val shared = LocalStrings.current
    val strings = shared.shopScreen
    val shop = state.shop

    PageSurface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(dimens.screenPadding)
                    .contentWidth(dimens.contentMaxWidth),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceMd),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = strings.title,
                    color = colors.headingAccent,
                    fontSize = WyrTypeScale.heading,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f),
                )
                if (shop != null) {
                    PointsAmount(
                        points = shop.points,
                        fontSize = WyrTypeScale.heading,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.headingAccent,
                    )
                }
            }

            if (shop == null) {
                val failure = state.readFailure
                if (failure == null) {
                    LoadingSpinner()
                } else {
                    Failure(failure, shared, retry = actions::refresh, busy = state.isBusy)
                }
            } else {
                SectionTitle(strings.yourThemes)
                OwnedPicker(owned = ownedOf(shop), worn = worn, busy = state.isBusy, onWear = onWear)
                val forSale = onSaleOf(shop)
                if (forSale.isNotEmpty()) SectionTitle(strings.onSale)
                Notes(state, shared, onOpenAuth)
                forSale.forEach { (theme, onSale) -> SaleCard(theme, onSale, state, actions) }
                Text(
                    text = strings.comingSoon,
                    color = colors.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    val confirming = state.confirming?.let { id -> shop?.theme(id) }
    val confirmingTheme = confirming?.let { GameThemes.ofId(it.id) }
    if (confirming != null && confirmingTheme != null) {
        ConfirmPurchase(confirmingTheme, confirming, actions)
    }
}

/**
 * The themes the player owns, in the picker's order: the game's own first, free and every player's,
 * then each of the server's they bought that this build has colours for.
 */
internal fun ownedOf(shop: Shop): List<GameTheme> =
    listOf(GameThemes.Default) + shop.themes.filter { it.owned }.mapNotNull { GameThemes.ofId(it.id) }

/** The themes still on sale to the player, each of the server's they do not own that this build can draw. */
internal fun onSaleOf(shop: Shop): List<Pair<GameTheme, ShopTheme>> =
    shop.themes.filterNot { it.owned }.mapNotNull { onSale -> GameThemes.ofId(onSale.id)?.let { it to onSale } }

/** A section's heading. */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = WyrThemeAccessors.colors.primaryText,
        fontSize = WyrTypeScale.sectionTitle,
        fontWeight = FontWeight.Bold,
    )
}

/**
 * The player's themes side by side, scrolled across (the user: "horizontal picker"): a tile each, its
 * preview and its name, the one [worn] outlined in the accent and marked *Активна*. A tap on a tile puts
 * it on ([onWear]); the tiles are one choice of many to a screen reader, the worn one selected.
 */
@Composable
private fun OwnedPicker(
    owned: List<GameTheme>,
    worn: GameTheme,
    busy: Boolean,
    onWear: (String) -> Unit,
) {
    val dimens = WyrThemeAccessors.dimens
    // Opened on the theme worn, which may be past the screen's edge.
    val row = rememberLazyListState(initialFirstVisibleItemIndex = owned.indexOf(worn).coerceAtLeast(0))
    LazyRow(
        state = row,
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(owned, key = { it.id }) { theme -> ThemeTile(theme, worn = theme.id == worn.id, busy, onWear) }
    }
}

@Composable
private fun ThemeTile(
    theme: GameTheme,
    worn: Boolean,
    busy: Boolean,
    onWear: (String) -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val strings = LocalStrings.current.shopScreen
    val outline = RoundedCornerShape(dimens.radiusPreview + dimens.tileOutline)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dimens.spaceXs),
        modifier =
            Modifier
                .width(dimens.themeTileWidth)
                .selectable(
                    selected = worn,
                    enabled = !busy,
                    role = Role.RadioButton,
                    onClick = tapped("shop.apply", mapOf(AnalyticsProperty.THEME to theme.id)) { onWear(theme.id) },
                ),
    ) {
        ThemePreview(
            theme = theme,
            height = dimens.themeTileHeight,
            compact = true,
            modifier =
                Modifier
                    .border(dimens.tileOutline, if (worn) colors.headingAccent else Color.Transparent, outline)
                    .padding(dimens.tileOutline),
        )
        Text(
            text = themeName(theme.id, strings),
            color = if (worn) colors.headingAccent else colors.primaryText,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (worn) Text(text = strings.active, color = colors.muted, fontSize = WyrTypeScale.statLabel)
    }
}

/**
 * What stands between the player and buying, said once over the themes: a guest's why and the way to
 * register; too few points; how the last purchase or read failed.
 */
@Composable
private fun Notes(
    state: ShopState,
    shared: Strings,
    onOpenAuth: () -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val strings = shared.shopScreen

    if (state.isGuest) {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceSm)) {
            Text(text = strings.registerToBuy, color = colors.primaryText)
            Button(onClick = tapped("shop.open_auth", onClick = onOpenAuth)) {
                Text(shared.accountScreens.openAuth)
            }
        }
    } else if (state.tooFewPoints) {
        Text(text = shared.accountScreens.notEnoughPoints, color = colors.muted)
    }
    (state.buyFailure ?: state.readFailure)?.let { failure ->
        // A purchase refused for too few points says so above already, from the points read after it.
        if (failure.error != DomainError.NOT_ENOUGH_POINTS && failure.error != DomainError.ACCOUNT_REQUIRED) {
            Text(
                text = failureText(failure, shared, read = state.buyFailure == null),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** A theme on sale on its card: its preview, its name, and *Купи ·* its price, which opens the dialog. */
@Composable
private fun SaleCard(
    theme: GameTheme,
    onSale: ShopTheme,
    state: ShopState,
    actions: ShopActions,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val strings = LocalStrings.current.shopScreen

    Surface(
        color = colors.surface,
        shape = RoundedCornerShape(dimens.radiusCard),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(dimens.spaceSm),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceSm),
        ) {
            ThemePreview(theme = theme, height = dimens.themePreviewHeight)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm),
                modifier = Modifier.padding(horizontal = dimens.spaceXs),
            ) {
                Text(
                    text = themeName(theme.id, strings),
                    color = colors.primaryText,
                    fontSize = WyrTypeScale.sectionTitle,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick =
                        tapped(
                            "shop.buy",
                            mapOf(AnalyticsProperty.THEME to theme.id),
                        ) { actions.askToBuy(theme.id) },
                    enabled = state.canBuy(onSale),
                ) { PriceText(onSale.price) }
            }
        }
    }
}

/** *Купи ·*, the coin and [price], which a screen reader hears as *Купи · Поени: 220*. */
@Composable
private fun PriceText(price: Int) {
    val shared = LocalStrings.current
    val template = shared.shopScreen.buy
    PointsText(template = template, points = price, spoken = template.fill(shared.points.fill(price)))
}

/**
 * The dialog before a purchase: the theme larger, so the player knows what they get, its name in the
 * question, and *Купи* with its price, or *Откажи*. Only *Купи* buys.
 */
@Composable
private fun ConfirmPurchase(
    theme: GameTheme,
    onSale: ShopTheme,
    actions: ShopActions,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val shared = LocalStrings.current
    val strings = shared.shopScreen

    AlertDialog(
        onDismissRequest = actions::cancelBuy,
        title = { Text(strings.confirmBuy.fill(themeName(theme.id, strings))) },
        text = { ThemePreview(theme = theme, height = dimens.themePreviewLargeHeight, large = true) },
        // The theme's, not Material's own container and text colours (CLAUDE.md §5b).
        containerColor = colors.surface,
        titleContentColor = colors.primaryText,
        textContentColor = colors.primaryText,
        confirmButton = {
            Button(
                onClick =
                    tapped(
                        "shop.buy_confirm",
                        mapOf(AnalyticsProperty.THEME to theme.id),
                        onClick = actions::buy,
                    ),
            ) {
                PriceText(onSale.price)
            }
        },
        dismissButton = {
            TextButton(
                onClick = tapped("shop.buy_cancel", onClick = actions::cancelBuy),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.headingAccent),
            ) { Text(shared.cancel) }
        },
    )
}

/** A read that failed with nothing to show, and Try again. */
@Composable
private fun Failure(
    failure: ShopFailure,
    shared: Strings,
    retry: () -> Unit,
    busy: Boolean,
) {
    val dimens = WyrThemeAccessors.dimens
    Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceSm)) {
        Text(text = failureText(failure, shared, read = true), color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = tapped("shop.try_again", onClick = retry), enabled = !busy) {
            Text(shared.tryAgain)
        }
    }
}

/** How [failure] reads, in a few words: of a [read] of the shop, or of a purchase. */
internal fun failureText(
    failure: ShopFailure,
    shared: Strings,
    read: Boolean,
): String {
    val account = shared.accountScreens
    return when (failure.error) {
        DomainError.NETWORK -> {
            account.offline
        }

        DomainError.ALREADY_OWNED -> {
            shared.shopScreen.alreadyOwned
        }

        DomainError.NOT_ENOUGH_POINTS -> {
            account.notEnoughPoints
        }

        DomainError.ACCOUNT_REQUIRED -> {
            shared.shopScreen.registerToBuy
        }

        DomainError.RATE_LIMITED -> {
            failure.retryAfter?.let { account.tooManyTries.fill(it.inWholeSeconds) } ?: account.tooManyTriesNoWait
        }

        else -> {
            if (read) shared.shopScreen.unread else account.somethingWrong
        }
    }
}
