package io.ntole.wyr.server.category

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.RegisterRequest
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.category.CategoryDto
import io.ntole.wyr.core.category.CategoryListDto
import io.ntole.wyr.core.category.CreateCategoryRequest
import io.ntole.wyr.core.category.RenameCategoryRequest
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.question.ApproveSubmissionRequest
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.question.QuestionPageDto
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteRequest
import io.ntole.wyr.server.NO_PRACTICAL_LIMIT
import io.ntole.wyr.server.config.ServerConfig
import io.ntole.wyr.server.testDatabaseFor
import io.ntole.wyr.server.wyrModule
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The categories end to end (CLAUDE.md §8d, *Categories*): listed to anybody, oldest first, and added
 * to and renamed by the moderator.
 */
class CategoryFlowTest {
    @Test
    fun `the categories are listed to anybody oldest first with both their names`() =
        runServer("listed") { client ->
            val response = client.categories()

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(FIRST_CATEGORIES, response.body<CategoryListDto>().categories)
        }

    @Test
    fun `a bearer token beside the request plays no part`() =
        runServer("bearer") { client ->
            val response = client.categories { bearerAuth("not a token this server signed") }

            assertEquals(HttpStatusCode.OK, response.status, "no 401 for a token it does not read")
            assertEquals(FIRST_CATEGORIES, response.body<CategoryListDto>().categories)
        }

    @Test
    fun `a moderator adds a category under an id from its English name and it is listed last`() =
        runServer("create-derived") { client ->
            val response = client.createCategory(CreateCategoryRequest(nameSr = " Брза храна ", nameEn = "Fast food"))

            assertEquals(HttpStatusCode.Created, response.status)
            val added = CategoryDto("FAST_FOOD", "Брза храна", "Fast food")
            assertEquals(added, response.body<CategoryDto>())
            assertEquals(FIRST_CATEGORIES + added, client.listed())
        }

    @Test
    fun `an id the moderator gives is kept as given`() =
        runServer("create-given") { client ->
            val request = CreateCategoryRequest(id = "JUNK", nameSr = "Брза храна", nameEn = "Fast food")

            val response = client.createCategory(request)

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(CategoryDto("JUNK", "Брза храна", "Fast food"), client.listed().last())
        }

    @Test
    fun `an id a category has already is refused and nothing changes`() =
        runServer("create-exists") { client ->
            listOf(
                "derived" to CreateCategoryRequest(nameSr = "Јело", nameEn = "Food"),
                "given" to CreateCategoryRequest(id = "ABSURD", nameSr = "Бесмислено", nameEn = "Nonsense"),
            ).forEach { (case, request) ->
                val response = client.createCategory(request)

                assertEquals(HttpStatusCode.Conflict, response.status, case)
                assertEquals(ErrorCode.CATEGORY_EXISTS, response.body<ErrorDto>().code, case)
            }
            assertEquals(FIRST_CATEGORIES, client.listed())
        }

    @Test
    fun `a category the rules refuse is a malformed request and nothing is added`() =
        runServer("create-malformed") { client ->
            listOf(
                "a blank name" to """{"nameSr":" ","nameEn":"Animals"}""",
                "no Serbian name" to """{"nameEn":"Animals"}""",
                "an English name that gives no id" to """{"nameSr":"Школа","nameEn":"Школа"}""",
                "an id in lower case" to """{"id":"animals","nameSr":"Животиње","nameEn":"Animals"}""",
                "not json" to "{not json",
            ).forEach { (case, body) ->
                val response =
                    client.post(WyrApi.Paths.ADMIN_CATEGORIES) {
                        header(WyrApi.Headers.ADMIN_TOKEN, ADMIN_TOKEN)
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status, case)
                assertEquals(ErrorCode.VALIDATION_FAILED, response.body<ErrorDto>().code, case)
            }
            assertEquals(FIRST_CATEGORIES, client.listed())
        }

    @Test
    fun `a rename sets both names and keeps the id and the questions filed under it`() =
        runServer("rename") { client ->
            val guest = client.guest()
            val food = client.feed(guest, "?${WyrApi.Query.CATEGORY}=FOOD").map { it.id }.toSet()

            val response = client.renameCategory(RenameCategoryRequest("FOOD", nameSr = " Јело", nameEn = "Meals"))

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(CategoryDto("FOOD", "Јело", "Meals"), response.body<CategoryDto>())
            assertEquals(CategoryDto("FOOD", "Јело", "Meals"), client.listed().first(), "first, as it was")
            assertEquals(food, client.feed(guest, "?${WyrApi.Query.CATEGORY}=FOOD").map { it.id }.toSet())
        }

