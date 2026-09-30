// :snippets:java — the Java examples the documentation site shows (ADR
// 29.9.26r D11). Every marked region is vendored at a released tag, and
// `make test-java` compiles every file, so what the site shows compiled.

plugins {
    id("lingara.jvm-conventions")
}

dependencies {
    implementation(project(":java"))
}
