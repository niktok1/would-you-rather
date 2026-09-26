package io.ntole.wyr.language

/**
 * The words of the screen the game shows once the server serves this build nothing more (CLAUDE.md
 * §8e, *The build on every request*), as [Strings.updateScreen]: made in each language as the rest of
 * [Strings] is, and checked by the same tests.
 */
data class UpdateStrings(
    /** All the screen says. */
    val newVersion: String,
    /** The button to the game's page in the store, where there is one: Android's. */
    val update: String,
    /** The button that loads the page again, on the web. */
    val reload: String,
) {
    /** These strings with [transform] applied to every one of them, as [Strings.map] asks. */
    internal fun map(transform: (String) -> String): UpdateStrings =
        UpdateStrings(
            newVersion = transform(newVersion),
            update = transform(update),
            reload = transform(reload),
        )
}

/** The source text, written by hand. */
internal val SerbianCyrillicUpdateStrings: UpdateStrings =
    UpdateStrings(
        newVersion = "Нова верзија је доступна",
        update = "Ажурирај",
        reload = "Освежи",
    )

internal val EnglishUpdateStrings: UpdateStrings =
    UpdateStrings(
        newVersion = "A new version is available",
        update = "Update",
        reload = "Reload",
    )
