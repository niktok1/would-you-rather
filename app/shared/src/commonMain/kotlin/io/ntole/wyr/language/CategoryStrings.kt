package io.ntole.wyr.language

/**
 * The Categories screen's words (CLAUDE.md §8d, *Categories*; §8f), a part of [Strings] of their own
 * so the screen's texts stand together. Short, the user asking for less text: the category names
 * themselves are the server's, named by [categoryName], not these, and its Play, Try again and the
 * failure beside it are the game's, [Strings.play], [Strings.tryAgain], [Strings.categoriesUnread]
 * and [PlayStrings.cannotReach]. A text holding `{0}` is a template, which [fill] fills in.
 */
data class CategoryStrings(
    /** The search field's hint, and the screen's only title. */
    val search: String,
    /** The first line of the list, ticked while no category is: every category. */
    val all: String,
    /** How many categories are ticked, `{0}`, as the screen shows it beside Play: *Изабрано: 3*. */
    val selectedCount: String,
    /** Under All, when the search finds no category. */
    val noMatch: String,
    /** The spinner's name, for a screen reader, while the categories are read with none read before. */
    val loading: String,
) {
    /** These strings with [transform] applied to every one of them, as [Strings.map] does. */
    internal fun map(transform: (String) -> String): CategoryStrings =
        CategoryStrings(
            search = transform(search),
            all = transform(all),
            selectedCount = transform(selectedCount),
            noMatch = transform(noMatch),
            loading = transform(loading),
        )
}

/** The source text, written by hand; Serbian Latin is made from it with the rest of [Strings]. */
internal val SerbianCyrillicCategoryStrings: CategoryStrings =
    CategoryStrings(
        search = "Претражи категорије",
        all = "Све",
        selectedCount = "Изабрано: {0}",
        noMatch = "Нема резултата",
        loading = "Учитавање",
    )

internal val EnglishCategoryStrings: CategoryStrings =
    CategoryStrings(
        search = "Search categories",
        all = "All",
        selectedCount = "Selected: {0}",
        noMatch = "No results",
        loading = "Loading",
    )
