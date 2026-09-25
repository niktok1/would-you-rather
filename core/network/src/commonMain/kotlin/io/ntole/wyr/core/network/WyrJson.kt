package io.ntole.wyr.core.network

import kotlinx.serialization.json.Json

/**
 * The client's single `Json` instance.
 *
 * **Do not change these flags casually.** `coerceInputValues` is the second half of the wire
 * enum rule in CLAUDE.md §5: without it, the `UNKNOWN` defaults on `ErrorCode` and
 * `QuestionStatus` do nothing and the first server-side enum addition breaks every installed client.
 * Categories do not lean on it: they are server data, plain ids on the wire, so a new one is only an
 * id this build has no name for. `ignoreUnknownKeys` is the same bargain for added fields.
 */
public val WyrJson: Json =
    Json {
        // Server added an enum value this build has never heard of -> fall back to the property's
        // declared default instead of throwing.
        coerceInputValues = true

        // Server added a field this build has never heard of -> skip it instead of throwing.
        ignoreUnknownKeys = true

        // Keep payloads small; every DTO default is a value the server may legitimately omit.
        explicitNulls = false
    }
