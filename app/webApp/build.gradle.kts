import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// The server environment this web build targets, from -Pwyr.env (CLAUDE.md §8e): generateWyrEnv
// writes it into io.ntole.wyr.WYR_ENV, which the entry point hands to initKoin.
extra["wyrEnvPackage"] = "io.ntole.wyr"
apply(from = rootProject.file("gradle/wyr-env.gradle.kts"))

// The PostHog project this web build sends analytics to (CLAUDE.md §8g), from wyr.posthog.key and
// wyr.posthog.host, as a Gradle property or in local.properties: generateWyrAnalytics writes them, and
// the app's version, wyr.app.version in gradle.properties, into io.ntole.wyr's POSTHOG_KEY,
// POSTHOG_HOST and APP_VERSION. None is off.
apply(from = rootProject.file("gradle/wyr-version.gradle.kts"))
extra["wyrAnalyticsPackage"] = "io.ntole.wyr"
apply(from = rootProject.file("gradle/wyr-analytics.gradle.kts"))

kotlin {
    js {
        browser()
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":app:shared"))

            implementation(libs.compose.ui)
        }
        webMain.configure {
            kotlin.srcDir(tasks.named("generateWyrEnv"))
            kotlin.srcDir(tasks.named("generateWyrAnalytics"))
        }
    }
}
