import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    jvm()

    js {
        browser()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    android {
        namespace = "io.ntole.wyr.app.shared"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
            // BackHandler, which binds Android's back to the back stack (SystemBack.android.kt).
            implementation(libs.androidx.activity.compose)
            // api, not implementation: WyrApplication calls androidContext() when starting DI, so
            // this is part of what the Android entry point compiles against.
            api(libs.koin.android)
            // Google Play Games Services v2, the no-click sign-in (CLAUDE.md §8a): Android's own, the §2
            // platform exception, behind the PlayGames port of :core:domain.
            implementation(libs.play.services.gamesV2)
            // Firebase Cloud Messaging, a moderator's decision pushed (CLAUDE.md §8a): Android's own too,
            // behind the DevicePush port, started from FirebaseOptions of the build's ids, with no
            // google-services plugin.
            implementation(libs.firebase.messaging)
        }
        commonMain.dependencies {
            // UI works in domain types only; :core:data is here purely to register DI bindings.
            api(project(":core:domain"))
            implementation(project(":core:data"))
            implementation(project(":core:network"))

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            // api: initKoin() takes a KoinAppDeclaration, so entry points compile against Koin.
            api(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.composeViewmodel)
        }
        webMain.dependencies {
            // The page's own location, which the update screen's Reload loads again (UpdateButton.web.kt):
            // the browser API wrappers :core:network's web storage already reads through.
            implementation(libs.wrappers.browser)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmTest.dependencies {
            // This machine's Skia, so a test can draw a screen off screen (ImageComposeScene).
            implementation(compose.desktop.currentOs)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}
