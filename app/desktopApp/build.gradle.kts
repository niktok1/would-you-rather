import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":app:shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

// The app's version, wyr.app.version in gradle.properties, every platform's (CLAUDE.md §8g): its
// package's, and every analytics event's, which the app reads from the wyr.app.version system property,
// on `run` as in a package; and the build number made from it, from wyr.app.build, which every request
// names (§8b, *Minimum client version*).
apply(from = rootProject.file("gradle/wyr-version.gradle.kts"))
val appVersion = extra["wyrAppVersion"] as String
val buildNumber = extra["wyrBuildNumber"] as Int

compose.desktop {
    application {
        mainClass = "io.ntole.wyr.MainKt"
        jvmArgs += listOf("-Dwyr.app.version=$appVersion", "-Dwyr.app.build=$buildNumber")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "io.ntole.wyr"
            packageVersion = appVersion
        }
    }
}
