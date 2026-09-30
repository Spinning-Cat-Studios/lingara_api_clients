// The convention plugins' own build (ADR 29.9.26r D9). Each plugin a
// convention applies is on this classpath at its catalogue pin, so no project
// build file names a plugin version.

plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.spotless.plugin)
    // 29.9.26v D6a: the Maven Central publishing plugin.
    implementation(libs.maven.publish.plugin)
    // 29.9.26s D10: lingara.kotlin-conventions applies the first, and
    // kotlin/build.gradle.kts the second, each without a version.
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin)
}
