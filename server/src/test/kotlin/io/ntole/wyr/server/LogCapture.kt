package io.ntole.wyr.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/** Runs [block] with every log event recorded, from any logger. */
internal fun withLogCapture(block: (ListAppender<ILoggingEvent>) -> Unit) {
    val logged = ListAppender<ILoggingEvent>().apply { start() }
    val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    root.addAppender(logged)
    try {
        block(logged)
    } finally {
        root.detachAppender(logged)
    }
}

/** Every event's message, with its exception's messages and stack as printed, as a log would hold them. */
internal fun ListAppender<ILoggingEvent>.printed(): List<String> =
    list.map { event ->
        val thrown =
            generateSequence(
                event.throwableProxy,
            ) { it.cause }.joinToString("\n") { "${it.className}: ${it.message}" }
        "${event.formattedMessage}\n$thrown"
    }
