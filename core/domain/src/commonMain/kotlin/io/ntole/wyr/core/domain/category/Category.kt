package io.ntole.wyr.core.domain.category

/**
 * A category questions are filed under (CLAUDE.md §8d, *Categories*): server data, which a moderator
 * adds to without a build, so the client lists them from the server ([CategoryRepository]) rather
 * than naming them itself.
 *
 * Deliberately not the same type as `CategoryDto`: domain code must never see a DTO, and the mapping
 * between the two lives in `:core:data` (CLAUDE.md §3).
 *
 * [id] is what a question, a player's selection and every request name the category by, and it never
 * changes. [nameSr] is its name in Serbian, in Cyrillic, and [nameEn] in English; a moderator may put
 * either right, so nothing keeps a name longer than the list it came in.
 */
public data class Category(
    public val id: String,
    public val nameSr: String,
    public val nameEn: String,
)
