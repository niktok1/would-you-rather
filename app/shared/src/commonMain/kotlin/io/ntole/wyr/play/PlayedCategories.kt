package io.ntole.wyr.play

import io.ntole.wyr.core.domain.category.Category

/**
 * The categories played (CLAUDE.md §8d, *Categories*): [selected], by id, none for every category, as
 * the repository holds them, and [known], every category as the app last read them from the server,
 * oldest first, to name the selected ones by.
 */
data class PlayedCategories(
    val selected: Set<String> = emptySet(),
    val known: List<Category> = emptyList(),
)
