package io.ntole.wyr.core.network.analytics

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ntole.wyr.core.network.InMemoryTokenStorage
import io.ntole.wyr.core.network.environment.WyrEnvironment
import io.ntole.wyr.core.network.jsonHeaders
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * [PostHogAnalytics] against a PostHog made of a `MockEngine` (CLAUDE.md §8g): what it sends, when,
 * as whom, and what it never does. Everything runs on the test's virtual clock, the sends included,
 * so thirty seconds pass in none.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PostHogAnalyticsTest {
    private val storage = InMemoryTokenStorage()

    @Test
    fun `a build with no key sends nothing and keeps nothing`() =
        runTest {
            val posthog = FakePostHog(this)
            val analytics = analytics(posthog, config = null)

            repeat(PostHogAnalytics.BATCH_SIZE * 2) { analytics.track("tap") }
            analytics.screen("play")
            analytics.identify("player-1")
            analytics.flush()
            testScheduler.advanceTimeBy(2.minutes)
            testScheduler.runCurrent()

            assertEquals(0, posthog.requests)
            WyrEnvironment.entries.forEach { assertNull(storage.read(PostHogAnalytics.idKeyFor(it))) }
        }

    @Test
    fun `a batch goes once twenty events wait`() =
        runTest {
            val posthog = FakePostHog(this)
            val analytics = analytics(posthog)

            repeat(PostHogAnalytics.BATCH_SIZE - 1) { analytics.track("tap") }
            testScheduler.runCurrent()
            assertEquals(0, posthog.requests, "nineteen wait")

            analytics.track("tap")
            testScheduler.runCurrent()

            assertEquals(1, posthog.requests)
            assertEquals(PostHogAnalytics.BATCH_SIZE, posthog.events.size)
        }

    @Test
    fun `an event waits thirty seconds at most`() =
        runTest {
            val posthog = FakePostHog(this)
            val analytics = analytics(posthog)

            analytics.track("app_opened")
            testScheduler.advanceTimeBy(29.seconds)
            testScheduler.runCurrent()
            assertEquals(0, posthog.requests)

            testScheduler.advanceTimeBy(1.seconds)
            testScheduler.runCurrent()

            assertEquals(listOf("app_opened"), posthog.eventNames)
        }

    /** As the app goes to the background, what waits goes at once. */
    @Test
    fun `flush sends what waits at once`() =
        runTest {
            val posthog = FakePostHog(this)
            val analytics = analytics(posthog)
            repeat(3) { analytics.track("tap") }

            analytics.flush()
            testScheduler.runCurrent()

            assertEquals(1, posthog.requests)
            assertEquals(3, posthog.events.size)
        }

    @Test
    fun `the batch is PostHog's with the project key and each event's id and time`() =
        runTest {
            val posthog = FakePostHog(this)
            val analytics = analytics(posthog, environment = WyrEnvironment.DEV)

            analytics.track("question_answered", mapOf("side" to "A", "answer_ms" to 1234L))
            analytics.flush()
            testScheduler.runCurrent()

            val batch = posthog.batches.single()
            assertEquals("https://eu.i.posthog.com/batch/", posthog.urls.single())
            assertEquals(HttpMethod.Post, posthog.methods.single())
            assertEquals("phc_test", batch.string("api_key"))
            val event = posthog.events.single()
            assertEquals("question_answered", event.string("event"))
            assertEquals(Instant.fromEpochMilliseconds(START).toString(), event.string("timestamp"))
            assertTrue(event.string("uuid").isNotEmpty())
            val properties = event.getValue("properties").jsonObject
            assertEquals("A", properties.string("side"))
            assertEquals(
                1234L,
                properties
                    .getValue("answer_ms")
                    .jsonPrimitive.content
                    .toLong(),
            )
            assertEquals(storage.read(PostHogAnalytics.idKeyFor(WyrEnvironment.DEV)), properties.string("distinct_id"))
            assertEquals("wyr-kotlin", properties.string("\$lib"))
            assertEquals("1.2.3", properties.string("\$app_version"))
            assertEquals("dev", properties.string("environment"))
            assertTrue(properties.string("\$os").isNotEmpty(), "the OS")
            assertTrue(properties.string("platform") in setOf("android", "ios", "desktop", "web"))
            assertTrue(properties.string("\$session_id").isNotEmpty(), "the session")
        }

    @Test
    fun `the batch goes to the host the build names`() =
        runTest {
            val posthog = FakePostHog(this)
            val analytics = analytics(posthog, config = PostHogConfig.of("phc_test", "us.i.posthog.com/", "1"))

            analytics.track("tap")
            analytics.flush()
            testScheduler.runCurrent()

            assertEquals("https://us.i.posthog.com/batch/", posthog.urls.single())
        }

    @Test
    fun `an install keeps its id across launches and one for each environment`() =
        runTest {
            val posthog = FakePostHog(this)
            WyrEnvironment.entries.forEach { environment ->
                analytics(posthog, environment = environment).track("app_opened")
            }
            testScheduler.runCurrent()
            val ids = WyrEnvironment.entries.map { storage.read(PostHogAnalytics.idKeyFor(it)) }
            assertEquals(3, ids.filterNotNull().toSet().size, "one id for each environment: $ids")

            // A launch after: the same storage, a new instance.
            val relaunched = analytics(posthog, environment = WyrEnvironment.PROD)
            relaunched.track("app_opened")
            relaunched.flush()
            testScheduler.runCurrent()

            assertEquals(ids.last(), posthog.events.last().distinctId)
        }

    @Test
    fun `the id is kept apart from the sessions and the language`() {
        val keys = WyrEnvironment.entries.map(PostHogAnalytics::idKeyFor) + PostHogAnalytics.ENABLED_KEY

        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.none { it.startsWith("wyr.session") || it == "wyr.language" }, "$keys")
    }

    @Test
    fun `identify joins the install to the player and plays on as them`() =
        runTest {
            val posthog = FakePostHog(this)
            val analytics = analytics(posthog)
            analytics.track("register_started")
            testScheduler.runCurrent()
            val anonymous = storage.read(PostHogAnalytics.idKeyFor(WyrEnvironment.LOCAL))

            analytics.identify("player-1")
            analytics.track("register_completed")
            analytics.flush()
            testScheduler.runCurrent()

            val (started, identify, completed) = posthog.events
            assertEquals(anonymous, started.distinctId)
            assertEquals("\$identify", identify.string("event"))
            assertEquals("player-1", identify.distinctId)
            assertEquals(anonymous, identify.getValue("properties").jsonObject.string("\$anon_distinct_id"))
            assertEquals("player-1", completed.distinctId)
            assertEquals("player-1", storage.read(PostHogAnalytics.idKeyFor(WyrEnvironment.LOCAL)))
        }

    @Test
    fun `identify as the player already identified sends nothing`() =
        runTest {
            val posthog = FakePostHog(this)
            val analytics = analytics(posthog)
            analytics.identify("player-1")
            analytics.identify("player-1")
            analytics.flush()
            testScheduler.runCurrent()

            assertEquals(1, posthog.eventNames.count { it == "\$identify" })
        }

    @Test
    fun `reset plays on as a fresh anonymous player in a fresh session`() =
        runTest {
            val posthog = FakePostHog(this)
            val analytics = analytics(posthog)
            analytics.identify("player-1")
            analytics.track("logout")

            analytics.reset()
            analytics.track("app_opened")
            analytics.flush()
            testScheduler.runCurrent()

            val logout = posthog.events.single { it.string("event") == "logout" }
            val after = posthog.events.last()
            assertEquals("player-1", logout.distinctId, "the logout is the account's")
            assertNotEquals("player-1", after.distinctId)
            assertNotEquals(logout.property("\$session_id"), after.property("\$session_id"))
            assertEquals(after.distinctId, storage.read(PostHogAnalytics.idKeyFor(WyrEnvironment.LOCAL)))
        }

    @Test
    fun `off sends nothing and drops what waited`() =
        runTest {
            val posthog = FakePostHog(this)
            val analytics = analytics(posthog)
            repeat(5) { analytics.track("tap") }

            analytics.setEnabled(false)
            repeat(PostHogAnalytics.BATCH_SIZE) { analytics.track("tap") }
            analytics.screen("account")
            analytics.flush()
            testScheduler.advanceTimeBy(2.minutes)
            testScheduler.runCurrent()

            assertFalse(analytics.enabled.value)
            assertEquals(0, posthog.requests)
        }

    @Test
    fun `off is kept on the device for every environment and on again sends again`() =
        runTest {
            val posthog = FakePostHog(this)
            analytics(posthog).setEnabled(false)
            testScheduler.runCurrent()

            WyrEnvironment.entries.forEach { environment ->
                assertFalse(analytics(posthog, environment = environment).enabled.value, "$environment")
            }
            val relaunched = analytics(posthog)
            relaunched.setEnabled(true)
            relaunched.track("tap")
            relaunched.flush()
            testScheduler.runCurrent()

            assertTrue(analytics(posthog).enabled.value)
            assertEquals(listOf("tap"), posthog.eventNames)
        }

    @Test
    fun `on is the default`() =
        runTest {
            assertTrue(analytics(FakePostHog(this)).enabled.value)
            assertTrue(analytics(FakePostHog(this), config = null).enabled.value)
        }

    /** The player's choice is kept whether or not the build sends, since they cannot tell. */
    @Test
    fun `a build with no key keeps the player's choice`() =
        runTest {
            analytics(FakePostHog(this), config = null).setEnabled(false)
            testScheduler.runCurrent()

            assertFalse(analytics(FakePostHog(this), config = null).enabled.value)
        }

    @Test
    fun `a batch the network failed waits and goes again`() =
        runTest {
            val posthog = FakePostHog(this)
            val analytics = analytics(posthog)
            posthog.offline = true
            repeat(3) { analytics.track("tap") }
            analytics.flush()
            testScheduler.runCurrent()
            assertEquals(1, posthog.requests)
            assertTrue(posthog.events.isEmpty())

            posthog.offline = false
            testScheduler.advanceTimeBy(PostHogAnalytics.FLUSH_INTERVAL)
            testScheduler.runCurrent()

            assertEquals(3, posthog.events.size)
        }

    @Test
    fun `a batch the service could not take yet waits and goes again`() =
        runTest {
            listOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.TooManyRequests).forEach { status ->
                val posthog = FakePostHog(this)
                val analytics = analytics(posthog)
                posthog.status = status
                analytics.track("tap")
                analytics.flush()
                testScheduler.runCurrent()

                posthog.status = HttpStatusCode.OK
                analytics.flush()
                testScheduler.runCurrent()

                assertEquals(2, posthog.requests, "$status")
                assertEquals(1, posthog.acceptedEvents.size, "$status")
            }
        }

    @Test
    fun `a batch the service refused is dropped`() =
        runTest {
            val posthog = FakePostHog(this)
            val analytics = analytics(posthog)
            posthog.status = HttpStatusCode.Unauthorized
            analytics.track("tap")
            analytics.flush()
            testScheduler.runCurrent()

            posthog.status = HttpStatusCode.OK
            analytics.flush()
            testScheduler.advanceTimeBy(2.minutes)
            testScheduler.runCurrent()

            assertEquals(1, posthog.requests)
        }

    @Test
    fun `at most a thousand wait and the oldest go first`() =
        runTest {
            val posthog = FakePostHog(this)
            val analytics = analytics(posthog)
            posthog.offline = true
            repeat(PostHogAnalytics.MAX_QUEUED + 100) { index -> analytics.track("tap", mapOf("index" to index)) }
            testScheduler.runCurrent()
            val tried = posthog.requests

            posthog.offline = false
            analytics.flush()
            testScheduler.runCurrent()

            val sent = posthog.acceptedEvents.map { it.property("index").toInt() }
            assertEquals((100 until PostHogAnalytics.MAX_QUEUED + 100).toList(), sent)
            assertEquals(1, tried, "one try, then none on size alone until the timer")
            assertEquals(PostHogAnalytics.MAX_QUEUED / PostHogAnalytics.MAX_BATCH, posthog.requests - tried)
        }

    @Test
    fun `a screen names itself and every event after it`() =
        runTest {
            val posthog = FakePostHog(this)
            val analytics = analytics(posthog)

            analytics.track("app_opened")
            analytics.screen("play")
            analytics.track("tap", mapOf("element" to "play.skip"))
            analytics.flush()
            testScheduler.runCurrent()

            val (opened, screen, tap) = posthog.events
            assertNull(opened.getValue("properties").jsonObject["\$screen_name"])
            assertEquals("\$screen", screen.string("event"))
            assertEquals("play", screen.property("\$screen_name"))
            assertEquals("play", tap.property("\$screen_name"))
        }

    @Test
    fun `a session ends after thirty minutes without an event`() =
        runTest {
            val posthog = FakePostHog(this)
            val analytics = analytics(posthog)

            analytics.track("a")
            testScheduler.advanceTimeBy(29.minutes)
            analytics.track("b")
            testScheduler.advanceTimeBy(30.minutes)
            analytics.track("c")
            analytics.flush()
            testScheduler.runCurrent()

            val (a, b, c) = posthog.events.map { it.property("\$session_id") }
            assertEquals(a, b)
            assertNotEquals(b, c)
            assertEquals('7', c[14], "a session's id is a version 7 UUID: $c")
        }

    @Test
    fun `a version 7 UUID begins with its time`() {
        val millis = 0x0192_3456_789AL

        val uuid = uuidV7(millis).toString()

        assertEquals("01923456-789a", uuid.take(13))
        assertEquals('7', uuid[14])
        assertTrue(uuid[19] in "89ab", uuid)
        assertNotEquals(uuidV7(millis), uuidV7(millis))
    }

    @Test
    fun `a property is JSON of its kind`() {
        assertEquals(JsonPrimitive("x"), "x".toJson())
        assertEquals(JsonPrimitive(3), 3.toJson())
        assertEquals(JsonPrimitive(true), true.toJson())
        assertEquals("[\"FOOD\",\"TRAVEL\"]", setOf("FOOD", "TRAVEL").toJson().toString())
        assertEquals(JsonPrimitive("B"), Letter.B.toJson())
        assertEquals("null", null.toJson().toString())
    }

    private fun TestScope.analytics(
        posthog: FakePostHog,
        config: PostHogConfig? = PostHogConfig.of("phc_test", null, "1.2.3"),
        environment: WyrEnvironment = WyrEnvironment.LOCAL,
    ): PostHogAnalytics {
        val scheduler = testScheduler
        return PostHogAnalytics(
            config = config,
            storage = storage,
            environment = environment,
            dispatcher = StandardTestDispatcher(scheduler),
            engine = posthog.engine,
            clock =
                object : Clock {
                    override fun now(): Instant = Instant.fromEpochMilliseconds(START + scheduler.currentTime)
                },
            timeSource = scheduler.timeSource,
        )
    }

    private enum class Letter { B }

    /**
     * PostHog's `/batch/` endpoint: answers [status], or fails as if offline while [offline], and keeps
     * every batch it was sent. On the test's own dispatcher, so a send runs on its virtual clock.
     */
    private class FakePostHog(
        scope: TestScope,
    ) {
        var status = HttpStatusCode.OK
        var offline = false
        var requests = 0
        val batches = mutableListOf<JsonObject>()
        val accepted = mutableListOf<JsonObject>()
        val urls = mutableListOf<String>()
        val methods = mutableListOf<HttpMethod>()

        val engine =
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(scope.testScheduler)
                    addHandler { request ->
                        requests++
                        if (offline) throw IllegalStateException("offline")
                        val batch = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        batches += batch
                        urls += request.url.toString()
                        methods += request.method
                        if (status.value in 200..299) accepted += batch
                        respond("{\"status\":1}", status, jsonHeaders)
                    }
                },
            )

        /** Every event sent, in the order sent, taken or not. */
        val events: List<JsonObject> get() =
            batches.flatMap { batch ->
                batch.getValue("batch").jsonArray.map { it.jsonObject }
            }

        /** Every event the service took. */
        val acceptedEvents: List<JsonObject> get() =
            accepted.flatMap { batch ->
                batch.getValue("batch").jsonArray.map { it.jsonObject }
            }

        val eventNames: List<String> get() = events.map { it.string("event") }
    }

    private companion object {
        const val START = 1_790_000_000_000L

        fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

        fun JsonObject.property(key: String): String = getValue("properties").jsonObject.string(key)

        val JsonObject.distinctId: String get() = property("distinct_id")
    }
}
