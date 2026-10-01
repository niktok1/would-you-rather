import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import java.io.StringReader
import java.util.Properties

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

/*
 * The admin token the desktop app starts with (CLAUDE.md §8d, *Moderation*), so the moderator need not
 * paste it at every launch: local.properties' `wyr.admin.token.local`, `.dev` or `.prod`, the one for the
 * server `WYR_ENV` names, handed to the app as `WYR_ADMIN_TOKEN` by every task that runs it from Gradle
 * (`run`, `runRelease`, `jvmRun`, the hot-reload runs), and written into nothing built. A token in the
 * shell's own `WYR_ADMIN_TOKEN` is never passed on, so a token reaches only the server it is for. Both
 * are read as the task starts, so neither lands in the configuration cache. The browser page has none:
 * its build would carry the token in its script.
 */
val localPropertiesText = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText
val runEnvironmentName = providers.environmentVariable("WYR_ENV").orElse("")

tasks.withType<JavaExec>().configureEach {
    // Read here, into locals, so the task action captures only these and not the script.
    val propertiesText = localPropertiesText
    val environmentName = runEnvironmentName
    doFirst {
        val run = this as JavaExec
        run.environment.remove("WYR_ADMIN_TOKEN")
        val properties = Properties().apply { propertiesText.orNull?.let { load(StringReader(it)) } }
        // As WyrEnvironment.parse reads it: trimmed, in any case, and none is local.
        val environment =
            environmentName
                .get()
                .trim()
                .lowercase()
                .ifEmpty { "local" }
        properties
            .getProperty("wyr.admin.token.$environment")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { token -> run.environment("WYR_ADMIN_TOKEN", token) }
    }
}
