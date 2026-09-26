package io.ntole.wyr.admin.moderation

import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.moderation.AdminToken
import io.ntole.wyr.core.domain.moderation.AuthorBlock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** A question named by its two options, as the screens and their notices name it. */
fun optionsOf(
    optionA: String,
    optionB: String,
): String = "\"$optionA\" or \"$optionB\""

/**
 * The categories [ids] name, in the order given, each by its Serbian name as [known] lists it, or by
 * its id when [known] does not list it, or has not been read.
 */
fun namesOf(
    ids: Collection<String>,
    known: List<Category>?,
): String = ids.joinToString(", ") { id -> nameOf(id, known) }

/** The category [id] by its Serbian name as [known] lists it, or by its id when it does not. */
fun nameOf(
    id: String,
    known: List<Category>?,
): String = known?.firstOrNull { it.id == id }?.nameSr ?: id

/**
 * How long before [now] [from] was, roughly, as a moderator reads a submission's age. A clock behind
 * the server's makes a new submission look a little in the future, which reads as just now too.
 */
fun ageOf(
    from: Instant,
    now: Instant,
): String {
    val age = now - from
    return when {
        age < 1.minutes -> "just now"
        age < 1.hours -> "${age.inWholeMinutes} min ago"
        age < 1.days -> "${age.inWholeHours} h ago"
        else -> "${age.inWholeDays} d ago"
    }
}

/** [instant] to the second, in UTC, as the server keeps it. */
fun shownTime(instant: Instant): String = Instant.fromEpochSeconds(instant.epochSeconds).toString()

/** Whether what is typed can be sent as the token, without ever showing any of it. */
fun tokenStatusOf(typed: String): String =
    when {
        typed.isBlank() -> "No token typed. Type the server's ADMIN_TOKEN to moderate."
        AdminToken.of(typed) == null -> "That cannot be a token: visible ASCII only, no spaces."
        else -> "Held in memory only, until Lock or the app closes."
    }

/**
 * An author's opaque id, a player id, shortened to its first [AUTHOR_SHOWN] characters: enough to tell
 * one author's questions from another's on screen, and nothing about who they are.
 */
fun shortAuthorOf(authorId: String): String = authorId.take(AUTHOR_SHOWN)

private const val AUTHOR_SHOWN = 8

/**
 * Who wrote a question: its author, shortened, and whether they are blocked when the server has said
 * ([blocked], null while it has not), or that it is a seed, or that no author is known, as for a
 * question whose author deleted their account.
 */
fun authorLabelOf(
    authorId: String?,
    isSeed: Boolean,
    blocked: Boolean?,
): String =
    when {
        authorId != null -> {
            val standing =
                when (blocked) {
                    true -> " · blocked"
                    false -> " · not blocked"
                    null -> ""
                }
            "Author ${shortAuthorOf(authorId)}$standing"
        }

        isSeed -> {
            "Seed"
        }

        else -> {
            "No author known"
        }
    }

/** What a block did, for the line under the screen it was done from. */
fun blockedNoticeOf(block: AuthorBlock): String {
    val rejected =
        when (block.rejectedSubmissions) {
            0 -> "nothing of theirs was pending"
            1 -> "1 pending question of theirs rejected"
            else -> "${block.rejectedSubmissions} pending questions of theirs rejected"
        }
    return "Blocked author ${shortAuthorOf(block.authorId)}: they can submit nothing until unblocked; $rejected."
}

/** What blocking the author [authorId] does, for the moderator to confirm it. */
fun blockWarningOf(authorId: String): String =
    "Author ${shortAuthorOf(authorId)} can submit no more questions until unblocked. Each question of " +
        "theirs still pending is rejected with the reason below, which they see, and its point paid back. " +
        "Their approved questions stay served."
