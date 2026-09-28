package io.ntole.wyr.update

import androidx.compose.runtime.Composable

/**
 * How this platform gets the new version, the one button the update screen shows (CLAUDE.md §8e,
 * *The build on every request*): what it says, [way], and what it does, [go].
 */
class UpdateButton(
    val way: UpdateWay,
    val go: () -> Unit,
)

/** What the update screen's button does, and so what it says. */
enum class UpdateWay {
    /** Opens the game's page in the store: Android's Play Store. */
    STORE,

    /** Loads the page again, which fetches the new build: the web's. */
    RELOAD,
}

/**
 * This platform's way to the new version, or null where the app has none to offer: on the desktop
 * and on iOS, for now, whose builds are not in a store yet.
 */
@Composable
internal expect fun rememberUpdateButton(): UpdateButton?
