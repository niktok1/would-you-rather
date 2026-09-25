package io.ntole.wyr.server.category

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.category.CategoryDto
import io.ntole.wyr.core.category.CreateCategoryRequest
import io.ntole.wyr.core.category.RenameCategoryRequest
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.server.plugins.ApiFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** What a moderator may name a category, and the id one gets when none is given. */
class CategoryRulesTest {
    @Test
    fun `an id is derived from the English name when none is given`() {
        val cases =
            mapOf(
                "Animals" to "ANIMALS",
                "Fast food" to "FAST_FOOD",
                "Rock & roll" to "ROCK_ROLL",
                "  Café  " to "CAFE",
                "Sci-fi 2" to "SCI_FI_2",
                "...Odd... ones" to "ODD_ONES",
                "Школа" to "",
            )

        cases.forEach { (name, id) -> assertEquals(id, categoryIdFor(name), name) }
    }

    @Test
    fun `a derived id is cut to the longest an id can be and never ends in an underscore`() {
        val name = "A".repeat(WyrApi.Limits.MAX_CATEGORY_ID_LENGTH - 1) + " and more"

        assertEquals("A".repeat(WyrApi.Limits.MAX_CATEGORY_ID_LENGTH - 1), categoryIdFor(name))
    }

    @Test
    fun `a creation keeps its names trimmed and its id as given or derived`() {
        assertEquals(
            CategoryDto("FAST_FOOD", "Брза храна", "Fast food"),
            checkedCreation(CreateCategoryRequest(nameSr = " Брза храна ", nameEn = "Fast food\t")),
        )
        assertEquals(
            CategoryDto("JUNK", "Брза храна", "Fast food"),
            checkedCreation(CreateCategoryRequest(id = "JUNK", nameSr = "Брза храна", nameEn = "Fast food")),
        )
    }

    @Test
    fun `a creation the rules refuse is a malformed request`() {
        val longest = "x".repeat(WyrApi.Limits.MAX_CATEGORY_NAME_LENGTH)
        val refused =
            mapOf(
                "a blank Serbian name" to CreateCategoryRequest(nameSr = "  ", nameEn = "Animals"),
                "an empty English name" to CreateCategoryRequest(nameSr = "Животиње", nameEn = ""),
                "a name too long" to CreateCategoryRequest(nameSr = longest + "x", nameEn = "Animals"),
                "a name of two lines" to CreateCategoryRequest(nameSr = "Живо\nтиње", nameEn = "Animals"),
                "a name with a line separator" to CreateCategoryRequest(nameSr = "Животиње", nameEn = "Ani mals"),
                "a name with a NUL" to CreateCategoryRequest(nameSr = "Животиње\u0000", nameEn = "Animals"),
                "an English name that gives no id" to CreateCategoryRequest(nameSr = "Школа", nameEn = "Школа"),
                "an id in lower case" to CreateCategoryRequest(id = "animals", nameSr = "Животиње", nameEn = "Animals"),
                "an empty id" to CreateCategoryRequest(id = "", nameSr = "Животиње", nameEn = "Animals"),
                "an id with a space" to
                    CreateCategoryRequest(
                        id = "WILD LIFE",
                        nameSr = "Животиње",
                        nameEn = "Animals",
                    ),
                "an id too long" to
                    CreateCategoryRequest(
                        id = "A".repeat(WyrApi.Limits.MAX_CATEGORY_ID_LENGTH + 1),
                        nameSr = "Животиње",
                        nameEn = "Animals",
                    ),
            )

        refused.forEach { (case, request) ->
            assertEquals(
                ErrorCode.VALIDATION_FAILED,
                assertFailsWith<ApiFailure>(case) { checkedCreation(request) }.code,
                case,
            )
        }
        assertEquals(longest, checkedCreation(CreateCategoryRequest(nameSr = longest, nameEn = "Animals")).nameSr)
    }

    @Test
    fun `a rename is checked as a creation is`() {
        assertEquals(
            CategoryDto("FOOD", "Јело", "Meals"),
            checkedRenaming(RenameCategoryRequest("FOOD", nameSr = " Јело", nameEn = "Meals ")),
        )
        listOf(
            RenameCategoryRequest("food", nameSr = "Јело", nameEn = "Meals"),
            RenameCategoryRequest("FOOD", nameSr = "", nameEn = "Meals"),
        ).forEach { request ->
            assertEquals(
                ErrorCode.VALIDATION_FAILED,
                assertFailsWith<ApiFailure>("$request") { checkedRenaming(request) }.code,
            )
        }
    }
}
