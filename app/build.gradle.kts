import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes -PversionCode / -PversionName; local builds fall back to 1 / "dev".
val appVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val appVersionName = (project.findProperty("versionName") as String?) ?: "dev"

// Optional stable release key, supplied by CI secrets. Without it the release
// APK is signed with the debug key so it still installs.
val keystorePath: String? = System.getenv("COLONYSIM_KEYSTORE")

android {
    namespace = "com.colonysim.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.colonysim.app"
        minSdk = 24
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("COLONYSIM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("COLONYSIM_KEY_ALIAS")
                keyPassword = System.getenv("COLONYSIM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
}
