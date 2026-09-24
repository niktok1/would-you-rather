import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

/*
 * The moderation app (CLAUDE.md §3, §8d *Moderation*): a desktop window (`./gradlew :app:adminApp:run`)
 * and a browser page, one module holding both entry points, since nothing else consumes its UI. It
 * moderates and nothing more, so it depends on the client layers and never on :app:shared, the game.
 */

// The server environment a browser build targets, from -Pwyr.env (CLAUDE.md §8e): generateWyrEnv
// writes it into io.ntole.wyr.admin.WYR_ENV, which the page's entry point hands to initAdminKoin.
extra["wyrEnvPackage"] = "io.ntole.wyr.admin"
apply(from = rootProject.file("gradle/wyr-env.gradle.kts"))

kotlin {
    jvm()

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
            // The UI works in domain types only; :core:data is here to register the moderator's
            // bindings, and :core:network to name the server environment.
            implementation(project(":core:domain"))
            implementation(project(":core:data"))
            implementation(project(":core:network"))

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.composeViewmodel)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            // The moderator's calls driven over the real client configuration, answered by a mock.
            implementation(libs.ktor.clientCore)
            implementation(libs.ktor.clientMock)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            // Dispatchers.Main on the desktop, which viewModelScope runs on.
            implementation(libs.kotlinx.coroutinesSwing)
        }
        webMain.configure {
            kotlin.srcDir(tasks.named("generateWyrEnv"))
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.ntole.wyr.admin.MainKt"
    }
}
