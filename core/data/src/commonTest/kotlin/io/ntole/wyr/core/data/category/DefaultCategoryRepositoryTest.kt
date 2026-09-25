package io.ntole.wyr.core.data.category

import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.category.CategoryDto
import io.ntole.wyr.core.category.CategoryListDto
import io.ntole.wyr.core.data.BASE_URL
import io.ntole.wyr.core.data.FakeServer
import io.ntole.wyr.core.data.session
import io.ntole.wyr.core.data.storeHolding
import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.category.GetCategories
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.api.CategoryApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Reading the categories through the real client, against [FakeServer]. */
class DefaultCategoryRepositoryTest {
    private val server = FakeServer()

    @Test
    fun `the categories are read in the server's order and kept for every screen`() =
        runTest {
            val categories = repositoryOver(storeHolding(session("a")))
            assertEquals(emptyList(), categories.categories.value, "nothing is known before a read")

            val read = GetCategories(categories)()

            assertEquals(FIRST, read)
            assertEquals(FIRST, categories.categories.value)
        }

    @Test
    fun `reading the categories needs no session and mints none`() =
        runTest {
            val store = storeHolding(null)

            repositoryOver(store).refresh()

            // Sent with no bearer at all, and nothing minted for it: the list is the same for everybody.
            assertEquals(listOf<String?>(null), server.categoriesSentAs)
            assertEquals(0, server.guestsMinted)
            assertEquals(null, store.read())
        }

    @Test
    fun `every read asks the server again and keeps what it answers`() =
        runTest {
            val categories = repositoryOver(storeHolding(null))
            categories.refresh()

            // A moderator adds a category meanwhile.
            val animals = CategoryDto(id = "ANIMALS", nameSr = "Животиње", nameEn = "Animals")
            server.categories = CategoryListDto(FakeServer.CATEGORIES.categories + animals)
            val read = categories.refresh()

            assertEquals(FIRST + Category(id = "ANIMALS", nameSr = "Животиње", nameEn = "Animals"), read)
            assertEquals(read, categories.categories.value)
            assertEquals(2, server.categoriesSentAs.size)
        }

    @Test
    fun `a read that fails keeps the categories read before`() =
        runTest {
            val categories = repositoryOver(storeHolding(null))
            categories.refresh()
            server.refuseCategoriesWith = HttpStatusCode.InternalServerError to ErrorCode.INTERNAL

            val failure = assertFailsWith<WyrException> { categories.refresh() }

            assertEquals(DomainError.SERVER, failure.error)
            assertEquals(FIRST, categories.categories.value)
        }

    private fun repositoryOver(store: SessionStore): DefaultCategoryRepository =
        DefaultCategoryRepository(CategoryApi(WyrHttpClient.create(BASE_URL, store, server.engine)))

    private companion object {
        /** [FakeServer.CATEGORIES] as the domain holds them, in the same order. */
        val FIRST =
            listOf(
                Category(id = "FOOD", nameSr = "Храна", nameEn = "Food"),
                Category(id = "LIFESTYLE", nameSr = "Начин живота", nameEn = "Lifestyle"),
                Category(id = "ETHICS", nameSr = "Етика", nameEn = "Ethics"),
                Category(id = "SUPERPOWERS", nameSr = "Супермоћи", nameEn = "Superpowers"),
                Category(id = "ABSURD", nameSr = "Апсурдно", nameEn = "Absurd"),
            )
    }
}
