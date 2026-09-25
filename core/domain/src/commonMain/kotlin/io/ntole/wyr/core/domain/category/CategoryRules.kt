package io.ntole.wyr.core.domain.category

/**
 * The server's rules for a category a moderator adds or renames (CLAUDE.md §8d, *Categories*), so
 * the moderation app can say what is wrong before it sends: the server refuses what breaks them as a
 * malformed request, which reads as a server failure.
 *
 * An id is 1 to [MAX_ID_LENGTH] of `A`-`Z`, `0`-`9` and `_`, taken exactly as typed. A name is
 * trimmed, then 1 to [MAX_NAME_LENGTH] long, counted as `String.length` counts, and one line: no
 * control character, nor U+2028 or U+2029, left anywhere once trimmed, as a submitted option.
 *
 * An id left out is made by the server from the English name, which needs a Java library to take
 * the accents off, so nothing here copies that rule: the name the server derives nothing from (one
 * of no Latin letter or digit) is refused as malformed, and the moderator then gives an id.
 *
 * The numbers are the server's `WyrApi.Limits`, which this module cannot see (CLAUDE.md §3), so
 * `:core:data`'s tests pin each copy to the wire's.
 */
public object CategoryRules {
    public const val MAX_ID_LENGTH: Int = 32

    public const val MAX_NAME_LENGTH: Int = 40

    /** Whether the server would take [id] as a category's id, exactly as it is. */
    public fun isId(id: String): Boolean = id.length in 1..MAX_ID_LENGTH && id.all { it in ID_CHARACTERS }

    /** Whether the server would take [name], once trimmed, as a category's name in either language. */
    public fun isName(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.length in 1..MAX_NAME_LENGTH &&
            trimmed.none { it.isISOControl() || it.category in LINE_SEPARATORS }
    }

    private val ID_CHARACTERS: Set<Char> = (('A'..'Z') + ('0'..'9') + '_').toSet()

    /** The categories of U+2028 and U+2029, the only characters in either. */
    private val LINE_SEPARATORS = setOf(CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR)
}
