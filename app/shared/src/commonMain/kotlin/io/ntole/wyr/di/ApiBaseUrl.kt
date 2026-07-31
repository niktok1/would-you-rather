package io.ntole.wyr.di

/** Koin qualifier name for the API base URL string supplied by [platformModule]. */
const val API_BASE_URL: String = "apiBaseUrl"

/**
 * Where the server lives during development.
 *
 * Android is the odd one out: an emulator cannot see the host's `localhost`, so it needs the
 * host-loopback alias instead. Both are development values — production reads from
 * [io.ntole.wyr.di.platformModule] overrides or a build-time value once there is a deployed URL.
 */
object DevApiBaseUrl {
    const val LOCALHOST: String = "http://localhost:8080"

    /** Android emulator's alias for the host machine's loopback interface. */
    const val ANDROID_EMULATOR: String = "http://10.0.2.2:8080"
}
