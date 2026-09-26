package io.ntole.wyr.admin.moderation

import io.ntole.wyr.admin.moderation.FakeModeration.Companion.TOKEN
import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.category.CategoryRules
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Categories tab, adding a category and putting one's names right, over scripted repositories. */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val moderation = FakeModeration()
    private val categories = FakeCategories()

    @BeforeTest
    fun setUp() {
        // viewModelScope runs on Dispatchers.Main, which has no implementation under test.
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `Load reads the categories`() =
        runTest(dispatcher) {
            val viewModel = open()
            viewModel.setAdminToken(TOKEN)

            viewModel.loadCategories()
            testScheduler.advanceUntilIdle()

            assertEquals(FakeCategories.LISTED, viewModel.state.value.categories.categories)
            assertEquals(1, categories.reads)
            assertEquals(emptyList(), moderation.calls, "no admin route: the list needs no token")
        }

    @Test
    fun `nothing is loaded added or renamed without a token`() =
        runTest(dispatcher) {
            val viewModel = open()

            viewModel.loadCategories()
            viewModel.editNewCategory(SPORT)
            viewModel.saveNewCategory()
            testScheduler.advanceUntilIdle()

            assertEquals(0, categories.reads)
            assertEquals(emptyList(), moderation.calls)
        }

    @Test
    fun `Add sends nothing until both names and any id typed are ones the server takes`() =
        runTest(dispatcher) {
            val viewModel = openWithCategories()
            val refused =
                listOf(
                    CategoryDraft(nameSr = "", nameEn = "Sport"),
                    CategoryDraft(nameSr = "Спорт", nameEn = "   "),
                    CategoryDraft(nameSr = "Спорт", nameEn = "Sport\nand games"),
                    CategoryDraft(nameSr = "x".repeat(CategoryRules.MAX_NAME_LENGTH + 1), nameEn = "Sport"),
                    SPORT.copy(id = "sport"),
                    SPORT.copy(id = "SPORT AND GAMES"),
                )

            refused.forEach { draft ->
                viewModel.editNewCategory(draft)
                viewModel.saveNewCategory()
                testScheduler.advanceUntilIdle()
            }

            assertEquals(emptyList(), moderation.calls)
        }

    @Test
    fun `a category with no id typed is left to the server to name and the list is read again`() =
        runTest(dispatcher) {
            val viewModel = openWithCategories()
            val fastFood = Category(id = "FAST_FOOD", nameSr = "Брза храна", nameEn = "Fast food")
            categories.read = { FakeCategories.LISTED + fastFood }

            viewModel.editNewCategory(CategoryDraft(id = " ", nameSr = " Брза храна", nameEn = "Fast food"))
            viewModel.saveNewCategory()
            testScheduler.advanceUntilIdle()

            // The names as typed, for the server to trim; no id, for it to make one.
            assertEquals(listOf("addCategory null| Брза храна|Fast food"), moderation.calls)
            val list = viewModel.state.value.categories
            assertEquals("Added FAST_FOOD: Брза храна / Fast food.", list.outcomes.notice)
            assertEquals(CategoryDraft(), list.adding, "cleared for the next one")
            assertEquals(FakeCategories.LISTED + fastFood, list.categories)
            assertEquals(2, categories.reads)
        }

    @Test
    fun `an id typed is sent exactly as typed`() =
        runTest(dispatcher) {
            val viewModel = openWithCategories()

            viewModel.editNewCategory(SPORT)
            viewModel.saveNewCategory()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("addCategory SPORT|Спорт|Sport"), moderation.calls)
            assertEquals(listOf(TOKEN), moderation.tokens.map { it.value })
        }

    @Test
    fun `an id a category has already says so under the form and keeps what was typed`() =
        runTest(dispatcher) {
            val viewModel = openWithCategories()
            moderation.addCategory = { _, _, _ -> throw WyrException(DomainError.CATEGORY_EXISTS, "FOOD exists") }

            viewModel.editNewCategory(SPORT.copy(id = "FOOD"))
            viewModel.saveNewCategory()
            testScheduler.advanceUntilIdle()

            val list = viewModel.state.value.categories
            assertEquals(Failure.Refused(DomainError.CATEGORY_EXISTS, detail = "FOOD exists"), list.addFailure)
            assertEquals(SPORT.copy(id = "FOOD"), list.adding)
            assertNull(list.outcomes.notice)
            // Read again all the same, so the category that has the id is listed.
            assertEquals(2, categories.reads)
        }

    @Test
    fun `renaming starts from the names a category has and sends the ones typed`() =
        runTest(dispatcher) {
            val viewModel = openWithCategories()

            viewModel.startRenaming("FOOD")
            assertEquals(CategoryDraft("FOOD", "Храна", "Food"), viewModel.state.value.categories.renaming)
            viewModel.editRenaming(CategoryDraft("FOOD", "Храна", "Meals"))
            viewModel.saveRenaming()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("renameCategory FOOD|Храна|Meals"), moderation.calls)
            val list = viewModel.state.value.categories
            assertNull(list.renaming, "done")
            assertEquals("Renamed FOOD: Храна / Meals.", list.outcomes.notice)
            assertEquals(2, categories.reads)
        }

    @Test
    fun `a rename keeps the category's id whatever is typed`() =
        runTest(dispatcher) {
            val viewModel = openWithCategories()
            viewModel.startRenaming("FOOD")

            viewModel.editRenaming(CategoryDraft("ANOTHER_ID", "Јело", "Meals"))
            viewModel.saveRenaming()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("renameCategory FOOD|Јело|Meals"), moderation.calls)
        }

    @Test
    fun `Cancel drops the names typed and sends nothing`() =
        runTest(dispatcher) {
            val viewModel = openWithCategories()
            viewModel.startRenaming("FOOD")
            viewModel.editRenaming(CategoryDraft("FOOD", "Јело", "Meals"))

            viewModel.cancelRenaming()
            viewModel.saveRenaming()
            testScheduler.advanceUntilIdle()

            assertNull(viewModel.state.value.categories.renaming)
            assertEquals(emptyList(), moderation.calls)
        }

    @Test
    fun `a rename that failed says so under the category and keeps the names typed`() =
        runTest(dispatcher) {
            val viewModel = openWithCategories()
            moderation.renameCategory = { _, _, _ -> throw WyrException(DomainError.NETWORK) }
            viewModel.startRenaming("FOOD")
            viewModel.editRenaming(CategoryDraft("FOOD", "Јело", "Meals"))

            viewModel.saveRenaming()
            testScheduler.advanceUntilIdle()

            val list = viewModel.state.value.categories
            assertEquals(Failure.Refused(DomainError.NETWORK, detail = "NETWORK"), list.renameFailure)
            assertEquals(CategoryDraft("FOOD", "Јело", "Meals"), list.renaming)
        }

    @Test
    fun `Lock forgets what was typed for a category and keeps the categories read`() =
        runTest(dispatcher) {
            val viewModel = openWithCategories()
            viewModel.editNewCategory(SPORT)
            viewModel.startRenaming("FOOD")

            viewModel.lock()

            assertEquals(CategoryList(FakeCategories.LISTED), viewModel.state.value.categories)
        }

    private fun TestScope.open(): ModerationViewModel =
        moderationViewModelOver(moderation, categories).also { testScheduler.advanceUntilIdle() }

    /** The app with the token typed and the categories read, the one read so far. */
    private fun TestScope.openWithCategories(): ModerationViewModel =
        open().also { viewModel ->
            viewModel.setAdminToken(TOKEN)
            viewModel.loadCategories()
            testScheduler.advanceUntilIdle()
            assertEquals(1, categories.reads)
        }

    private companion object {
        val SPORT = CategoryDraft(id = "SPORT", nameSr = "Спорт", nameEn = "Sport")
    }
}
