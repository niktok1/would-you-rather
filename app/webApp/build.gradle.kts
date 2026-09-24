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
        }
    }
}
