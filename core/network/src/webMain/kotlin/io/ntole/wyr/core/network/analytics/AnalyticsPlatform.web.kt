package io.ntole.wyr.core.network.analytics

import web.navigator.navigator

internal actual fun analyticsPlatform(): AnalyticsPlatform {
    val (os, deviceType) = osOfUserAgent(navigator.userAgent)
    return AnalyticsPlatform(name = "web", os = os, osVersion = "", deviceType = deviceType)
}
