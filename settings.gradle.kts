// The repo-root Gradle build the two JVM libraries share (ADR 29.9.26r D9;
// Kotlin's three includes, ADR 29.9.26s D10).
//
// scs-snapshot reads this file statically to learn which directories the
// build needs, so it holds only literal include(...) calls and one
// includeBuild: no loop, and no project directory reassigned. Every project's
// directory is its path, `:snippets:java` being snippets/java/.

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "lingara-api-clients"

include(":codegen")
include(":java")
include(":java:conformance")
include(":snippets:java")
include(":kotlin")
include(":kotlin:conformance")
include(":snippets:kotlin")
