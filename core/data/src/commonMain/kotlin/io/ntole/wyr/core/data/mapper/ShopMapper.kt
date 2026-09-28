package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.shop.Shop
import io.ntole.wyr.core.domain.shop.ShopTheme
import io.ntole.wyr.core.shop.ShopDto

/**
 * The shop as the domain holds it (CLAUDE.md §8d, *The shop*). A price or points below zero, which no
 * server sends, reads as none rather than failing the shop.
 */
internal fun ShopDto.toDomain(): Shop =
    Shop(
        themes = themes.map { ShopTheme(id = it.id, price = it.price.coerceAtLeast(0), owned = it.owned) },
        points = totalPoints.coerceAtLeast(0),
        registered = registered,
    )
