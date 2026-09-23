package io.ntole.wyr.server.config

/**
 * One browser origin allowed through CORS, already split the way Ktor's `allowHost` takes it:
 * it rejects a host that still carries its scheme, and would fail the whole boot over it.
 *
 * @property host the host, with `:port` when one was given.
 * @property scheme `http` or `https`, or `null` when the entry named none — Ktor's own default
 *   schemes then apply.
 */
data class WebOrigin(
    val host: String,
    val scheme: String?,
)
