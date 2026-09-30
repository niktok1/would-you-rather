package io.ntole.wyr.admin.moderation

import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.category.CategoryRules
import io.ntole.wyr.core.domain.moderation.AccountRef
import io.ntole.wyr.core.domain.moderation.AdminToken
import io.ntole.wyr.core.domain.moderation.ModeratedQuestion
import io.ntole.wyr.core.domain.moderation.QuestionCursor
import io.ntole.wyr.core.domain.moderation.QuestionFilter
import io.ntole.wyr.core.domain.moderation.RejectionReason
import io.ntole.wyr.core.domain.moderation.ReportedQuestion
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import kotlin.jvm.JvmInline

/**
 * Everything the moderation app shows: the admin token as typed, the pending queue, the reported
 * questions, the list of every question, the categories, and what the moderator has picked or typed
 * for each pending one, for a category, and for an account to delete.
 *
 * Nothing is read until the moderator asks, since no token has been typed yet. One action runs at a
 * time ([running]), so what the screens show changes in the order things happened.
 */
data class ModerationState(
    /**
     * The admin token exactly as typed, held here, in memory, and nowhere else: not with the screen's
     * saved state, nor anywhere on the device, so it is typed again once the app closes, and Lock
     * clears it (CLAUDE.md §8d, *Moderation*). [SecretText] keeps it out of this state's `toString`.
     */
    val adminToken: SecretText = SecretText(""),
    val pending: PendingQueue = PendingQueue(),
    val reports: ReportList = ReportList(),
    val questions: QuestionList = QuestionList(),
    val categories: CategoryList = CategoryList(),
    /** The account the moderator is naming to delete, on its player's request, and how the last went. */
    val accounts: AccountDeletions = AccountDeletions(),
    /**
     * For each pending question, by id, the approval's categories and the rejection's reason, the
     * same whichever screen it is decided from.
     */
    val drafts: Map<String, DecisionDraft> = emptyMap(),
    /** The approved question the moderator asked to retire, waiting for them to confirm it. */
    val retiring: Retiring? = null,
    /**
     * Whether each author is blocked from submitting, by their opaque id, as the server last answered a
     * block or an unblock of them since the token was typed: the one place it says (CLAUDE.md §8d,
     * *Moderation*, *Authors*). An author not in it stands as nothing here says.
     */
    val authors: Map<String, Boolean> = emptyMap(),
    /** The author the moderator asked to block, with the reason typed, waiting for them to confirm it. */
    val blocking: BlockDraft? = null,
    /** The account the moderator asked to delete, waiting for them to confirm it. */
    val deleting: AccountRef? = null,
    /** The action in flight, or `null` when idle. */
    val running: Running? = null,
    /**
     * How many times Lock has been pressed. The token field is made anew each time, so its undo
     * history, which holds what was typed or pasted, goes with the token; and an action a Lock
     * cancelled, whose cleanup may run after the next one started, leaves that one's state alone.
     */
    val locks: Int = 0,
) {
    val isBusy: Boolean get() = running != null

    /**
     * The token every request sends, or `null` while what is typed cannot be one (blank, or more than
     * visible ASCII once trimmed), which keeps every request from going.
     */
    val token: AdminToken? get() = AdminToken.of(adminToken.text)

    /** Whether a request can go now: a token, and nothing else in flight. */
    val canSend: Boolean get() = !isBusy && token != null

    fun draftOf(questionId: String): DecisionDraft = drafts[questionId] ?: DecisionDraft()

    /**
     * The categories [questionId]'s author filed it under, as the queue or the list last listed it, or
     * `null` when neither lists it.
     */
    fun authorsCategoriesOf(questionId: String): Set<String>? =
        pending.submissions?.firstOrNull { it.id == questionId }?.categories
            ?: questions.questions?.firstOrNull { it.id == questionId }?.categories

    /**
     * Whether Approve can go for [questionId], filed by its author under [authorsCategories]: one filed
     * under none only with a category picked (CLAUDE.md §8d, *Categories*, *Nothing fits*).
     */
    fun canApprove(
        questionId: String,
        authorsCategories: Set<String>,
    ): Boolean = canSend && (authorsCategories.isNotEmpty() || draftOf(questionId).categories.isNotEmpty())

    /**
     * The reason to reject [questionId] with, or `null` while the one typed breaks a rule the server
     * holds a reason to, which keeps its Reject off: the server refuses such a reason as a malformed
     * request (CLAUDE.md §8d, *Moderation*).
     */
    fun rejectionOf(questionId: String): RejectionReason? = RejectionReason.of(draftOf(questionId).reason)

    /**
     * The question [questionId] as [screen] shows it: a row of the list of every question, or a
     * reported one's; `null` on any other screen, and when it does not show it.
     */
    fun shownOn(
        screen: Screen,
        questionId: String,
    ): ModeratedQuestion? =
        when (screen) {
            Screen.QUESTIONS -> questions.questions?.firstOrNull { it.id == questionId }
            Screen.REPORTS -> reports.reports?.firstOrNull { it.question.id == questionId }?.question
            Screen.PENDING, Screen.CATEGORIES, Screen.ACCOUNTS -> null
        }

    /**
     * The reason to block the author [blocking] names with, or `null` while the one typed breaks a
     * rule the server holds it to, a rejection's, which keeps Block off.
     */
    val blockReason: RejectionReason? get() = blocking?.let { RejectionReason.of(it.reason) }

    /** What [screen] shows of its actions' outcomes. */
    fun outcomesOf(screen: Screen): Outcomes =
        when (screen) {
            Screen.PENDING -> pending.outcomes
            Screen.REPORTS -> reports.outcomes
            Screen.QUESTIONS -> questions.outcomes
            Screen.CATEGORIES -> categories.outcomes
            Screen.ACCOUNTS -> accounts.outcomes
        }
}

