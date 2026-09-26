/*
 * The app's version, named once (CLAUDE.md §8g): `wyr.app.version` in the repository's
 * gradle.properties, three whole numbers, MAJOR.MINOR.PATCH, the one form a desktop MSI package takes.
 * Every analytics event carries it as `$app_version`, so every platform's build says the same.
 *
 * Applied by each game module that builds an app, which reads `extra["wyrAppVersion"]`: `:app:androidApp`
 * for its `versionName`, `:app:desktopApp` for its `packageVersion` and the `wyr.app.version` property it
 * runs with, and `:app:webApp` for the `APP_VERSION` generateWyrAnalytics writes (gradle/wyr-analytics.gradle.kts).
 *
 * Xcode reads the iOS app's own, `MARKETING_VERSION` in app/iosApp/Configuration/Config.xcconfig, which
 * Gradle cannot write for it: so any build fails here until the two say the same.
 */
val appVersion = providers.gradleProperty("wyr.app.version").orNull.orEmpty().trim()
require(Regex("""\d+\.\d+\.\d+""").matches(appVersion)) {
    "wyr.app.version must be MAJOR.MINOR.PATCH, three whole numbers: \"$appVersion\""
}

val iosConfig = rootProject.layout.projectDirectory.file("app/iosApp/Configuration/Config.xcconfig")
providers.fileContents(iosConfig).asText.orNull?.let { text ->
    val marketingVersion =
        text
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("MARKETING_VERSION") }
            ?.substringAfter("=")
            ?.trim()
    require(marketingVersion == appVersion) {
        "MARKETING_VERSION in app/iosApp/Configuration/Config.xcconfig is $marketingVersion, but " +
            "wyr.app.version is $appVersion: change the two together"
    }
}

extra["wyrAppVersion"] = appVersion
