@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

android {
    namespace = "uk.akane.libphonograph"
    compileSdk = 37

    defaultConfig {
        testFixtures.enable = true
        minSdk = 21
        consumerProguardFiles("libPhonograph/libPhonograph/consumer-rules.pro")
    }

    // The library's sources live inside the submodule, one directory down, so every root here is
    // redirected. `kotlin` has to be set as well as `java`: the old Kotlin Gradle plugin folded the
    // java roots into the Kotlin compilation for you, and AGP 9's built-in Kotlin does not - leaving
    // it out compiles the module to an empty AAR and every reference to it fails in :app instead of
    // here, which is a long way from the mistake.
    sourceSets {
        named("main") {
            manifest.srcFile("libPhonograph/libPhonograph/src/main/AndroidManifest.xml")
            java.setSrcDirs(listOf("libPhonograph/libPhonograph/src/main/java"))
            kotlin.setSrcDirs(listOf("libPhonograph/libPhonograph/src/main/java"))
            res.setSrcDirs(listOf("libPhonograph/libPhonograph/src/main/res"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "libPhonograph/libPhonograph/proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.media3.common)
    implementation(libs.kotlinx.coroutines.android)
}