/**
 * The statuses the list of every question can be filtered by, in declaration order: all but
 * [SubmissionStatus.OTHER], which holds whatever this build cannot name.
 */
val LISTABLE_STATUSES: List<SubmissionStatus> = SubmissionStatus.entries.filter { it != SubmissionStatus.OTHER }

/** The app's screens, each with the outcomes of the actions started from it. */
enum class Screen(
    val label: String,
) {
    PENDING("Pending"),
    REPORTS("Reports"),
    QUESTIONS("All questions"),
    CATEGORIES("Categories"),
    ACCOUNTS("Accounts"),
}

/**
 * The submissions waiting for a decision, oldest first, as the server last listed them, or `null`
 * until a read works. It is read on Load pending and again after every decision, whatever became of
 * it: a decision changes the queue, and one whose answer was lost, or that another moderator beat,
 * may have changed it too. Only a decision refused as a wrong token or by the rate limit, which
 * decided nothing, is not followed by a read. A read that fails keeps what was listed and says why in
 * [failure].
 */
data class PendingQueue(
    val submissions: List<Submission>? = null,
    /** Why the last read failed, or `null` once one works. */
    val failure: Failure? = null,
    val outcomes: Outcomes = Outcomes(),
)

/**
 * The questions players reported, most reported first, as the server last listed them, or `null` until
 * a read works (CLAUDE.md §8d, *Moderation*, *Reports*). It is read on Load reports and again after
 * every dismissal, whatever became of it, since a dismissal takes a question off it; a retirement or
 * restoration shows the question the server answered with in its row, as the list of every question
 * does, and one that failed reads it again. Nothing is read again after a refusal as a wrong token or
 * by the rate limit, which did nothing. A read that fails keeps what was listed and says why in
 * [failure].
 */
data class ReportList(
    val reports: List<ReportedQuestion>? = null,
    /** Why the last read failed, or `null` once one works. */
    val failure: Failure? = null,
    val outcomes: Outcomes = Outcomes(),
)

/**
 * The author [authorId] the moderator asked to block from the question [questionId] on [from], and the
 * reason typed, exactly as typed, which each of their pending questions is rejected with.
 */
data class BlockDraft(
    val authorId: String,
    val questionId: String,
    val from: Screen,
    val reason: String = "",
)

/** The approved question [questionId] the moderator asked to retire from [from], until they confirm it. */
data class Retiring(
    val questionId: String,
    val from: Screen,
)

/**
 * The list of every question, seeds included, newest first, at [filter]: the pages read so far, or
 * `null` until one is, and [next], where the page after them starts, `null` on the last. Changing
 * the filter drops what was read for the one before; Load reads the first page, Load more the next.
 * A retirement or restoration shows the question the server answered with in its row, and a
 * decision, whose answer lacks what the row shows, reads again as many pages as were shown, as does
 * a move that failed, so the list shows what the server holds without losing the moderator's place;
 * nothing is read again after a refusal as a wrong token or by the rate limit, which did nothing. A
 * read that fails keeps what was listed and says why in [failure].
 */
data class QuestionList(
    val filter: QuestionFilter = QuestionFilter(),
    val questions: List<ModeratedQuestion>? = null,
    val next: QuestionCursor? = null,
    /** Why the last read failed, or `null` once one works. */
    val failure: Failure? = null,
    val outcomes: Outcomes = Outcomes(),
) {
    val canLoadMore: Boolean get() = questions != null && next != null

    /** The pending ones among the questions listed, whose drafts the list keeps. */
    val pendingIds: Set<String>
        get() =
            questions
                .orEmpty()
                .filter { it.status == SubmissionStatus.PENDING }
                .map { it.id }
                .toSet()
}

