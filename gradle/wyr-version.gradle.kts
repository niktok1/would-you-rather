/*
 * The app's version, named once (CLAUDE.md §8g): `wyr.app.version` in the repository's
 * gradle.properties, three whole numbers, MAJOR.MINOR.PATCH, the one form a desktop MSI package takes.
 * Every analytics event carries it as `$app_version`, so every platform's build says the same.
 *
 * The build number is made from it, the same on every platform: MAJOR * 10000 + MINOR * 100 + PATCH,
 * so 1.2.3 is 10203, and MINOR and PATCH must each stay below 100 for the numbers to keep growing
 * with the version. It is Android's `versionCode`, and every request the game sends names it in
 * `X-Client-Version` (CLAUDE.md §8b, *Minimum client version*), which a server's minimum is compared
 * with.
 *
 * Applied by each game module that builds an app, which reads `extra["wyrAppVersion"]` and
 * `extra["wyrBuildNumber"]`: `:app:androidApp` for its `versionName` and `versionCode`, `:app:desktopApp`
 * for its `packageVersion` and the `wyr.app.version` and `wyr.app.build` properties it runs with, and
 * `:app:webApp` for the `APP_VERSION` and `BUILD_NUMBER` generateWyrAnalytics writes
 * (gradle/wyr-analytics.gradle.kts).
 *
 * Xcode reads the iOS app's own, `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION` (its
 * `CFBundleVersion`) in app/iosApp/Configuration/Config.xcconfig, which Gradle cannot write for it: so
 * any build fails here until they say the same as this.
 */
val appVersion = providers.gradleProperty("wyr.app.version").orNull.orEmpty().trim()
require(Regex("""\d+\.\d+\.\d+""").matches(appVersion)) {
    "wyr.app.version must be MAJOR.MINOR.PATCH, three whole numbers: \"$appVersion\""
}

val (major, minor, patch) = appVersion.split('.').map(String::toInt)
require(minor < 100 && patch < 100) {
    "wyr.app.version $appVersion: MINOR and PATCH must each be below 100, or the build number " +
        "(MAJOR * 10000 + MINOR * 100 + PATCH) would not grow with the version"
}
val buildNumber = major * 10_000 + minor * 100 + patch
require(buildNumber >= 1) { "wyr.app.version $appVersion makes build number 0; Android needs at least 1" }

val iosConfig = rootProject.layout.projectDirectory.file("app/iosApp/Configuration/Config.xcconfig")
providers.fileContents(iosConfig).asText.orNull?.let { text ->
    fun setting(name: String): String? =
        text
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.substringBefore("=").trim() == name }
            ?.substringAfter("=")
            ?.trim()

    val marketingVersion = setting("MARKETING_VERSION")
    require(marketingVersion == appVersion) {
        "MARKETING_VERSION in app/iosApp/Configuration/Config.xcconfig is $marketingVersion, but " +
            "wyr.app.version is $appVersion: change the two together"
    }
    val projectVersion = setting("CURRENT_PROJECT_VERSION")
    require(projectVersion == buildNumber.toString()) {
        "CURRENT_PROJECT_VERSION in app/iosApp/Configuration/Config.xcconfig is $projectVersion, but " +
            "wyr.app.version $appVersion makes build number $buildNumber: set it to $buildNumber"
    }
}

extra["wyrAppVersion"] = appVersion
extra["wyrBuildNumber"] = buildNumber
