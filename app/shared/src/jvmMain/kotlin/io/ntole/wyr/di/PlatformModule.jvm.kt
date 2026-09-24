package io.ntole.wyr.di

import io.ntole.wyr.core.network.JvmTokenStorage
import io.ntole.wyr.core.network.TokenStorage
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.net.URI
import java.net.URISyntaxException

actual fun platformModule(): Module = desktopModule(System.getenv())

/** The environment variable that points the desktop client at a server other than localhost. */
internal const val API_BASE_URL_VARIABLE: String = "WYR_API_BASE_URL"

/** The desktop bindings, reading [environment] rather than the process's own so a test can set it. */
internal fun desktopModule(environment: Map<String, String>): Module =
    module {
        single<TokenStorage> { JvmTokenStorage() }
        single(named(API_BASE_URL)) { desktopApiBaseUrl(environment[API_BASE_URL_VARIABLE]) }
    }

/**
 * [override], the value of [API_BASE_URL_VARIABLE], or [DevApiBaseUrl.LOCALHOST] when it is unset
 * or blank. It is trimmed, and must then be an http or https URL of a host, a port allowed, with
 * nothing after it but a slash: every route is an absolute path, so a path here would be dropped
 * without a word, and a query or fragment would never be sent.
 *
 * Anything else stops the app at start, naming the variable, rather than letting a typo fail every
 * request later as if the server were down.
 */
internal fun desktopApiBaseUrl(override: String?): String {
    val url = override?.trim()
    if (url.isNullOrEmpty()) return DevApiBaseUrl.LOCALHOST

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
