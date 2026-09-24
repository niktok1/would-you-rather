package io.ntole.wyr.admin.moderation

import io.ntole.wyr.core.domain.moderation.AdminToken
import io.ntole.wyr.core.domain.question.Category
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** A question named by its two options, as the screens and their notices name it. */
fun optionsOf(
    optionA: String,
    optionB: String,
): String = "\"$optionA\" or \"$optionB\""

/** [categories] by name, in the order given, which is declaration order wherever a set is sorted. */
fun namesOf(categories: Set<Category>): String = categories.joinToString(", ") { it.name }

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
