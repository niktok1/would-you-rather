package io.ntole.wyr.core.network.environment

/** The emulator's alias for the host machine's loopback, since the emulator's own `localhost` is itself. */
internal actual val localApiBaseUrl: String = "http://10.0.2.2:8080"
