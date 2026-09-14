@file:Suppress("UnstableApiUsage")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

plugins {
    // 0.9.0 reads JvmVendorSpec.IBM_SEMERU, which no longer exists in the Gradle 9 API used by
    // this repository's wrapper. The 1.x resolver is the Gradle 9-compatible line.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Accord"
include(":app", ":recyclerview")

// Automix analysis. Vendors aubio (GPL-3.0) behind a small JNI surface, in its own module so the
// vendored C tree and its licence sit apart from Fincord's own sources rather than inside :app.
include(":automix")

// The Cupertino widget library the upstream Accord UI is built on, vendored as a submodule. Its
// repository root is a full sample project, so point Gradle at the library module inside it rather
// than including the whole thing.
include(":Cupertino")
project(":Cupertino").projectDir = file("Cupertino/Cupertino")

// Carried over from upstream Accord, whose UI these modules back:
//   libPhonograph - the library model (Album/Artist/Playlist, the MediaStore readers) the Accord
//                   screens are written against. Vendored as a submodule the same way Cupertino is,
//                   with a thin wrapper module pointing Gradle at the library inside it.
//   hificore      - hidden-API AudioTrack access behind the audio format readout, plus reflective
//                   audio effects. Builds a small native library; the USB-audio half upstream
//                   leaves commented out is not built.
//   misc:*        - the ALAC renderer and the audio effect forwarding stubs hificore links against.
include(":libPhonograph", ":hificore", ":misc:audiofxfwd", ":misc:audiofxstub", ":misc:alacdecoder")
