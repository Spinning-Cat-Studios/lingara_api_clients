// :kotlin:conformance — the Kotlin library's conformance harness (ADR
// 29.9.26s D11; conformance/README.md, Writing a harness). A separate project,
// so it is never in the published jar. It reads every case as a JsonElement
// and declares no @Serializable class, so it needs no serialization plugin.
// `installDist` writes the start script make/kotlin.mk runs:
// build/install/conformance/bin/conformance.

plugins {
    id("lingara.kotlin-conventions")
    application
}

dependencies {
    implementation(project(":kotlin"))
}

application {
    mainClass = "com.getlingara.kotlin.conformance.HarnessKt"
}