    @Test
    fun `renaming an id no category has is not found`() =
        runServer("rename-unknown") { client ->
            val response =
                client.renameCategory(
                    RenameCategoryRequest("RANDOM", nameSr = "Насумично", nameEn = "Random"),
                )

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(ErrorCode.CATEGORY_NOT_FOUND, response.body<ErrorDto>().code)
            assertEquals(FIRST_CATEGORIES, client.listed())
        }

    @Test
    fun `a category added is one a question can be submitted under approved under and played under`() =
        runServer("create-played") { client ->
            client.createCategory(CreateCategoryRequest(nameSr = "Животиње", nameEn = "Animals"))
            val author = client.guest()
            // Only a registered player may submit.
            val registered =
                client.post(WyrApi.Paths.AUTH_REGISTER) {
                    bearerAuth(author.accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(RegisterRequest("catperson", "a password"))
                }
            assertEquals(HttpStatusCode.OK, registered.status)
            // The point the submission costs.
            val vote = VoteRequest("seed-1", OptionSide.A, attemptId = "earn")
            client.post(WyrApi.Paths.VOTES) {
                bearerAuth(author.accessToken)
                contentType(ContentType.Application.Json)
                setBody(vote)
            }

            val submitted =
                client.post(WyrApi.Paths.QUESTIONS) {
                    bearerAuth(author.accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(SubmitQuestionRequest("Be a cat", "Be a dog", listOf("ANIMALS", "FOOD")))
                }
            assertEquals(HttpStatusCode.Created, submitted.status)
            val submission = submitted.body<SubmissionDto>()
            assertEquals(listOf("FOOD", "ANIMALS"), submission.categories, "in the order of categories")
            val approved =
                client.post(WyrApi.Paths.ADMIN_APPROVALS) {
                    header(WyrApi.Headers.ADMIN_TOKEN, ADMIN_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(ApproveSubmissionRequest(submission.id, listOf("ANIMALS")))
                }
            assertEquals(HttpStatusCode.OK, approved.status)

            val served = client.feed(client.guest(), "?${WyrApi.Query.CATEGORY}=ANIMALS")

            assertEquals(listOf(submission.id), served.map { it.id })
            assertEquals(listOf("ANIMALS"), served.single().categories)
        }

    private suspend fun HttpClient.listed(): List<CategoryDto> = categories().body<CategoryListDto>().categories

    private suspend fun HttpClient.createCategory(request: CreateCategoryRequest): HttpResponse =
        post(WyrApi.Paths.ADMIN_CATEGORIES) {
            header(WyrApi.Headers.ADMIN_TOKEN, ADMIN_TOKEN)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun HttpClient.renameCategory(request: RenameCategoryRequest): HttpResponse =
        post(WyrApi.Paths.ADMIN_CATEGORY_RENAMES) {
            header(WyrApi.Headers.ADMIN_TOKEN, ADMIN_TOKEN)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun HttpClient.guest(): SessionDto = post(WyrApi.Paths.AUTH_GUEST).body()

    private suspend fun HttpClient.feed(
        session: SessionDto,
        query: String,
    ): List<QuestionDto> =
        get(WyrApi.Paths.QUESTIONS + query) { bearerAuth(session.accessToken) }.body<QuestionPageDto>().questions

    private suspend fun HttpClient.categories(build: HttpRequestBuilder.() -> Unit = {}): HttpResponse =
        get(WyrApi.Paths.CATEGORIES, build)

    private fun runServer(
        databaseName: String,
        block: suspend (HttpClient) -> Unit,
    ) = testApplication {
        val database = testDatabaseFor("categories-$databaseName")
        application {
            wyrModule(
                ServerConfig(
                    port = 0,
                    jdbcUrl = database.jdbcUrl,
                    dbUser = database.user,
                    dbPassword = database.password,
                    jwtSecret = "test-secret",
                    jwtIssuer = "wyr-test",
                    jwtAudience = "wyr-test-client",
                    accessTokenTtlSeconds = 300,
                    refreshTokenTtlSeconds = 3_600,
                    refreshGraceSeconds = null,
                    allowedWebOrigins = emptyList(),
                    adminToken = ADMIN_TOKEN,
                    rateLimits = NO_PRACTICAL_LIMIT,
                    clientIpHeader = null,
                    onRender = false,
                ),
            )
        }
        block(createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } })
    }

    private companion object {
        const val ADMIN_TOKEN = "test-admin-token-long-enough-to-pass-the-length-check"

        /** The five V6 writes, as the list sends them, Serbian names in Cyrillic and all. */
        val FIRST_CATEGORIES: List<CategoryDto> =
            listOf(
                CategoryDto("FOOD", "Храна", "Food"),
                CategoryDto("LIFESTYLE", "Начин живота", "Lifestyle"),
                CategoryDto("ETHICS", "Етика", "Ethics"),
                CategoryDto("SUPERPOWERS", "Супермоћи", "Superpowers"),
                CategoryDto("ABSURD", "Апсурдно", "Absurd"),
            )
    }
}
