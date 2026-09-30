// :snippets:kotlin — the Kotlin examples the documentation site shows (ADR
// 29.9.26s D12). Every marked region is vendored at a released tag, and
// `make test-kotlin` compiles every file, so what the site shows compiled.
// install.kts sits outside every source set, so Gradle never compiles it.

plugins {
    id("lingara.kotlin-conventions")
}

dependencies {
    implementation(project(":kotlin"))
}