/**
 * Every category, as the server last listed them, oldest first, or `null` until a read works: what an
 * approval's and the filter's chips offer, and what names the categories a question is filed under,
 * one not listed by its id. Read with every Load, before what it loads, since a moderator adds
 * categories without a build, and again after every add and rename, whatever became of it; the list
 * needs no token, but it is read as the actions that do are, one at a time. A read that fails keeps
 * what was listed and says why in [failure].
 */
data class CategoryList(
    val categories: List<Category>? = null,
    /** Why the last read failed, or `null` once one works. */
    val failure: Failure? = null,
    /** The category being written for Add, exactly as typed, an empty id for the server to make. */
    val adding: CategoryDraft = CategoryDraft(),
    /** The category whose names are being put right, by its id, with the names as typed, or `null`. */
    val renaming: CategoryDraft? = null,
    /** Why the last Add failed, shown under its form until the next add or rename or Load. */
    val addFailure: Failure? = null,
    /** Why the last rename failed, shown under its category until the next add or rename or Load. */
    val renameFailure: Failure? = null,
    /** What the last add or rename that worked did; no failures, which are the two above. */
    val outcomes: Outcomes = Outcomes(),
)

/**
 * A category as the moderator types it (CLAUDE.md §8d, *Categories*): its [id], blank for the server
 * to make one from [nameEn] when adding, and fixed when renaming, and its names in Serbian and
 * English, all exactly as typed; the server trims the names.
 */
data class CategoryDraft(
    val id: String = "",
    val nameSr: String = "",
    val nameEn: String = "",
) {
    /** The id to send: `null`, for the server to make one, while none is typed. */
    val idToSend: String? get() = id.ifBlank { null }

    /**
     * Whether the server would take it: both names, and the id when one is typed, by its
     * [CategoryRules], so nothing it would refuse as malformed is sent.
     */
    val isValid: Boolean
        get() =
            CategoryRules.isName(nameSr) && CategoryRules.isName(nameEn) &&
                (idToSend?.let(CategoryRules::isId) ?: true)
}

/**
 * An account to delete on its player's request (CLAUDE.md §8a, *Deleting an account*, *By a moderator*),
 * named by its username or its id exactly as typed, and why the last deletion failed, shown under the
 * field until the next one, or in [outcomes] what the last that worked did.
 */
data class AccountDeletions(
    val typed: String = "",
    val failure: Failure? = null,
    val outcomes: Outcomes = Outcomes(),
) {
    /** The account [typed] names, a username or an id, or `null` while nothing is typed. */
    val named: AccountRef? get() = AccountRef.of(typed)
}

/**
 * The outcomes of the actions started from one screen: why each action on a question failed, by the
 * question's id, shown under it, or at the top once the screen no longer lists it, kept until the
 * next action on it or the screen's own Load; and what the last action that worked did, in a line,
 * until the next action.
 */
data class Outcomes(
    val failures: Map<String, ItemFailure> = emptyMap(),
    val notice: String? = null,
)

/**
 * What the moderator has picked for one pending submission: the [categories], ids, an approval files
 * it under in place of the author's (none keeps the author's), and the [reason] to reject it with,
 * exactly as typed. [newCategory] is the category being made from its author's suggestion (CLAUDE.md
 * §8d, *Categories*, *Nothing fits*), as typed, or `null`: once added, it is picked for the approval.
 */
data class DecisionDraft(
    val categories: Set<String> = emptySet(),
    val reason: String = "",
    val newCategory: CategoryDraft? = null,
)

/** Why an action on one question failed, and the question's options, to name it by. */
data class ItemFailure(
    val question: String,
    val failure: Failure,
)

/** The action in flight, and the question it acts on, if it acts on one. */
data class Running(
    val action: Action,
    val questionId: String? = null,
)

enum class Action {
    LOAD_PENDING,
    APPROVE,
    REJECT,
    LOAD_REPORTS,
    DISMISS_REPORTS,
    BLOCK_AUTHOR,
    UNBLOCK_AUTHOR,
    LOAD_QUESTIONS,
    LOAD_MORE,
    RETIRE,
    RESTORE,
    LOAD_CATEGORIES,
    ADD_CATEGORY,
    RENAME_CATEGORY,
    DELETE_ACCOUNT,
}

/**
 * Text typed into a secret field. [toString] never shows it, so no dump of a state that holds it, in
 * a log line or a failed assertion, carries the secret.
 */
@JvmInline
value class SecretText(
    val text: String,
) {
    override fun toString(): String = "SecretText(redacted)"
}
