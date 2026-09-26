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

// The app's version: its package's, and every analytics event's (CLAUDE.md §8g), which the app reads
// from the wyr.app.version property, on `run` as in a package.
val appVersion = "1.0.0"

compose.desktop {
    application {
        mainClass = "io.ntole.wyr.MainKt"
        jvmArgs += listOf("-Dwyr.app.version=$appVersion")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "io.ntole.wyr"
            packageVersion = appVersion
        }
    }
}
