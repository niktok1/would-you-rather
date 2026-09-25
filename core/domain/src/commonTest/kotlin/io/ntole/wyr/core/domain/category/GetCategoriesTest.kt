package io.ntole.wyr.core.domain.category

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GetCategoriesTest {
    @Test
    fun `every call reads the categories from the server again`() =
        runTest {
            val repository = CountingCategories()
            val getCategories = GetCategories(repository)

            assertEquals(FIRST, getCategories())
            assertEquals(FIRST, getCategories())

            // Never answered from memory: a moderator may have added one since.
            assertEquals(2, repository.reads)
        }

    private class CountingCategories : CategoryRepository {
        var reads = 0

        override val categories: StateFlow<List<Category>> = MutableStateFlow(emptyList())

        override suspend fun refresh(): List<Category> {
            reads++
            return FIRST
        }
    }

    private companion object {
        val FIRST = listOf(Category(id = "FOOD", nameSr = "Храна", nameEn = "Food"))
    }
}
