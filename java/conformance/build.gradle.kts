// :java:conformance — the Java library's conformance harness (ADR 29.9.26r
// D10; conformance/README.md, Writing a harness). A separate project, so it
// is never in the published jar. `installDist` writes the start script
// make/java.mk runs: build/install/conformance/bin/conformance.

plugins {
    id("lingara.jvm-conventions")
    application
}

dependencies {
    implementation(project(":java"))
}

application {
    mainClass = "com.getlingara.conformance.Harness"
}
