package io.ntole.wyr.core.network.analytics

import android.os.Build

internal actual fun analyticsPlatform(): AnalyticsPlatform =
    AnalyticsPlatform(
        name = "android",
        os = "Android",
        osVersion = Build.VERSION.RELEASE.orEmpty(),
        deviceType = MOBILE,
    )
