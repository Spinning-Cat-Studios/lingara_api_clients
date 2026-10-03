// lingara-kotlin: the Kotlin library (ADR 29.9.26s).
//
// Generated models and stream types live in src/generated/kotlin (committed,
// and written only by `make codegen-kotlin`); the hand-written core in
// src/main/kotlin. Both compile in `main`, so explicit-API mode and
// allWarningsAsErrors cover the generated code too (D10). The two runtime
// dependencies are `api`: the models carry kotlinx.serialization annotations,
// and EventStream is a Flow. Publishing is the 29.9.26v D6a convention plugin,
// whose artifact is lingara-<project name>: lingara-kotlin.

plugins {
    id("lingara.kotlin-conventions")
    id("lingara.publishing-conventions")
    kotlin("plugin.serialization")
}

description = "The official Kotlin library for the Lingara API."

kotlin {
    explicitApi()
    sourceSets.named("main") {
        kotlin.srcDir("src/generated/kotlin")
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    systemProperty("lingara.view", rootProject.file("spec/generator/openapi.3.0.json").path)
    systemProperty("lingara.vectors", rootProject.file("conformance/vectors/webhook-signatures.json").path)
    systemProperty("lingara.generated", file("src/generated/kotlin").path)
}
