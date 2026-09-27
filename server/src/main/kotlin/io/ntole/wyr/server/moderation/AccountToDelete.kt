package io.ntole.wyr.server.moderation

import io.ntole.wyr.core.player.DeleteAccountRequest
import io.ntole.wyr.server.auth.usernameOrNull
import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.plugins.requireValidId
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select

/**
 * The account a moderator asked to delete (CLAUDE.md §8a, *Deleting an account*), named by its
 * username or by its id, the player's.
 */
internal sealed interface AccountToDelete {
    /**
     * The account whose username is [username], lower-cased as a login compares it, or nobody's when
     * [username] is null: a name the username rules refuse, which no account can have.
     */
    data class ByUsername(
        val username: String?,
    ) : AccountToDelete

    data class ById(
        val playerId: String,
    ) : AccountToDelete

    /**
     * The id of the player this names, their row locked, as `AccountDeletion.delete` then locks it
     * again (CLAUDE.md §4), or [ApiFailure.playerNotFound]. Must run inside a transaction. Of two
     * deletions of one player racing, the second waits on the lock, then finds no row, and is 404.
     */
    fun lockedPlayerId(): String {
        val named =
            when (this) {
                is ByUsername -> username?.let { name -> Players.username eq name }
                is ById -> Players.id eq playerId
            } ?: throw ApiFailure.playerNotFound()
        return Players
            .select(Players.id)
            .where { named }
            .forUpdate()
            .singleOrNull()
            ?.get(Players.id)
            ?: throw ApiFailure.playerNotFound()
    }
}

/**
 * The account [request] names, or an [ApiFailure.validation] for what no correct client sends: neither
 * or both of a username and an id, either blank once trimmed, or an id holding a control character. A
 * username is trimmed and lower-cased, as a login's is, and one the rules refuse names nobody, as it
 * logs nobody in.
 */
internal fun checkedAccountDeletion(request: DeleteAccountRequest): AccountToDelete {
    val username = request.username?.trim()
    val accountId = request.accountId?.trim()
    if ((username == null) == (accountId == null)) {
        throw ApiFailure.validation("name exactly one of username and accountId")
    }
    if (accountId != null) {
        requireValidId("accountId", accountId)
        return AccountToDelete.ById(accountId)
    }
    if (username.isNullOrEmpty()) throw ApiFailure.validation("username is blank")
    return AccountToDelete.ByUsername(usernameOrNull(username))
}
