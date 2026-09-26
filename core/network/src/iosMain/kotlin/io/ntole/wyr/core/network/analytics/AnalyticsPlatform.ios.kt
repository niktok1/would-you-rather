package io.ntole.wyr.core.network.analytics

import platform.UIKit.UIDevice

internal actual fun analyticsPlatform(): AnalyticsPlatform =
    AnalyticsPlatform(name = "ios", os = "iOS", osVersion = UIDevice.currentDevice.systemVersion, deviceType = MOBILE)
