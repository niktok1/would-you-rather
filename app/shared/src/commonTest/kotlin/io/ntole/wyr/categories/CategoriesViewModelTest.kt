package io.ntole.wyr.categories

import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.category.CategoryRepository
import io.ntole.wyr.core.domain.category.GetCategories
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.question.QuestionRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Categories screen's ViewModel (CLAUDE.md §8d, *Categories*), over fakes of the repositories. */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val questions = FakeQuestionRepository()
    private val categories = FakeCategoryRepository()

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
    fun `the categories are read when the screen is shown and listed in the server's order`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.refresh()
            assertTrue(viewModel.state.value.isLoading)
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(1, categories.reads)
            assertFalse(state.isLoading)
            assertEquals(LISTED, state.categories)
            assertEquals(LISTED, state.found)
        }

    @Test
    fun `the categories read before are listed until the read lands`() =
        runTest(dispatcher) {
            categories.categories.value = LISTED.take(2)
            val viewModel = viewModel()

            assertEquals(LISTED.take(2), viewModel.state.value.found)
            viewModel.refresh()
            testScheduler.advanceUntilIdle()
            assertEquals(LISTED, viewModel.state.value.found)
        }

    @Test
    fun `a failed read says so and keeps the categories read before`() =
        runTest(dispatcher) {
            categories.categories.value = LISTED.take(2)
            categories.read = { throw WyrException(DomainError.NETWORK) }
            val viewModel = viewModel()

            viewModel.refresh()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(DomainError.NETWORK, state.failure)
            assertFalse(state.isLoading)
            assertEquals(LISTED.take(2), state.found)
        }

    @Test
    fun `Try again reads the categories again and clears the failure`() =
        runTest(dispatcher) {
            categories.read = { throw WyrException(DomainError.SERVER) }
            val viewModel = viewModel()
            viewModel.refresh()
            testScheduler.advanceUntilIdle()
            categories.read = { LISTED }

            viewModel.refresh()
            assertNull(viewModel.state.value.failure, "cleared as the read goes out")
            testScheduler.advanceUntilIdle()

            assertEquals(2, categories.reads)
            assertNull(viewModel.state.value.failure)
            assertEquals(LISTED, viewModel.state.value.found)
        }

    @Test
    fun `a second read while one is in flight reads nothing more`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            categories.read = {
                gate.await()
                LISTED
            }
            val viewModel = viewModel()

            viewModel.refresh()
            testScheduler.advanceUntilIdle()
            viewModel.refresh()
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertEquals(1, categories.reads)
        }

    @Test
    fun `the categories another screen reads are listed here too`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            testScheduler.advanceUntilIdle()

            categories.categories.value = LISTED
            testScheduler.advanceUntilIdle()

            assertEquals(LISTED, viewModel.state.value.found)
        }

    @Test
    fun `a Cyrillic query finds a Serbian name`() =
        runTest(dispatcher) {
            assertEquals(listOf(FOOD), finds("Хра"))
        }

    @Test
    fun `a Latin query finds a Serbian name written in Cyrillic`() =
        runTest(dispatcher) {
            assertEquals(listOf(FOOD), finds("hra"))
        }

    @Test
    fun `a Latin query with a digraph finds a Cyrillic letter`() =
        runTest(dispatcher) {
            // Љ is two letters in Latin, and so is Џ.
            assertEquals(listOf(LOVE), finds("ljub"))
            assertEquals(listOf(JUNGLE), finds("džu"))
        }

    @Test
    fun `a Latin query with a Serbian letter finds it in Cyrillic`() =
        runTest(dispatcher) {
            assertEquals(listOf(LIFESTYLE), finds("život"))
        }

    @Test
    fun `a Cyrillic query finds a Serbian name written in Latin`() =
        runTest(dispatcher) {
            assertEquals(listOf(ROCK), finds("рок"))
        }

    @Test
    fun `an English query finds an English name`() =
        runTest(dispatcher) {
            assertEquals(listOf(FOOD), finds("food"))
        }

    @Test
    fun `a query finds a name in any case`() =
        runTest(dispatcher) {
            assertEquals(listOf(FOOD), finds("ХРАНА"))
            assertEquals(listOf(FOOD), finds("FoOd"))
            assertEquals(listOf(LOVE), finds("LJUBAV"))
        }

    /** Provisional (CLAUDE.md §8d, *The Categories screen*): Serbian Latin is often typed without them. */
    @Test
    fun `a query without a letter's accent does not find it`() =
        runTest(dispatcher) {
            assertEquals(emptyList(), finds("nacin"))
        }

    @Test
    fun `a query finds a name by any part of it`() =
        runTest(dispatcher) {
            assertEquals(listOf(LIFESTYLE), finds("ivo"))
        }

    @Test
    fun `a query finds every category either name holds it in and keeps the server's order`() =
        runTest(dispatcher) {
            // Food and Love hold it in English only, Начин живота and Апсурдно in Serbian only.
            assertEquals(listOf(FOOD, LIFESTYLE, ABSURD, LOVE, ROCK), finds("o"))
        }

    @Test
    fun `a blank query lists every category`() =
        runTest(dispatcher) {
            val viewModel = listed()
            viewModel.search("хр")

            viewModel.search("  ")

            assertEquals(LISTED, viewModel.state.value.found)
            assertEquals("  ", viewModel.state.value.query, "kept as typed")
        }

    @Test
    fun `a query around spaces finds what it holds`() =
        runTest(dispatcher) {
            assertEquals(listOf(ETHICS), finds("  етика "))
        }

    @Test
    fun `a query that finds nothing lists nothing`() =
        runTest(dispatcher) {
            val viewModel = listed()

            viewModel.search("xyz")

            assertEquals(emptyList(), viewModel.state.value.found)
            assertEquals(LISTED, viewModel.state.value.categories)
        }

    @Test
    fun `a search keeps its query when the categories are read again`() =
        runTest(dispatcher) {
            val viewModel = listed()
            viewModel.search("food")

            categories.categories.value = LISTED + Category("FAST_FOOD", "Брза храна", "Fast food")
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf("FOOD", "FAST_FOOD"),
                viewModel.state.value.found
                    .map { it.id },
            )
        }

    @Test
    fun `All is ticked while no category is`() =
        runTest(dispatcher) {
            val viewModel = listed()

            assertEquals(emptySet(), viewModel.state.value.ticked)
        }

    @Test
    fun `ticking a category unticks All`() =
        runTest(dispatcher) {
            val viewModel = listed()

            viewModel.toggle("FOOD")

            assertEquals(setOf("FOOD"), viewModel.state.value.ticked)
        }

    @Test
    fun `several categories can be ticked at once`() =
        runTest(dispatcher) {
            val viewModel = listed()

            viewModel.toggle("FOOD")
            viewModel.toggle("ETHICS")
            viewModel.toggle("ABSURD")

            assertEquals(setOf("FOOD", "ETHICS", "ABSURD"), viewModel.state.value.ticked)
        }

    @Test
    fun `unticking a category leaves the rest ticked`() =
        runTest(dispatcher) {
            val viewModel = listed()
            viewModel.toggle("FOOD")
            viewModel.toggle("ETHICS")

            viewModel.toggle("FOOD")

            assertEquals(setOf("ETHICS"), viewModel.state.value.ticked)
        }

    @Test
    fun `unticking the last category is All`() =
        runTest(dispatcher) {
            val viewModel = listed()
            viewModel.toggle("FOOD")

            viewModel.toggle("FOOD")

            assertEquals(emptySet(), viewModel.state.value.ticked)
        }

    @Test
    fun `ticking All unticks every category`() =
        runTest(dispatcher) {
            val viewModel = listed()
            viewModel.toggle("FOOD")
            viewModel.toggle("ETHICS")

            viewModel.selectAll()

            assertEquals(emptySet(), viewModel.state.value.ticked)
        }

    @Test
    fun `a category the search hides stays ticked`() =
        runTest(dispatcher) {
            val viewModel = listed()
            viewModel.toggle("FOOD")

            viewModel.search("етика")
            viewModel.toggle("ETHICS")

            assertEquals(setOf("FOOD", "ETHICS"), viewModel.state.value.ticked)
        }

    @Test
    fun `opening starts from the categories played with nothing searched`() =
        runTest(dispatcher) {
            questions.categories.value = setOf("ETHICS")
            val viewModel = listed()
            viewModel.search("хр")

            viewModel.open()

            val state = viewModel.state.value
            assertEquals(setOf("ETHICS"), state.ticked)
            assertEquals("", state.query)
            assertEquals(LISTED, state.found)
        }

    @Test
    fun `Play sets what is ticked once and goes back`() =
        runTest(dispatcher) {
            val viewModel = listed()
            viewModel.open()
            viewModel.toggle("FOOD")
            viewModel.toggle("ETHICS")

            viewModel.play()
            assertTrue(viewModel.state.value.isPlaying)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(setOf("FOOD", "ETHICS")), questions.changes)
            assertEquals(setOf("FOOD", "ETHICS"), questions.categories.value)
            assertTrue(viewModel.state.value.played)
            assertFalse(viewModel.state.value.isPlaying)
        }

    @Test
    fun `Play on All sets none`() =
        runTest(dispatcher) {
            questions.categories.value = setOf("FOOD")
            val viewModel = listed()
            viewModel.open()

            viewModel.selectAll()
            viewModel.play()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(emptySet<String>()), questions.changes)
            assertTrue(viewModel.state.value.played)
        }

    @Test
    fun `every category ticked is played as all of them and not as none`() =
        runTest(dispatcher) {
            // Not the same selection (CLAUDE.md §8d, *Categories*): a category a moderator adds later
            // is in none, and not in these.
            val viewModel = listed()
            viewModel.open()
            val every = LISTED.map { it.id }.toSet()

            every.forEach(viewModel::toggle)
            assertEquals(every, viewModel.state.value.ticked, "ticked, not folded into All")
            viewModel.play()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(every), questions.changes)
        }

    @Test
    fun `Play waits for the change to land before it goes back`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            questions.beforeChange = { gate.await() }
            val viewModel = listed()
            viewModel.open()
            viewModel.toggle("FOOD")

            viewModel.play()
            testScheduler.advanceUntilIdle()
            assertFalse(viewModel.state.value.played, "the Play screen would show the question before")

            gate.complete(Unit)
            testScheduler.advanceUntilIdle()
            assertTrue(viewModel.state.value.played)
        }

    @Test
    fun `a second tap on Play sends one change`() =
        runTest(dispatcher) {
            val viewModel = listed()
            viewModel.open()
            viewModel.toggle("FOOD")

            // Both land before the first one's coroutine gets to run.
            viewModel.play()
            viewModel.play()
            testScheduler.advanceUntilIdle()

            assertEquals(1, questions.changes.size)
        }

    @Test
    fun `nothing can be ticked while Play is sent`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            questions.beforeChange = { gate.await() }
            val viewModel = listed()
            viewModel.open()
            viewModel.toggle("FOOD")
            viewModel.play()
            testScheduler.advanceUntilIdle()

            viewModel.toggle("ETHICS")
            viewModel.selectAll()
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertEquals(setOf("FOOD"), viewModel.state.value.ticked)
            assertEquals(listOf(setOf("FOOD")), questions.changes)
        }

    @Test
    fun `Play with the categories played sends nothing and goes back`() =
        runTest(dispatcher) {
            questions.categories.value = setOf("ETHICS")
            val viewModel = listed()
            viewModel.open()
            viewModel.toggle("FOOD")
            viewModel.toggle("FOOD")

            viewModel.play()
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList(), questions.changes, "so the Play screen keeps its question")
            assertTrue(viewModel.state.value.played)
        }

    @Test
    fun `back discards what was ticked and the next visit starts from the categories played`() =
        runTest(dispatcher) {
            questions.categories.value = setOf("ETHICS")
            val viewModel = listed()
            viewModel.open()
            viewModel.toggle("FOOD")
            viewModel.selectAll()

            // Back is leaving without Play: nothing is sent, and opening again starts afresh.
            viewModel.open()
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList(), questions.changes)
            assertEquals(setOf("ETHICS"), questions.categories.value)
            assertEquals(setOf("ETHICS"), viewModel.state.value.ticked)
        }

    @Test
    fun `a visit after Play starts again with nothing played yet`() =
        runTest(dispatcher) {
            val viewModel = listed()
            viewModel.open()
            viewModel.toggle("FOOD")
            viewModel.play()
            testScheduler.advanceUntilIdle()

            viewModel.open()

            val state = viewModel.state.value
            assertFalse(state.played, "the screen would go back as soon as it is shown")
            assertEquals(setOf("FOOD"), state.ticked)
            viewModel.toggle("ETHICS")
            viewModel.play()
            testScheduler.advanceUntilIdle()
            assertEquals(listOf(setOf("FOOD"), setOf("FOOD", "ETHICS")), questions.changes)
        }

    @Test
    fun `opening while Play is sent keeps what it sends`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            questions.beforeChange = { gate.await() }
            val viewModel = listed()
            viewModel.open()
            viewModel.toggle("FOOD")
            viewModel.play()
            testScheduler.advanceUntilIdle()

            viewModel.open()

            assertEquals(setOf("FOOD"), viewModel.state.value.ticked)
            assertTrue(viewModel.state.value.isPlaying)
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()
            assertTrue(viewModel.state.value.played)
        }

    /** What a ViewModel with every category of [LISTED] read lists once [query] is typed. */
    private fun TestScope.finds(query: String): List<Category> {
        val viewModel = listed()
        viewModel.search(query)
        return viewModel.state.value.found
    }

    private fun viewModel() =
        CategoriesViewModel(
            getCategories = GetCategories(categories),
            questions = questions,
            categoryList = categories,
        )

    /** A ViewModel whose categories are [LISTED], read. */
    private fun TestScope.listed(): CategoriesViewModel {
        val viewModel = viewModel()
        viewModel.refresh()
        testScheduler.advanceUntilIdle()
        return viewModel
    }

    /** The selection, as the Play screen's repository holds it, and every change of it, in order. */
    private class FakeQuestionRepository : QuestionRepository {
        override val categories = MutableStateFlow<Set<String>>(emptySet())

        val changes = mutableListOf<Set<String>>()

        /** Runs before a change lands, as a refill in flight holds the real one up. */
        var beforeChange: suspend () -> Unit = {}

        override suspend fun setCategories(categories: Set<String>) {
            changes += categories
            beforeChange()
            this.categories.value = categories
        }

        override suspend fun next(): Question = error("the Categories screen asks for no question")

        override suspend fun prefetch() = Unit

        override suspend fun skip(questionId: String) = Unit

        override suspend fun reset() = Unit
    }

    /** The server's categories as the tests script them, each read counted in [reads]. */
    private class FakeCategoryRepository : CategoryRepository {
        var reads = 0

        var read: suspend () -> List<Category> = { LISTED }

        override val categories = MutableStateFlow<List<Category>>(emptyList())

        override suspend fun refresh(): List<Category> {
            reads++
            return read().also { categories.value = it }
        }
    }

    private companion object {
        val FOOD = Category(id = "FOOD", nameSr = "Храна", nameEn = "Food")
        val LIFESTYLE = Category(id = "LIFESTYLE", nameSr = "Начин живота", nameEn = "Lifestyle")
        val ETHICS = Category(id = "ETHICS", nameSr = "Етика", nameEn = "Ethics")
        val ABSURD = Category(id = "ABSURD", nameSr = "Апсурдно", nameEn = "Absurd")
        val LOVE = Category(id = "LOVE", nameSr = "Љубав", nameEn = "Love")
        val JUNGLE = Category(id = "JUNGLE", nameSr = "Џунгла", nameEn = "Jungle")

        /** A Serbian name a moderator wrote in Latin. */
        val ROCK = Category(id = "ROCK", nameSr = "Rok muzika", nameEn = "Rock music")

        val LISTED = listOf(FOOD, LIFESTYLE, ETHICS, ABSURD, LOVE, JUNGLE, ROCK)
    }
}
