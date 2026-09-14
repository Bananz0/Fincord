/*
 * Copyright (C) 2023 Helixform
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    id("com.android.library")
}

android {
    namespace = "androidx.recyclerview"
    compileSdk = 37

    defaultConfig {
        minSdk = 19
        multiDexEnabled = true
    }

    buildTypes {
        release {
            consumerProguardFiles("proguard-rules.pro")
        }
    }

    // Configured through the container rather than as `sourceSets.getByName("main") { }`: outside
    // the block, the Kotlin DSL resolves that accessor to AGP's legacy AndroidLibrarySourceSet,
    // which AGP 9's new DSL no longer returns, and the script fails on the cast before it runs.
    sourceSets {
        named("main") {
            res.srcDirs("res", "res-public")
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("androidx.annotation:annotation:1.10.0")
    //noinspection KtxExtensionAvailable
    api("androidx.core:core:1.19.0")
    //noinspection KtxExtensionAvailable,GradleDependency
    implementation("androidx.collection:collection:1.6.0")
    api("androidx.customview:customview:1.2.0")
    implementation("androidx.customview:customview-poolingcontainer:1.1.0")

    constraints {
        implementation("androidx.viewpager2:viewpager2:1.1.0")
    }
}
