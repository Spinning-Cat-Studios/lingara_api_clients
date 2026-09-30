// The JVM conventions every project of the shared Gradle root applies
// (ADR 29.9.26r D9, D10).
//
// - Compilation always uses the JDK 17 toolchain with `--release 17`. No
//   toolchain is ever provisioned, so a machine without a JDK 17 fails with
//   Gradle's own named error. Compiling on a newer JDK is never done:
//   `-Werror` would meet lint categories 17 does not have.
// - `-PtestJdk=<n>` runs the tests on that toolchain instead, leaving
//   compilation on 17: CI's newest-LTS leg.
// - `project.version` is the repository's VERSION, so no Gradle file holds a
//   version.
// - Spotless and Checkstyle read the hand-written sources only. src/generated
//   is a directory of the same `main` source set and would otherwise be linted.

plugins {
    `java-library`
    checkstyle
    id("com.diffplug.spotless")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun pin(name: String): String = catalog.findVersion(name).get().requiredVersion

version = rootProject.file("VERSION").readText().trim()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
    // JPMS compiles module-info.java and the generated models in one javac
    // run, so the flags cannot be scoped to hand-written sources. A category
    // the generated models trip is disabled by name, never -Werror itself.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addBooleanOption("Xdoclint:none", true)
        addStringOption("Xmaxwarns", "1")
        quiet()
    }
}

val handWritten = listOf("src/main/java", "src/test/java")

spotless {
    java {
        target(handWritten.map { "$it/**/*.java" })
        googleJavaFormat(pin("google-java-format"))
    }
}

checkstyle {
    toolVersion = pin("checkstyle")
    configFile = rootProject.file("java/checkstyle.xml")
    maxWarnings = 0
}

tasks.named<Checkstyle>("checkstyleMain") {
    // Checkstyle's grammar has no module declarations.
    setSource(fileTree("src/main/java") { exclude("module-info.java") })
}

tasks.named<Checkstyle>("checkstyleTest") {
    setSource(fileTree("src/test/java"))
}

dependencies {
    testImplementation(platform(catalog.findLibrary("junit-bom").get()))
    testImplementation(catalog.findLibrary("junit-jupiter").get())
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
