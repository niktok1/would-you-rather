import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.StringReader
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

// The app's version, wyr.app.version in gradle.properties, every platform's (CLAUDE.md §8g).
apply(from = rootProject.file("gradle/wyr-version.gradle.kts"))
val appVersion = extra["wyrAppVersion"] as String

// The PostHog project this build sends analytics to (CLAUDE.md §8g), from wyr.posthog.key and
// wyr.posthog.host, as a Gradle property or in local.properties: none is analytics off.
apply(from = rootProject.file("gradle/wyr-analytics.gradle.kts"))
val posthogKey = extra["wyrPosthogKey"] as String
val posthogHost = extra["wyrPosthogHost"] as String

// The Play upload key (CLAUDE.md §8, *Release builds*): each of the four settings from local.properties,
// which git ignores, or else from the environment, and never committed. With all four a release build
// is signed with it; with fewer, with the debug key, so a release build still installs on a phone for
// testing, and every bundle*Release task, which makes what Play takes, fails before anything runs.
val localProperties =
    Properties().apply {
        providers
            .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
            .asText
            .orNull
            ?.let { text -> load(StringReader(text)) }
    }

fun uploadSetting(
    property: String,
    variable: String,
): String? =
    (localProperties.getProperty(property) ?: providers.environmentVariable(variable).orNull)
        ?.takeIf { it.isNotBlank() }

val uploadStoreFile = uploadSetting("wyr.upload.storeFile", "WYR_UPLOAD_STORE_FILE")
val uploadStorePassword = uploadSetting("wyr.upload.storePassword", "WYR_UPLOAD_STORE_PASSWORD")
val uploadKeyAlias = uploadSetting("wyr.upload.keyAlias", "WYR_UPLOAD_KEY_ALIAS")
val uploadKeyPassword = uploadSetting("wyr.upload.keyPassword", "WYR_UPLOAD_KEY_PASSWORD")
val uploadKeyMissing =
    mapOf(
        "wyr.upload.storeFile" to uploadStoreFile,
        "wyr.upload.storePassword" to uploadStorePassword,
        "wyr.upload.keyAlias" to uploadKeyAlias,
        "wyr.upload.keyPassword" to uploadKeyPassword,
    ).filterValues { it == null }.keys

if (uploadKeyMissing.isNotEmpty()) {
    val missing = uploadKeyMissing.joinToString()
    val modulePath = path
    // Asked once the task graph is known, so a bundle fails before any task runs.
    gradle.taskGraph.whenReady {
        val bundles =
            allTasks
                .filter { it.project.path == modulePath && Regex("""bundle\w*Release""").matches(it.name) }
                .map { it.name }
        if (bundles.isNotEmpty()) {
            throw GradleException(
                "${bundles.joinToString()} makes what Google Play takes, which must be signed with the Play " +
                    "upload key, and it is not configured: set $missing in local.properties, or the " +
                    "WYR_UPLOAD_* variables (NEXT-SESSION.md, Release builds and Google Play).",
            )
        }
    }
    // Said by each release APK's packaging as it signs one: an APK up to date signs nothing.
    val warning = "Signed with the debug key: the Play upload key is not configured ($missing)."
    tasks.named { Regex("""package\w*Release""").matches(it) }.configureEach {
        doFirst { logger.warn("$name: $warning") }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":app:shared"))

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)

    testImplementation(libs.kotlin.testJunit)
}

// WindowThemeTest reads the manifest and the resources from disk, so they are inputs of every unit test
// run, which would otherwise be up to date after a resource changed.
tasks.withType<Test>().configureEach {
    inputs.file("src/main/AndroidManifest.xml").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src/main/res").withPathSensitivity(PathSensitivity.RELATIVE)
}

android {
    namespace = "io.ntole.wyr"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "io.ntole.wyr"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        versionCode = 1
        versionName = appVersion
        // One project for every flavor: each event names its environment (CLAUDE.md §8g).
        buildConfigField("String", "POSTHOG_KEY", "\"$posthogKey\"")
        buildConfigField("String", "POSTHOG_HOST", "\"$posthogHost\"")
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        if (uploadKeyMissing.isEmpty()) {
            create("upload") {
                // A path relative to the repository's root, where local.properties is, or absolute.
                storeFile = rootProject.file(uploadStoreFile.orEmpty())
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }
    buildTypes {
        release {
            // The upload key when it is configured (above); otherwise the debug key, so a release build
            // (not debuggable, so Compose runs at full speed) still installs on a phone for testing,
            // which Google Play would refuse.
            signingConfig = signingConfigs.getByName(if (uploadKeyMissing.isEmpty()) "upload" else "debug")
            // R8 shrinks, optimizes and renames the code, and drops the resources nothing uses. Its
            // mapping, which turns a crash's renamed stack trace back into these names, lands in
            // build/outputs/mapping/<variant>/mapping.txt, and a bundle carries it to Play itself.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // BuildConfig carries each flavor's environment and the analytics project to WyrApplication, and
        // resValue its label.
        buildConfig = true
        resValues = true
    }

    // One flavor per server environment (CLAUDE.md §8e), each under an id and a launcher label of its
    // own, so all three install side by side. Only local may send plain http: the emulator reaches a
    // server on the host at http://10.0.2.2:8080, and the deployed ones are https only.
    flavorDimensions += "environment"
    productFlavors {
        create("local") {
            applicationIdSuffix = ".local"
            resValue("string", "app_name", "WYR Local")
            manifestPlaceholders["usesCleartextTraffic"] = true
        }
        create("dev") {
            // Android Studio's default variant: a physical phone cannot reach a server on the
            // developer's machine, so local is only for the emulator.
            isDefault = true
            applicationIdSuffix = ".dev"
            resValue("string", "app_name", "WYR Dev")
            manifestPlaceholders["usesCleartextTraffic"] = false
        }
        create("prod") {
            // The game's name as Home shows it, in Serbian Cyrillic (CLAUDE.md §8f), whatever the
            // device's language; the Play listing's name is set apart, in the Play Console.
            resValue("string", "app_name", "Шта би радије?")
            manifestPlaceholders["usesCleartextTraffic"] = false
        }
        configureEach {
            dimension = "environment"
            // The flavor's name is its environment's, as WyrEnvironment.parse reads it.
            buildConfigField("String", "WYR_ENV", "\"$name\"")
        }
    }
}
