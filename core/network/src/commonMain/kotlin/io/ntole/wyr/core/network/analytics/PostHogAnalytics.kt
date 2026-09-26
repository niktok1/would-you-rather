package io.ntole.wyr.core.network.analytics

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.network.TokenStorage
import io.ntole.wyr.core.network.environment.WyrEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * [Analytics] over PostHog's public HTTP API (CLAUDE.md §8g): no SDK, the Ktor client already in the
 * tree, and its `/batch/` endpoint, which takes events a batch at a time.
 *
 * - *Who*: a random id per install, kept in [storage] under a key of each environment's own
 *   ([idKeyFor]), so a DEV build's player is never a PROD one's. [identify] makes it the account's
 *   player, and [reset] a fresh random one.
 * - *When*: every [BATCH_SIZE] events, [FLUSH_INTERVAL] after the first event waiting, and on
 *   [flush], which the app calls as it goes to the background. At most [MAX_QUEUED] wait, in memory
 *   only, the oldest dropped first; a batch the network or the service failed goes back to wait, and
 *   one the service refused is dropped, since it would be refused again.
 * - *Never in the game's way*: every call returns at once, on the caller's thread, and the rest runs
 *   one step at a time on [dispatcher], so no two steps race and nothing a send does can fail the
 *   caller. A step that throws anything is swallowed there.
 * - *Off* ([setEnabled]): nothing more is sent, and what waited is dropped. The choice is the
 *   device's, under [ENABLED_KEY], whatever the environment, as the language is (§8f).
 *
 * Every event carries the install's id, a session ([SESSION_IDLE] without an event starts another), the
 * screen shown, the app's version, the platform and its OS, and the environment; nothing personal.
 * With no [config], a build with no key, nothing is kept, read or sent but the choice itself.
 */
