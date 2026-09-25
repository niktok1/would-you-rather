package io.ntole.wyr.language

/**
 * The Categories screen's words (CLAUDE.md §8d, *Categories*; §8f), a part of [Strings] of their own
 * so the screen's texts stand together. Short, the user asking for less text: the category names
 * themselves are the server's, named by [categoryName], not these.
 */
data class CategoryStrings(
    /** The search field's hint, and the screen's only title. */
    val search: String,
    /** The first line of the list, ticked while no category is: every category. */
    val all: String,
    /** Before the number of categories ticked: *Изабрано: 3*. */
    val selected: String,
    /** Under All, when the search finds no category. */
    val noMatch: String,
    /** The read of the categories failed. */
    val cannotLoad: String,
    /** The button beside that failure, which reads them again. */
    val tryAgain: String,
    /** The spinner's name, for a screen reader, while the categories are read with none read before. */
    val loading: String,
    /** The one action: play the categories ticked, and back to the Play screen. */
    val play: String,
) {
    /** How many categories are ticked, as the screen shows it beside Play: *Изабрано: 3*. */
    fun selectedCount(count: Int): String = "$selected: $count"

    /** These strings with [transform] applied to every one of them, as [Strings.map] does. */
    internal fun map(transform: (String) -> String): CategoryStrings =
        CategoryStrings(
            search = transform(search),
            all = transform(all),
            selected = transform(selected),
            noMatch = transform(noMatch),
            cannotLoad = transform(cannotLoad),
            tryAgain = transform(tryAgain),
            loading = transform(loading),
            play = transform(play),
        )
}

/** The source text, written by hand; Serbian Latin is made from it with the rest of [Strings]. */
internal val SerbianCyrillicCategoryStrings: CategoryStrings =
    CategoryStrings(
        search = "Претражи категорије",
        all = "Све",
        selected = "Изабрано",
        noMatch = "Нема резултата",
        cannotLoad = "Категорије се нису учитале.",
        tryAgain = "Пробај опет",
        loading = "Учитавање",
        play = "Играј",
    )

internal val EnglishCategoryStrings: CategoryStrings =
    CategoryStrings(
        search = "Search categories",
        all = "All",
        selected = "Selected",
        noMatch = "No results",
        cannotLoad = "Couldn't load the categories.",
        tryAgain = "Try again",
        loading = "Loading",
        play = "Play",
    )
