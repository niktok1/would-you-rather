package io.ntole.wyr.di

/**
 * Koin qualifier name for the API base URL string: the environment's own
 * ([io.ntole.wyr.core.network.environment.WyrEnvironment.apiBaseUrl]), or the one the platform
 * lets its launch put in its place ([platformApiBaseUrl]).
 */
const val API_BASE_URL: String = "apiBaseUrl"
