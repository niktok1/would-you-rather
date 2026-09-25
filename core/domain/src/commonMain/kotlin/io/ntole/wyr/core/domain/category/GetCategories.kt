package io.ntole.wyr.core.domain.category

/**
 * Reads every category from the server again (CLAUDE.md §8d, *Categories*), as a picker does each
 * time it opens, and keeps it in [CategoryRepository.categories] for every screen that names one.
 *
 * Ensures no session, unlike the player's use cases: the list is the same for everybody, so the
 * server reads none, and the moderation app, which has no player, reads it too.
 */
public class GetCategories(
    private val categories: CategoryRepository,
) {
    /** @throws io.ntole.wyr.core.domain.error.WyrException as [CategoryRepository.refresh] does. */
    public suspend operator fun invoke(): List<Category> = categories.refresh()
}
