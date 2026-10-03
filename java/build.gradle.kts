// lingara-java: the Java library (ADR 29.9.26r).
//
// Generated models and stream types live in src/generated/ (committed, and
// written only by `make codegen-java`); the hand-written core in src/main/.
// Jackson databind is the one runtime dependency, declared `api` because the
// models carry its annotations. Publishing is the 29.9.26v D6a convention
// plugin, whose artifact is lingara-<project name>: lingara-java.

plugins {
    id("lingara.jvm-conventions")
    id("lingara.publishing-conventions")
}

description = "The official Java library for the Lingara API."

sourceSets {
    main {
        java.srcDir("src/generated/java")
        resources.srcDir("src/generated/resources")
    }
}

dependencies {
    api(libs.jackson.databind)
}

tasks.test {
    systemProperty("lingara.view", rootProject.file("spec/generator/openapi.3.0.json").path)
    systemProperty("lingara.vectors", rootProject.file("conformance/vectors/webhook-signatures.json").path)
    systemProperty("lingara.generated", file("src/generated/java").path)
}
