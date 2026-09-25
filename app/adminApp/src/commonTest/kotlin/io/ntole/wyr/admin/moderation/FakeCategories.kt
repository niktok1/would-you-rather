package io.ntole.wyr.admin.moderation

import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.category.CategoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The categories as the tests script them: every read counted in [reads] and answered by [read]. */
class FakeCategories : CategoryRepository {
    var reads = 0
        private set

    var read: suspend () -> List<Category> = { LISTED }

    private val listed = MutableStateFlow<List<Category>>(emptyList())

    override val categories: StateFlow<List<Category>> = listed.asStateFlow()

    override suspend fun refresh(): List<Category> {
        reads++
        return read().also { listed.value = it }
    }

    companion object {
        val FOOD = Category(id = "FOOD", nameSr = "Храна", nameEn = "Food")
        val ETHICS = Category(id = "ETHICS", nameSr = "Етика", nameEn = "Ethics")
        val SUPERPOWERS = Category(id = "SUPERPOWERS", nameSr = "Супермоћи", nameEn = "Superpowers")
        val ABSURD = Category(id = "ABSURD", nameSr = "Апсурдно", nameEn = "Absurd")

        /** In the order of categories, as the server lists them. */
        val LISTED = listOf(FOOD, ETHICS, SUPERPOWERS, ABSURD)
    }
}
