package io.ntole.wyr.core.domain.category

import kotlinx.coroutines.flow.StateFlow

/**
 * Every category questions can be filed under, as the server lists them (CLAUDE.md §8d,
 * *Categories*). Implemented in `:core:data`.
 *
 * Kept in memory only, for the app's life, and read again when asked: a moderator adds categories
 * and puts their names right without a build, so no list is kept longer than that. Reading needs no
 * player session and touches none: the list is the same for everybody.
 */
public interface CategoryRepository {
    /**
     * The categories as [refresh] last read them, in the order of categories, when each was added,
     * oldest first; empty until a read works. A read that fails leaves it as it was.
     */
    public val categories: StateFlow<List<Category>>

    /**
     * Reads every category from the server again, keeps it in [categories], and returns it.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure, leaving [categories] as it
     *   was.
     */
    public suspend fun refresh(): List<Category>
}
