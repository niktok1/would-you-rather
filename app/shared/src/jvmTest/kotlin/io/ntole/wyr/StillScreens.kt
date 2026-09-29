package io.ntole.wyr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.ntole.wyr.account.AccountActions
import io.ntole.wyr.account.AuthMode
import io.ntole.wyr.categories.CategoriesActions
import io.ntole.wyr.shop.ShopActions
import io.ntole.wyr.submit.SubmitActions
import io.ntole.wyr.theme.LocalPageDrawn
import io.ntole.wyr.theme.WholePageArt
import io.ntole.wyr.theme.WyrThemeAccessors
import org.jetbrains.skia.Image

/*
 * What the tests that draw the game's screens as stills share (ScreenshotsTest, StoreGraphicsTest): the
 * page under a screen as the app draws it, actions that do nothing, and a scene rendered once every
 * animation has ended.
 */

/** [content] over the page and its art, as `App`'s `WholePage` draws them, so the screen draws neither. */
@Composable
internal fun OnWholePage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        WholePageArt(Modifier.matchParentSize())
        CompositionLocalProvider(LocalPageDrawn provides true) {
            Surface(
                color = Color.Transparent,
                contentColor = WyrThemeAccessors.colors.primaryText,
                modifier = Modifier.fillMaxSize(),
                content = content,
            )
        }
    }
}

/** How long a still waits for every animation to end: past the Play reveal's 3-second count up, the longest. */
internal const val SETTLED_NANOS: Long = 4_000_000_000L

private const val FRAME_NANOS: Long = 1_000_000_000L / 60

/** The scene once every animation has ended, drawn a frame at a time up to then, as a screen draws. */
internal fun ImageComposeScene.renderSettled(): Image {
    (0..SETTLED_NANOS / FRAME_NANOS).forEach { frame -> renderAt(frame * FRAME_NANOS) }
    return render(SETTLED_NANOS)
}

internal object NoAccountActions : AccountActions {
    override fun refresh() = Unit

    override fun authShown() = Unit

    override fun setAuthMode(mode: AuthMode) = Unit

    override fun leftAuth() = Unit

    override fun setRegisterUsername(text: String) = Unit

    override fun setRegisterPassword(text: String) = Unit

    override fun toggleShowRegisterPassword() = Unit

    override fun register() = Unit

    override fun setLoginUsername(text: String) = Unit

    override fun setLoginPassword(text: String) = Unit

    override fun logIn() = Unit

    override fun cancelLogIn() = Unit

    override fun logOut() = Unit

    override fun deleteAccount() = Unit
}

internal object NoCategoriesActions : CategoriesActions {
    override fun search(query: String) = Unit

    override fun toggle(id: String) = Unit

    override fun selectAll() = Unit

    override fun refresh() = Unit

    override fun play() = Unit
}

internal object NoShopActions : ShopActions {
    override fun refresh() = Unit

    override fun askToBuy(themeId: String) = Unit

    override fun cancelBuy() = Unit

    override fun buy() = Unit

    override fun boughtWorn() = Unit
}

internal object NoSubmitActions : SubmitActions {
    override fun refresh() = Unit

    override fun leftForm() = Unit

    override fun setOptionA(text: String) = Unit

    override fun setOptionB(text: String) = Unit

    override fun toggleCategory(id: String) = Unit

    override fun submit() = Unit
}
