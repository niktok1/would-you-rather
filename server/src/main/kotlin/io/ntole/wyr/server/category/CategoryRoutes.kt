package io.ntole.wyr.server.category

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.category.CategoryListDto
import io.ntole.wyr.server.db.Db
import io.ntole.wyr.server.plugins.RouteLimit
import io.ntole.wyr.server.plugins.rateLimit

/**
 * The categories, for everybody (CLAUDE.md §8d, *Categories*). Outside `authenticate`: the list is the
 * same for every player, so a client can read it before it has a session, and a bearer token sent
 * beside it plays no part.
 */
fun Route.categoryRoutes(db: Db) {
    rateLimit(RouteLimit.CATEGORIES) {
        get(WyrApi.Paths.CATEGORIES) {
            call.respond(CategoryListDto(db.query { CategoryStore.all() }))
        }
    }
}
