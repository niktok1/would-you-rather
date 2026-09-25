package io.ntole.wyr.core.data.category

import io.ntole.wyr.core.data.mapper.runApi
import io.ntole.wyr.core.data.mapper.toDomain
import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.category.CategoryRepository
import io.ntole.wyr.core.network.api.CategoryApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads the categories through [runApi] alone, never `withSessionRecovery`: the list needs no
 * session, so there is none to ensure or recover, and a read can never mint a guest. Kept in memory,
 * in the order the server sent them, until the next read.
 */
public class DefaultCategoryRepository(
    private val api: CategoryApi,
) : CategoryRepository {
    private val read = MutableStateFlow<List<Category>>(emptyList())

    override val categories: StateFlow<List<Category>> = read.asStateFlow()

    override suspend fun refresh(): List<Category> {
        val listed = runApi { api.all() }.categories.map { it.toDomain() }
        read.value = listed
        return listed
    }
}
