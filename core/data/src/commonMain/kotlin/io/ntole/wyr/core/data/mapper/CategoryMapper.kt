package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.category.CategoryDto
import io.ntole.wyr.core.domain.category.Category

/**
 * DTO to domain translation for a category: the only place a `CategoryDto` and a `Category` are both
 * in scope (CLAUDE.md §3).
 */
internal fun CategoryDto.toDomain(): Category = Category(id = id, nameSr = nameSr, nameEn = nameEn)
