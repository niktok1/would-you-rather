package io.ntole.wyr.core.network.analytics

internal actual fun analyticsPlatform(): AnalyticsPlatform =
    AnalyticsPlatform(
        name = "desktop",
        os = osOfJvmName(System.getProperty("os.name").orEmpty()),
        osVersion = System.getProperty("os.version").orEmpty(),
        deviceType = DESKTOP,
    )
