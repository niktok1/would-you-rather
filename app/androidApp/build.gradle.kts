import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
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
        versionName = "1.0"
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
        // BuildConfig carries each flavor's environment to WyrApplication, and resValue its label.
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
