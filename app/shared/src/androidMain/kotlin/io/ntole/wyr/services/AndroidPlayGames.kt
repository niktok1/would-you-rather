package io.ntole.wyr.services

import android.app.Activity
import com.google.android.gms.games.GamesSignInClient
import io.ntole.wyr.core.domain.playgames.PlayGames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.android.gms.games.PlayGames as GooglePlayGames

/**
 * Google Play Games Services v2 on this device (CLAUDE.md §8a, *Play Games sign-in*), for the game
 * server whose OAuth client is [serverClientId]. Each call asks the activity on screen for its sign-in
 * client, or its players client for the player's name, on the main thread, and answers a failure as nothing done: none on screen, Play Games
 * refusing, or its task failing.
 *
 * Made only once `PlayGamesSdk.initialize` has run, which signs a player with a profile in by itself as
 * the game starts.
 */
internal class AndroidPlayGames(
    private val activities: ActivityTracker,
    private val serverClientId: String,
) : PlayGames {
    override val available: Boolean = true

    override suspend fun isAuthenticated(): Boolean = ask { isAuthenticated().resultOrNull()?.isAuthenticated } == true

    override suspend fun signIn(): Boolean = ask { signIn().resultOrNull()?.isAuthenticated } == true

    override suspend fun serverAuthCode(): String? =
        ask { requestServerSideAccess(serverClientId, false).resultOrNull() }?.takeIf { it.isNotBlank() }

    override suspend fun playerName(): String? =
        onActivity { activity ->
            GooglePlayGames
                .getPlayersClient(activity)
                .currentPlayer
                .resultOrNull()
                ?.displayName
        }?.trim()?.takeIf { it.isNotEmpty() }

    private suspend fun <T> ask(question: suspend GamesSignInClient.() -> T?): T? =
        onActivity { activity -> GooglePlayGames.getGamesSignInClient(activity).question() }

    private suspend fun <T> onActivity(question: suspend (Activity) -> T?): T? =
        withContext(Dispatchers.Main) {
            val activity = activities.current() ?: return@withContext null
            question(activity)
        }
}
