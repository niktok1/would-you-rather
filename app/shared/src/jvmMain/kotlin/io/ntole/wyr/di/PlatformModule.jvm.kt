package io.ntole.wyr.di

import io.ntole.wyr.core.network.JvmTokenStorage
import io.ntole.wyr.core.network.TokenStorage
import io.ntole.wyr.core.network.environment.WyrEnvironment
import org.koin.core.module.Module
import org.koin.dsl.module
import java.net.URI
import java.net.URISyntaxException

actual fun platformModule(): Module =
    module {
        single<TokenStorage> { JvmTokenStorage() }
    }

internal actual fun platformApiBaseUrl(environment: WyrEnvironment): String =
    desktopApiBaseUrl(environment, System.getenv())

/**
 * The environment variable that points the desktop client at a server of its own, another machine
 * say, whatever environment it was started for.
 */
internal const val API_BASE_URL_VARIABLE: String = "WYR_API_BASE_URL"

/**
 * The URL [API_BASE_URL_VARIABLE] names in [variables], which wins, or [environment]'s own when it
 * names none. Reads [variables] rather than the process's own so a test can set them.
 */
internal fun desktopApiBaseUrl(
    environment: WyrEnvironment,
    variables: Map<String, String>,
): String = apiBaseUrlOverride(variables[API_BASE_URL_VARIABLE]) ?: environment.apiBaseUrl

/**
 * [value], the value of [API_BASE_URL_VARIABLE], or `null` when it is unset or blank. It is trimmed,
 * and must then be an http or https URL of a host, a port allowed, with nothing after it but a slash:
 * every route is an absolute path, so a path here would be dropped without a word, and a query or
 * fragment would never be sent.
 *
 * Anything else stops the app at start, naming the variable, rather than letting a typo fail every
 * request later as if the server were down.
 */
internal fun apiBaseUrlOverride(value: String?): String? {
    val url = value?.trim()
    if (url.isNullOrEmpty()) return null

    val uri =
        try {
            URI(url)
        } catch (malformed: URISyntaxException) {
            null
        }
    val usable =
        uri != null &&
            uri.scheme?.lowercase() in setOf("http", "https") &&
            !uri.host.isNullOrEmpty() &&
            uri.rawPath in setOf("", "/") &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            uri.rawUserInfo == null
    require(usable) {
        "$API_BASE_URL_VARIABLE must be an http or https URL with no path, such as " +
            "https://wyr.example.com, but is \"$url\""
    }
    return url
}
