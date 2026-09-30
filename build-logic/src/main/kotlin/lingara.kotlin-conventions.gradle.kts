// The Kotlin conventions :kotlin, :kotlin:conformance and :snippets:kotlin
// apply (ADR 29.9.26s D10), beside Java's lingara.jvm-conventions.
//
// - Compilation always uses the JDK 17 toolchain with jvmTarget 17, and no
//   toolchain is ever provisioned. `-PtestJdk=<n>` runs the tests on that
//   toolchain instead, leaving compilation on 17: CI's newest-LTS leg.
// - apiVersion and languageVersion are the oldest the pinned compiler accepts,
//   so the library's metadata is readable by as many Kotlin 2.x callers as the
//   pin allows (D1; the value is recorded in the ADR's As built).
// - allWarningsAsErrors covers the generated models too, since they compile in
//   the same `main` source set; a warning they raise is suppressed by name,
//   never by turning the flag off.
// - `project.version` is the repository's VERSION, so no Gradle file holds a
//   version.
// - Spotless with ktlint reads the hand-written sources only: src/generated is
//   a directory of the same `main` source set and would otherwise be formatted.
//   Checkstyle is Java's and cannot read Kotlin.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm")
    id("com.diffplug.spotless")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun pin(name: String): String = catalog.findVersion(name).get().requiredVersion

version = rootProject.file("VERSION").readText().trim()

kotlin {
    jvmToolchain(17)
    // Without it the POM declares kotlin-stdlib at the compiler's version, which a caller's older
    // compiler cannot read, however low apiVersion is.
    coreLibrariesVersion = pin("kotlin-stdlib-floor")
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        // 2.0 and 2.1 are deprecated under the 2.4 compiler, which allWarningsAsErrors refuses.
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
        allWarningsAsErrors = true
    }
}

spotless {
    kotlin {
        target("src/main/kotlin/**/*.kt", "src/test/kotlin/**/*.kt")
        ktlint(pin("ktlint"))
    }
}

dependencies {
    testImplementation(platform(catalog.findLibrary("junit-bom").get()))
    testImplementation(catalog.findLibrary("junit-jupiter").get())
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(catalog.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    providers.gradleProperty("testJdk").orNull?.let { jdk ->
        javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(jdk)
        }
    }
}
