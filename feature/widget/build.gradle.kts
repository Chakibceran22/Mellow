// The widgets on the home screen. Views, not Compose: a widget is
// drawn by the launcher, in the launcher's process, from a description this app sends it,
// and that description can only name the platform's own views. So this is the one module
// with layouts in XML, and the one feature that does not use the design system: none of
// its code can run in another app's process. What a widget has of it is copied by hand:
// the palette (res/values/colors.xml) and the light on the glass (WidgetGlass). The
// typeface cannot cross at all, and nor can anything that needs to see behind the widget.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.mellow.feature.widget"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 26
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
    implementation(project(":core:playback"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
