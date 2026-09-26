package io.ntole.wyr.server.push

import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.server.db.Db
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Tells an author, on every device they registered, that a moderator decided their question (CLAUDE.md
 * §8a, *Push tokens*).
 *
 * Launched once the decision has committed, and not waited for: it runs in [scope], off the request,
 * as a push is best effort, so nothing it does can hold up, fail or undo the decision. Every failure is
 * logged and dropped, a token Firebase calls unregistered is forgotten, and nothing is retried.
 */
class DecisionNotifier(
    private val db: Db,
    private val sender: PushSender,
    private val scope: CoroutineScope,
) {
    /** Sends [decided]'s author the push for its decision, and returns the job doing it, for a test to wait on. */
    fun submissionDecided(decided: SubmissionDto): Job =
        scope.launch {
            try {
                val message = decisionMessage(decided) ?: return@launch
                val recipients = db.query { PushTokenStore.recipientsOf(decided.id) } ?: return@launch
                recipients.tokens.forEach { token -> push(token, message, recipients.playerId, decided.id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                log.warn("the pushes for question ${decided.id}'s decision failed: ${failed::class.java.simpleName}")
            }
        }

    private suspend fun push(
        token: String,
        message: PushMessage,
        playerId: String,
        questionId: String,
    ) {
        when (sender.send(token, message)) {
            PushOutcome.SENT -> {
                Unit
            }

            PushOutcome.UNREGISTERED -> {
                db.query { PushTokenStore.forget(token) }
                log.info("forgot a push token of player $playerId that no device has any more")
            }

            PushOutcome.FAILED -> {
                log.warn("a push for question $questionId's decision to a device of player $playerId failed")
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(DecisionNotifier::class.java)
    }
}

/** What a push data field `type` says of a decision's push, for the app to act on. */
const val SUBMISSION_DECIDED: String = "submission_decided"

/**
 * The push telling [decided]'s author what became of it, in Serbian Cyrillic, the game's first language
 * (CLAUDE.md §8f; the client may put it into the language shown later), or null for a question no
 * decision leaves where it stands. The question is named as My questions names it, its options joined
 * by *или*, and a rejection gives the moderator's reason. The data names the question and its status,
 * so the app can open the question without reading the text.
 */
internal fun decisionMessage(decided: SubmissionDto): PushMessage? {
    val question = "${decided.optionA} или ${decided.optionB}"
    val (title, body) =
        when (decided.status) {
            QuestionStatus.APPROVED -> {
                "Твоје питање је одобрено" to question
            }

            QuestionStatus.REJECTED -> {
                "Твоје питање није одобрено" to
                    (decided.rejectionReason?.let { reason -> "$question. Разлог: $reason" } ?: question)
            }

            else -> {
                return null
            }
        }
    return PushMessage(
        title = title,
        body = body,
        data = mapOf("type" to SUBMISSION_DECIDED, "questionId" to decided.id, "status" to decided.status.name),
    )
}
