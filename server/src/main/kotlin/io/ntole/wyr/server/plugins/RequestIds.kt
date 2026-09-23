package io.ntole.wyr.server.plugins

/**
 * Refuses an id from a request body that is blank or holds a control character, as the client's
 * mistake. Every route that reads an id from its body checks it here, so they all refuse the same
 * ids for the same reasons.
 *
 * PostgreSQL refuses a NUL in text, and a refused parameter fails the transaction as a database
 * error: retried, then a 500 for what is the client's mistake. No id has any control character, so
 * all of them are refused here rather than only NUL.
 */
fun requireValidId(
    field: String,
    id: String,
) {
    if (id.isBlank()) throw ApiFailure.validation("$field is blank")
    if (id.any(Char::isISOControl)) throw ApiFailure.validation("$field has a control character")
}