public class PostHogAnalytics(
    private val config: PostHogConfig?,
    private val storage: TokenStorage,
    environment: WyrEnvironment,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    engine: HttpClientEngine? = null,
    private val clock: Clock = Clock.System,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : Analytics {
    // One step at a time: the state below is read and written only from coroutines of this scope.
    private val scope =
        CoroutineScope(SupervisorJob() + dispatcher.limitedParallelism(1) + CoroutineExceptionHandler { _, _ -> })
    private val client: HttpClient? = config?.let { clientFor(engine) }
    private val idKey = idKeyFor(environment)
    private val environmentName = environment.name.lowercase()
    private val platform by lazy { analyticsPlatform() }

    private val switch = MutableStateFlow(readOrNull(ENABLED_KEY) != OFF)
    override val enabled: StateFlow<Boolean> = switch.asStateFlow()

    private val queue = ArrayDeque<JsonObject>()
    private var distinctId: String? = null
    private var session: Session? = null
    private var screenName: String? = null
    private var timer: Job? = null
    private var sending = false
    private var backingOff = false

    override fun setEnabled(enabled: Boolean) {
        if (switch.value == enabled) return
        switch.value = enabled
        scope.launch {
            if (!enabled) {
                queue.clear()
                timer?.cancel()
                timer = null
            }
            persist(ENABLED_KEY, if (enabled) ON else OFF)
        }
    }

    override fun track(
        event: String,
        properties: Map<String, Any?>,
    ) {
        if (client == null || !switch.value) return
        val moment = now()
        // A copy, so a map the caller changes later is sent as it was.
        val own = properties.toMap()
        scope.launch { add(event, own, moment) }
    }

    override fun screen(name: String) {
        if (client == null) return
        val send = switch.value
        val moment = now()
        scope.launch {
            screenName = name
            if (send) add(SCREEN_EVENT, mapOf(SCREEN_NAME to name), moment)
        }
    }

    override fun identify(playerId: String) {
        if (client == null) return
        val send = switch.value
        val moment = now()
        scope.launch {
            val before = currentId()
            if (before == playerId) return@launch
            distinctId = playerId
            persist(idKey, playerId)
            // The service joins what the install sent as before to the player from here on.
            if (send) add(IDENTIFY_EVENT, mapOf(ANON_DISTINCT_ID to before), moment)
        }
    }

    override fun reset() {
        if (client == null) return
        scope.launch {
            val fresh = Uuid.random().toString()
            distinctId = fresh
            session = null
            persist(idKey, fresh)
        }
    }

    override fun flush() {
        if (client == null) return
        scope.launch { sendAll() }
    }

    private fun now(): Moment = Moment(clock.now(), timeSource.markNow())

    private suspend fun add(
        name: String,
        own: Map<String, Any?>,
        moment: Moment,
    ) {
        queue.addLast(eventOf(name, own, moment))
        while (queue.size > MAX_QUEUED) queue.removeFirst()
        armTimer()
        if (queue.size >= BATCH_SIZE && !backingOff) sendAll()
    }

    private suspend fun eventOf(
        name: String,
        own: Map<String, Any?>,
        moment: Moment,
    ): JsonObject {
        val id = currentId()
        val sessionId = sessionAt(moment)
        return buildJsonObject {
            put("event", name)
            put("uuid", Uuid.random().toString())
            put("timestamp", moment.at.toString())
            put(
                "properties",
                buildJsonObject {
                    own.forEach { (key, value) -> put(key, value.toJson()) }
                    put("distinct_id", id)
                    put("\$session_id", sessionId)
                    screenName?.let { put(SCREEN_NAME, it) }
                    put("\$lib", LIB)
                    put("\$app_version", config?.appVersion ?: PostHogConfig.UNKNOWN_VERSION)
                    put("\$os", platform.os)
                    put("\$os_version", platform.osVersion)
                    put("\$device_type", platform.deviceType)
                    put("platform", platform.name)
                    put("environment", environmentName)
                },
            )
        }
    }

    /** The install's id: as stored, or a fresh one, stored before anything else can ask for it. */
    private suspend fun currentId(): String {
        distinctId?.let { return it }
        val stored = readOrNull(idKey)
        val id = stored ?: Uuid.random().toString()
        distinctId = id
        if (stored == null) persist(idKey, id)
        return id
    }

    /** The session [moment] is in: the one going, unless it has been idle too long or run too long. */
    private fun sessionAt(moment: Moment): String {
        val current = session
        val next =
            when {
                current == null -> Session.startedAt(moment)
                moment.mark - current.lastSeen >= SESSION_IDLE -> Session.startedAt(moment)
                moment.mark - current.started >= SESSION_MAX -> Session.startedAt(moment)
                else -> current.copy(lastSeen = maxOf(current.lastSeen, moment.mark))
            }
        session = next
        return next.id
    }

    /** Sends what waits [FLUSH_INTERVAL] from now, unless a send is due already. */
    private fun armTimer() {
        if (timer?.isActive == true || queue.isEmpty()) return
        timer =
            scope.launch {
                delay(FLUSH_INTERVAL)
                timer = null
                sendAll()
            }
    }

    /** Sends every event waiting, a batch at a time; one send at a time, which takes what arrives meanwhile. */
    private suspend fun sendAll() {
        val client = client ?: return
        if (sending) return
        sending = true
        try {
            while (queue.isNotEmpty() && switch.value) {
                val batch = List(minOf(queue.size, MAX_BATCH)) { queue.removeFirst() }
                if (post(client, batch) == Delivery.FAILED) {
                    // Back to wait, in front of what came meanwhile, unless the player turned it off.
                    if (switch.value) batch.asReversed().forEach(queue::addFirst)
                    while (queue.size > MAX_QUEUED) queue.removeFirst()
                    backingOff = true
                    armTimer()
                    return
                }
                backingOff = false
            }
        } finally {
            sending = false
        }
    }

    private suspend fun post(
        client: HttpClient,
        batch: List<JsonObject>,
    ): Delivery {
        val body =
            buildJsonObject {
                put("api_key", config?.apiKey.orEmpty())
                put("batch", JsonArray(batch))
            }
        return try {
            val response =
                client.post(config?.host.orEmpty() + BATCH_PATH) {
                    setBody(TextContent(body.toString(), ContentType.Application.Json))
                }
            val status = response.status.value
            when {
                response.status.isSuccess() -> Delivery.SENT
                status == TIMEOUT || status == TOO_MANY_REQUESTS || status >= SERVER_ERROR -> Delivery.FAILED
                else -> Delivery.REFUSED
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Offline or cut short, as runApi reads it: an Exception, or the browser's bare Error.
            if (failure is Exception || failure::class == Error::class) Delivery.FAILED else throw failure
        }
    }

    private fun readOrNull(key: String): String? =
        try {
            storage.read(key)
        } catch (unreadable: Exception) {
            null
        }

    /** Kept for the next launch, or, when the storage cannot, for this one only. */
    private suspend fun persist(
        key: String,
        value: String,
    ) {
        try {
            storage.write(key, value)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unwritten: Exception) {
            // This run's only: the next launch starts from what was kept before.
        }
    }

    /** When something happened: the wall clock's for the service, and a monotonic mark for durations. */
    private class Moment(
        val at: Instant,
        val mark: ComparableTimeMark,
    )

    private data class Session(
        val id: String,
        val started: ComparableTimeMark,
        val lastSeen: ComparableTimeMark,
    ) {
        companion object {
            fun startedAt(moment: Moment): Session =
                Session(uuidV7(moment.at.toEpochMilliseconds()).toString(), moment.mark, moment.mark)
        }
    }

    private enum class Delivery {
        SENT,

        /** Refused for good, a bad key or payload: sent again, it would be refused again. */
        REFUSED,

        /** Not delivered, or not taken yet: worth sending again. */
        FAILED,
    }

    internal companion object {
        /** A batch goes once this many events wait. */
        const val BATCH_SIZE: Int = 20

        /** The most events waiting at once; past it the oldest goes. */
        const val MAX_QUEUED: Int = 1000

        /** The most events one request carries. */
        const val MAX_BATCH: Int = 100

        /** How long an event waits at most before a batch goes, however few wait. */
        val FLUSH_INTERVAL: Duration = 30.seconds

        /** How long without an event ends a session, as PostHog's own SDKs count it. */
        val SESSION_IDLE: Duration = 30.minutes

        /** The longest a session runs, however busy. */
        val SESSION_MAX: Duration = 24.hours

        /** How long a request may take before it is given up, and its batch waits again. */
        val SEND_TIMEOUT: Duration = 30.seconds

        /** Whether the player lets analytics be sent, one for the device: [ON] or [OFF], none being on. */
        const val ENABLED_KEY: String = "wyr.analytics.enabled"
        const val ON: String = "on"
        const val OFF: String = "off"

        const val BATCH_PATH: String = "/batch/"
        const val LIB: String = "wyr-kotlin"
        const val SCREEN_EVENT: String = "\$screen"
        const val SCREEN_NAME: String = "\$screen_name"
        const val IDENTIFY_EVENT: String = "\$identify"
        const val ANON_DISTINCT_ID: String = "\$anon_distinct_id"

        private const val TIMEOUT = 408
        private const val TOO_MANY_REQUESTS = 429
        private const val SERVER_ERROR = 500

        /**
         * The key [environment]'s install id is kept under, beside the sessions' (`SessionStore.keyFor`)
         * and none of them: an install is a player of each server apart.
         */
        fun idKeyFor(environment: WyrEnvironment): String = "wyr.analytics.id.${environment.name.lowercase()}"

        private fun clientFor(engine: HttpClientEngine?): HttpClient {
            val setup: HttpClientConfig<*>.() -> Unit = {
                // A refusal is an answer to read, not an exception.
                expectSuccess = false
                install(HttpTimeout) {
                    requestTimeoutMillis = SEND_TIMEOUT.inWholeMilliseconds
                    socketTimeoutMillis = SEND_TIMEOUT.inWholeMilliseconds
                    connectTimeoutMillis = SEND_TIMEOUT.inWholeMilliseconds
                }
            }
            return if (engine == null) HttpClient(setup) else HttpClient(engine, setup)
        }
    }
}

/** A property's value as JSON: a string, a number, a boolean, a list of those, or null; anything else as its text. */
internal fun Any?.toJson(): JsonElement =
    when (this) {
        null -> JsonNull
        is JsonElement -> this
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Enum<*> -> JsonPrimitive(name)
        is Iterable<*> -> JsonArray(map { it.toJson() })
        is Array<*> -> JsonArray(map { it.toJson() })
        else -> JsonPrimitive(toString())
    }

/**
 * A version 7 UUID for [epochMillis]: the time first, then random bits. PostHog reads when a session
 * began from its id, so a session's must be one.
 */
internal fun uuidV7(epochMillis: Long): Uuid {
    val bytes = Uuid.random().toByteArray()
    for (index in 0 until TIMESTAMP_BYTES) {
        bytes[index] = (epochMillis ushr (Byte.SIZE_BITS * (TIMESTAMP_BYTES - 1 - index))).toByte()
    }
    bytes[VERSION_BYTE] = ((bytes[VERSION_BYTE].toInt() and LOW_NIBBLE) or VERSION_7).toByte()
    bytes[VARIANT_BYTE] = ((bytes[VARIANT_BYTE].toInt() and VARIANT_MASK) or VARIANT_RFC).toByte()
    return Uuid.fromByteArray(bytes)
}

private const val TIMESTAMP_BYTES = 6
private const val VERSION_BYTE = 6
private const val VARIANT_BYTE = 8
private const val LOW_NIBBLE = 0x0F
private const val VERSION_7 = 0x70
private const val VARIANT_MASK = 0x3F
private const val VARIANT_RFC = 0x80
