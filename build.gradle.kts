// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // AGP 9 rather than the 8.13.2 upstream Accord builds against. The reason is JDK 25: AGP 8 ran
    // prefab in a forked JVM and treated its every stderr line as a build error, so the JVM's own
    // restricted-System::load warning was enough to kill hificore's native build. AGP 9 does prefab
    // in process. See gradle.properties.
    val agpVersion = "9.4.0"
    id("com.android.application") version agpVersion apply false
    id("com.android.library") version agpVersion apply false
    // Only parcelize now. AGP 9 compiles Kotlin itself, so org.jetbrains.kotlin.android is applied
    // nowhere and must not be - and the compiler it uses is 2.4.10, not this version, which only
    // sets which parcelize artifact is resolved.
    val kotlinVersion = "2.3.0"
    kotlin("plugin.parcelize") version kotlinVersion apply false
    // KSP moved to its own version line for the Kotlin 2.3 series, so it no longer carries the
    // Kotlin version as a prefix.
    id("com.google.devtools.ksp") version "2.3.11" apply false
}

tasks.withType(JavaCompile::class) {
    options.compilerArgs.add("-Xlint:all")
}
