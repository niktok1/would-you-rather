package io.ntole.wyr.core.data.playgames

import io.ntole.wyr.core.auth.PlayGamesSignInRequest
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.session.withSessionRecovery
import io.ntole.wyr.core.domain.playgames.PlayGamesRepository
import io.ntole.wyr.core.network.api.AuthApi

/**
 * Signs in with a Play Games server auth code through [AuthApi] (CLAUDE.md §8a, *Play Games sign-in*),
 * and stores the session answered in place of the device's, as a login's is, which settles who plays
 * here ([io.ntole.wyr.core.data.session.PlayGamesSettled]).
 *
 * The sign-in goes with the session's bearer, so a Play Games player linked to nobody is linked to the
 * player playing, who keeps everything they have. It goes through [withSessionRecovery], as a
 * registration does: a dead session is a 401 before the code goes anywhere, so the retry sends it,
 * still unspent, as the fresh guest minted for it. Only a sign-in the server took changes the stored
 * session, and only while the device still plays as the player it went out as
 * ([DefaultSessionRepository.replaceIfStill]): the exchange with Google takes seconds, and a login or a
 * logout landing meanwhile stands.
 */
public class DefaultPlayGamesRepository(
    private val api: AuthApi,
    private val session: DefaultSessionRepository,
) : PlayGamesRepository {
    override fun isSettled(): Boolean = session.isPlayGamesSettled()

    override suspend fun signIn(serverAuthCode: String): String? {
        var sentAs: String? = null
        val signedIn =
            session.withSessionRecovery {
                // Each attempt's own: a dead session's retry goes out as the fresh guest.
                sentAs = session.current()
                api.playGames(PlayGamesSignInRequest(serverAuthCode))
            }
        return signedIn.playerId.takeIf { session.replaceIfStill(sentAs, signedIn) }
    }
}
