package io.ntole.wyr.categories

import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.error.DomainError

/**
 * What the Categories screen shows (CLAUDE.md §8d, *Categories*): the categories as last read, those
 * the search finds, and what is ticked, which is played only once the player taps Play.
 */
data class CategoriesState(
    /** What is typed in the search field, as typed. */
    val query: String = "",
    /** The ids of the categories ticked, not played yet; none is every category, All. */
    val ticked: Set<String> = emptySet(),
    /** Every category as last read from the server, in the server's order, oldest first. */
    val categories: List<Category> = emptyList(),
    /** Those of [categories] the [query] finds, in the same order: every one while it is blank. */
    val found: List<Category> = emptyList(),
    /** While the categories are being read. */
    val isLoading: Boolean = false,
    /** How the last read failed, until one is asked for again; the categories read before stay listed. */
    val failure: DomainError? = null,
    /** While what is ticked is being played, when nothing can be ticked any more. */
    val isPlaying: Boolean = false,
    /** Once what is ticked is played: the screen goes back to Play, which shows a question from it. */
    val played: Boolean = false,
)
