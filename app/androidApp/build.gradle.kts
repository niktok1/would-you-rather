import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

// The Google services' ids (CLAUDE.md §8a), from wyr.playgames.* as a Gradle property or in
// local.properties: none is that service off on the build.
apply(from = rootProject.file("gradle/wyr-android-services.gradle.kts"))
val playGamesAppId = extra["wyrPlayGamesAppId"] as String
val playGamesServerClientId = extra["wyrPlayGamesServerClientId"] as String

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
        // One Play Games project for every flavor (CLAUDE.md §8b, Play Games sign-in): its id is the
        // manifest's APP_ID, a string resource as Play Games reads it, empty on a build without it.
        buildConfigField("String", "PLAY_GAMES_APP_ID", "\"$playGamesAppId\"")
        buildConfigField("String", "PLAY_GAMES_SERVER_CLIENT_ID", "\"$playGamesServerClientId\"")
        resValue("string", "game_services_project_id", playGamesAppId)
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            // Signed with the debug key until there is a Play upload key, so a release build (not
            // debuggable, so Compose runs at full speed) installs on a phone for testing. Google Play
            // refuses a debug-signed build, so publishing needs a real signing config first.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
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
            resValue("string", "app_name", "WYR")
            manifestPlaceholders["usesCleartextTraffic"] = false
        }
        configureEach {
            dimension = "environment"
            // The flavor's name is its environment's, as WyrEnvironment.parse reads it.
            buildConfigField("String", "WYR_ENV", "\"$name\"")
        }
    }
}
