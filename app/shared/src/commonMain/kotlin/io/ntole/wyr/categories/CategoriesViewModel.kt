package io.ntole.wyr.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.analytics.AnalyticsEvent
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.category.CategoryRepository
import io.ntole.wyr.core.domain.category.GetCategories
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.language.SerbianScript
import io.ntole.wyr.core.domain.question.QuestionRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Categories screen can ask for, so the screen takes one argument for all of it. */
interface CategoriesActions {
    /** Shows the categories [query] finds, by either of their names, in either script, accents or none. */
    fun search(query: String)

    /** Ticks the category [id], or unticks it if it is ticked. */
    fun toggle(id: String)

    /** Ticks All, which unticks every category. */
    fun selectAll()

    /** Reads every category again: each time the screen is shown, and Try again. */
    fun refresh()

    /** Plays what is ticked, and goes back to Play. */
    fun play()
}

/**
 * Drives the Categories screen (CLAUDE.md §8d, *Categories*): every category the server lists, read
 * through [GetCategories] each time the screen is shown, searched as the player types, and ticked, as
 * many as the player likes, none being All. What is ticked is played only by [play], in one
 * [QuestionRepository.setCategories], which the Play screen follows by showing a question from it.
 *
 * It lives as long as the app, as every screen's ViewModel does (§8d, *Navigation*), so [open]
 * starts each visit afresh: leaving without Play keeps nothing of what was ticked or searched.
 *
 * [analytics] hear of the categories played, by id, and of a read that failed (CLAUDE.md §8g); never
 * what was searched.
 */
class CategoriesViewModel(
    private val getCategories: GetCategories,
    private val questions: QuestionRepository,
    categoryList: CategoryRepository,
    private val analytics: Analytics,
) : ViewModel(),
    CategoriesActions {
    /** The categories as last read, each with what a search compares a query with. */
    private var searchable: List<Searchable> = categoryList.categories.value.map(::Searchable)

    private val _state =
        MutableStateFlow(
            CategoriesState(
                ticked = questions.categories.value,
                categories = categoryList.categories.value,
                found = categoryList.categories.value,
            ),
        )
    val state: StateFlow<CategoriesState> = _state.asStateFlow()

    /** The read in flight, if any: one at a time. */
    private var read: Job? = null

    init {
        // Whoever read them last, this screen or another: the list is the repository's.
        viewModelScope.launch {
            categoryList.categories.collect { listed ->
                searchable = listed.map(::Searchable)
                _state.update { it.copy(categories = listed, found = find(it.query)) }
            }
        }
    }

    /**
     * Starts a visit, as the Play screen opens this one: ticked are the categories played now, and
     * nothing is searched. Not while a Play is being sent, whose ticks are what the screen shows until
     * it lands.
     */
    fun open() {
        _state.update { current ->
            if (current.isPlaying) {
                current
            } else {
                current.copy(query = "", found = find(""), ticked = questions.categories.value, played = false)
            }
        }
    }

    /**
     * Reads every category again, since a moderator adds them without a build. A read that fails
     * says so and leaves the categories read before listed, to tick. A second while one is in flight
     * reads nothing more.
     */
    override fun refresh() {
        if (read?.isActive == true) return
        _state.update { it.copy(isLoading = true, failure = null) }
        read =
            viewModelScope.launch {
                val failure =
                    try {
                        getCategories()
                        null
                    } catch (unread: WyrException) {
                        // Only a WyrException: a cancellation must go on up.
                        analytics.track(
                            AnalyticsEvent.ERROR_SHOWN,
                            mapOf(
                                AnalyticsProperty.CODE to unread.error.name,
                                AnalyticsProperty.ACTION to "categories",
                            ),
                        )
                        unread.error
                    }
                _state.update { it.copy(isLoading = false, failure = failure) }
            }
    }

    override fun search(query: String) = _state.update { it.copy(query = query, found = find(query)) }

    /** A category ticked unticks All, since All is none ticked; unticking the last one is All again. */
    override fun toggle(id: String) = tick { ticked -> if (id in ticked) ticked - id else ticked + id }

    override fun selectAll() = tick { emptySet() }

    /**
     * Plays what is ticked: one [QuestionRepository.setCategories], then [CategoriesState.played], and
     * the screen goes back to Play. What is played already is not sent again, so the question on the
     * Play screen stays. A second tap while it is sent sends nothing more.
     */
    override fun play() {
        val draft = _state.value
        if (draft.isPlaying || draft.played) return
        if (draft.ticked == questions.categories.value) {
            _state.update { it.copy(played = true) }
            return
        }
        _state.update { it.copy(isPlaying = true) }
        analytics.track(
            AnalyticsEvent.CATEGORIES_CHANGED,
            mapOf(AnalyticsProperty.CATEGORIES to draft.ticked.sorted(), AnalyticsProperty.COUNT to draft.ticked.size),
        )

        viewModelScope.launch {
            questions.setCategories(draft.ticked)
            _state.update { it.copy(isPlaying = false, played = true) }
        }
    }

    /** Changes what is ticked, but not while it is being played: what is sent is what was ticked. */
    private fun tick(change: (Set<String>) -> Set<String>) =
        _state.update { if (it.isPlaying) it else it.copy(ticked = change(it.ticked)) }

    /** The categories [query] finds, in the server's order: every one for a blank query. */
    private fun find(query: String): List<Category> {
        val key = searchKey(query.trim())
        return searchable.filter { it.matches(key) }.map { it.category }
    }

    /** A category with its names as a search compares them, worked out once a read, not once a letter. */
    private class Searchable(
        val category: Category,
    ) {
        private val keys = listOf(searchKey(category.nameSr), searchKey(category.nameEn))

        fun matches(key: String): Boolean = keys.any { key in it }
    }
}

/**
 * [text] as the search compares it: in Serbian Latin, lower-cased, and without Serbian Latin's
 * accents (CLAUDE.md §8d, *The Categories screen*). A query then finds a name whatever the script of
 * either and whatever their case: *hra*, *Хра* and *HRA* all find *Храна*, and *рок* a name a
 * moderator wrote in Latin, *Rok*. Љ, Њ and Џ are two letters in Latin, so *lj* finds *Љ*. And a
 * phone without a Serbian keyboard finds a name all the same: č and ć are c, š is s, ž is z and đ is
 * dj, on both sides and in the English name too, so *nacin* finds *Начин живота* and *djak* finds
 * *Ђак*, as *način* and *đak* do.
 */
internal fun searchKey(text: String): String =
    buildString {
        SerbianScript.toLatin(text).lowercase().forEach { letter -> append(UNACCENTED[letter] ?: letter.toString()) }
    }

/** Each accented letter of Serbian Latin, small, as it is typed without its accent. */
private val UNACCENTED: Map<Char, String> =
    mapOf('č' to "c", 'ć' to "c", 'š' to "s", 'ž' to "z", 'đ' to "dj")
