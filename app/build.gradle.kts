import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Release signing material, kept out of git: `keystore.properties` at the repo root, naming
 * a keystore plus its passwords (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`).
 *
 * Absent until the app has a real key. Without it a release build is signed with the debug
 * key instead, which is enough to install on your own phone and measure real performance,
 * and is refused by any store. Android ties an app's identity to its signing key, so create
 * the real one before the first build anyone else installs.
 */
val keystoreProperties: Properties? = rootProject.file("keystore.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

android {
    namespace = "com.mellow.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mellow.app"
        minSdk = 26
        targetSdk = 36
        // The release workflow passes these from the tag (.github/workflows/release.yml);
        // local builds keep the defaults.
        versionCode = providers.gradleProperty("mellow.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("mellow.versionName").orNull ?: "0.1.0"
    }

    signingConfigs {
        keystoreProperties?.let { props ->
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Lets a debug build sit next to a future release build on the same phone.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        // The app to try things out on. It is the release build in every way that can be
        // felt (shrunk, optimised, not debuggable), but to a phone it is a different app:
        // its own package name, so it sits next to the real one with its own playlists
        // and settings, and the debug key that every Android toolchain already has, so
        // nothing about it needs guarding. It can be replaced, broken or removed without
        // the real app noticing. Install it with `./gradlew :app:installBeta`.
        create("beta") {
            initWith(getByName("release"))
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            signingConfig = signingConfigs.getByName("debug")
            // The other modules come only as debug and release. Take their release.
            matchingFallbacks += "release"
        }
    }

    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":core:playback"))
    implementation(project(":feature:home"))
    implementation(project(":feature:library"))
    implementation(project(":feature:player"))
    implementation(project(":feature:search"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
}
