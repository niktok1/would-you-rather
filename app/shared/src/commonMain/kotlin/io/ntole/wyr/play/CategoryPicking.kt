package io.ntole.wyr.play

import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.error.DomainError

/**
 * The categories played (CLAUDE.md §8d, *Categories*): [selected], by id, none for every category, as
 * the repository holds them, and [known], every category as the app last read them from the server,
 * oldest first, to name the selected ones by and to list in the picker.
 */
data class PlayedCategories(
    val selected: Set<String> = emptySet(),
    val known: List<Category> = emptyList(),
)

/**
 * The category picker while it is open: what it has [ticked], by id, not played yet, and how the read
 * of the categories it opened with went: [isLoading] while it is in flight, and [failure] once it
 * failed, when the picker lists the categories read before, if any.
 */
data class CategoryPicking(
    val ticked: Set<String>,
    val isLoading: Boolean = false,
    val failure: DomainError? = null,
)
