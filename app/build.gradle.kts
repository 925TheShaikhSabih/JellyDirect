import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// CI passes the run number so every build is newer than the last (the updater compares these).
val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.jellydirect.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jellydirect.app"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("int", "BUILD", "$buildNumber")
        // "owner/repo" of the GitHub project that publishes the releases (set automatically on Actions).
        buildConfigField("String", "REPO", "\"${System.getenv("GITHUB_REPOSITORY") ?: ""}\"")
    }

    flavorDimensions += "edition"
    productFlavors {
        create("standard") {
            dimension = "edition"
            buildConfigField("String", "FIXED_SERVER_URL", "\"\"")
            buildConfigField("String", "BRAND", "\"JellyDirect\"")
        }
        // Same app with the server address built in: no setup screen, just sign in.
        create("server") {
            dimension = "edition"
            applicationIdSuffix = ".server"
            buildConfigField("String", "FIXED_SERVER_URL", "\"http://140.245.81.217:8096\"")
            buildConfigField("String", "BRAND", "\"KUMO\"")
        }
    }

    // One small APK per CPU architecture (libVLC's native libraries are the bulk of the size),
    // plus a universal APK that installs anywhere.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    // Fixed key committed to the repo: every build is signed the same, so installs update in place and keep your login.
    signingConfigs {
        create("release") {
            storeFile = file("jellydirect.jks")
            storePassword = "jellydirect"
            keyAlias = "jellydirect"
            keyPassword = "jellydirect"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the release APK is directly installable.
            // Swap in your own signingConfig if you ever publish this.
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("dev.chrisbanes.haze:haze:1.7.0")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.navigation:navigation-compose:2.9.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Kodi-style on-device decoding: every codec/container is decoded on the phone, never the server.
    implementation("org.videolan.android:libvlc-all:3.7.6")
}
