@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

android {
    namespace = "uk.akane.accord.automix"
    compileSdk = 37
    ndkVersion = "28.0.13004108"

    defaultConfig {
        minSdk = 31

        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                // Same page-size argument hificore carries, and for the same reason: NDK r28 builds
                // 16 KB-page-compatible shared objects only when asked, and Android 15 devices with
                // 16 KB pages refuse to load a library that is not.
                arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 17 rather than the 21 hificore builds at, to match :app - the only consumer. Kotlin refuses to
// inline bytecode from a higher JVM target than the module doing the inlining, and there is nothing
// in this module worth taking that risk for.
kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}
