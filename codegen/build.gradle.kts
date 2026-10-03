// :codegen — the generated half of every JVM, Ruby and PHP library
// (ADR 29.9.26r D3). openapi-generator is pinned once, in the catalogue.
//
// generateJava always starts from an empty staging directory, runs
// openapi-generator and then StreamsCodegen there, and ends with a Sync of
// the staged com/getlingara/client/** tree and version.properties into
// java/src/generated/. Starting empty is what lets StreamsCodegen's refusal
// tell an openapi-generator copy of a union from its own last output; syncing
// only the package tree leaves openapi-generator's side output in build/; and
// Sync deletes a committed file the spec no longer produces.
// `-PcodegenOut=<dir>` changes only the sync target: check-codegen-java uses
// it to compare a fresh run with the committed tree.

import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    id("lingara.jvm-conventions")
    alias(libs.plugins.openapi.generator)
}

dependencies {
    implementation(libs.jackson.databind)
}

val view = rootProject.file("spec/generator/openapi.3.0.json")
val staging = layout.buildDirectory.dir("java-staging")
val stagedSources = staging.map { it.dir("openapi/src/main/java") }
val stagedResources = staging.map { it.dir("resources") }
val javaOut = providers.gradleProperty("codegenOut")
    .map { File(it) }
    .orElse(rootProject.file("java/src/generated"))

val cleanJavaStaging = tasks.register<Delete>("cleanJavaStaging") {
    delete(staging)
}

val generateJavaModels = tasks.register<GenerateTask>("generateJavaModels") {
    dependsOn(cleanJavaStaging)
    outputs.upToDateWhen { false }
    generatorName.set("java")
    library.set("native")
    inputSpec.set(view.path)
    outputDir.set(staging.get().dir("openapi").asFile.path)
    modelPackage.set("com.getlingara.client.model")
    ignoreFileOverride.set(file("java.openapi-generator-ignore").path)
    // Empty overrides of the javax nullability and @Generated annotations.
    templateDir.set(file("templates/java").path)
    globalProperties.set(mapOf("models" to "", "modelTests" to "false", "modelDocs" to "false"))
    configOptions.set(
        mapOf(
        "serializationLibrary" to "jackson",
        "openApiNullable" to "false",
        "hideGenerationTimestamp" to "true",
        "annotationLibrary" to "none",
        "sourceFolder" to "src/main/java",
        ),
    )
    // Java has no unsigned types, and a re-serialised OffsetDateTime need not
    // match the server's bytes, so timestamps and ids stay the server's
    // strings. An unknown integer format falls back to Integer silently,
    // which GeneratedModelTypesTest guards.
    typeMappings.set(
        mapOf(
        "integer+uint64" to "BigInteger",
        "integer+uint32" to "long",
        "integer+uint8" to "Integer",
        "string+date-time" to "String",
        "string+uuid" to "String",
        ),
    )
    importMappings.set(mapOf("BigInteger" to "java.math.BigInteger"))
    // No toUrlQueryString helpers: they call the generator's ApiClient.
    additionalProperties.set(mapOf("supportUrlQuery" to false))
}

val generateJavaStreams = tasks.register<JavaExec>("generateJavaStreams") {
    dependsOn(generateJavaModels)
    outputs.upToDateWhen { false }
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.getlingara.codegen.StreamsCodegen"
    args(
        view.path,
        rootProject.file("VERSION").path,
        stagedSources.get().asFile.path,
        stagedResources.get().asFile.path,
    )
}

// The event union, UnknownEvent, InboundEvent and their parser, from
// x-lingara-events (ADR 30.9.26aa D3), into com/getlingara/client/events/.
val generateJavaEvents = tasks.register<JavaExec>("generateJavaEvents") {
    dependsOn(generateJavaStreams)
    outputs.upToDateWhen { false }
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.getlingara.codegen.EventsCodegen"
    args(view.path, stagedSources.get().asFile.path)
}

tasks.register<Sync>("generateJava") {
    dependsOn(generateJavaEvents)
    outputs.upToDateWhen { false }
    from(stagedSources) {
        include("com/getlingara/client/**")
        into("java")
    }
    from(stagedResources) {
        into("resources")
    }
    into(javaOut)
}

tasks.test {
    systemProperty("lingara.view", view.path)
}

// ── Kotlin (ADR 29.9.26s D2) ─────────────────────────────────────────────────
//
// generateKotlin works the way generateJava does: an empty staging directory,
// openapi-generator's `kotlin` models, StreamsCodegen's Kotlin emitter (the
// four sealed unions, internal.Streams and internal.BuildInfo), then a Sync of
// the staged com/getlingara/kotlin/** tree into kotlin/src/generated/kotlin.
// `-PcodegenOut=<dir>` changes only the sync target.

val kotlinStaging = layout.buildDirectory.dir("kotlin-staging")
val kotlinStagedSources = kotlinStaging.map { it.dir("openapi/src/main/kotlin") }
val kotlinOut = providers.gradleProperty("codegenOut")
    .map { File(it) }
    .orElse(rootProject.file("kotlin/src/generated/kotlin"))

val cleanKotlinStaging = tasks.register<Delete>("cleanKotlinStaging") {
    delete(kotlinStaging)
}

val generateKotlinModels = tasks.register<GenerateTask>("generateKotlinModels") {
    dependsOn(cleanKotlinStaging)
    outputs.upToDateWhen { false }
    generatorName.set("kotlin")
    inputSpec.set(view.path)
    outputDir.set(kotlinStaging.get().dir("openapi").asFile.path)
    modelPackage.set("com.getlingara.kotlin.model")
    ignoreFileOverride.set(file("kotlin.openapi-generator-ignore").path)
    // The two property templates without @Contextual (AC25): the pin puts it
    // on kotlin.ULong and on every enum $ref, each of which has a serializer.
    templateDir.set(file("templates/kotlin").path)
    globalProperties.set(mapOf("models" to "", "modelTests" to "false", "modelDocs" to "false"))
    configOptions.set(
        mapOf(
            "serializationLibrary" to "kotlinx_serialization",
            "explicitApi" to "true",
            "enumPropertyNaming" to "UPPERCASE",
            "sourceFolder" to "src/main/kotlin",
        ),
    )
    // An unknown integer format falls back to kotlin.Int silently, a 32-bit
    // field that overflows, which GeneratedModelTypesTest guards. Timestamps
    // and ids stay the server's strings. A free-form value (EventEnvelope's
    // `data`, ADR 30.9.26aa) would be kotlin.Any, which has no serializer; it
    // is the JSON tree instead.
    typeMappings.set(
        mapOf(
            "AnyType" to "JsonElement",
            "integer+uint64" to "kotlin.ULong",
            "integer+uint32" to "kotlin.Long",
            "integer+uint8" to "kotlin.Int",
            "string+date-time" to "kotlin.String",
            "string+uuid" to "kotlin.String",
        ),
    )
    importMappings.set(mapOf("JsonElement" to "kotlinx.serialization.json.JsonElement"))
}

val generateKotlinStreams = tasks.register<JavaExec>("generateKotlinStreams") {
    dependsOn(generateKotlinModels)
    outputs.upToDateWhen { false }
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.getlingara.codegen.StreamsCodegen"
    args(
        "--kotlin",
        view.path,
        rootProject.file("VERSION").path,
        kotlinStagedSources.get().asFile.path,
    )
}

// The event union and its parser (ADR 30.9.26aa D3), as for Java.
val generateKotlinEvents = tasks.register<JavaExec>("generateKotlinEvents") {
    dependsOn(generateKotlinStreams)
    outputs.upToDateWhen { false }
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.getlingara.codegen.EventsCodegen"
    args("--kotlin", view.path, kotlinStagedSources.get().asFile.path)
}

tasks.register<Sync>("generateKotlin") {
    dependsOn(generateKotlinEvents)
    outputs.upToDateWhen { false }
    from(kotlinStagedSources) {
        include("com/getlingara/kotlin/**")
    }
    into(kotlinOut)
}

// ── Ruby (ADR 29.9.26t D2) ───────────────────────────────────────────────────
//
// generateRuby is openapi-generator's `ruby` models only: an empty staging
// directory, then a Sync of the staged lib/lingara/models/** into
// ruby/lib/lingara/models/. The four unions, the routes and the version
// constants are ruby/codegen/generate.rb's, which make codegen-ruby runs
// next; this task needs no JVM emitter of its own. `-PcodegenOut=<dir>`
// changes only the sync target (check-codegen-ruby passes <dir>/models).

val rubyStaging = layout.buildDirectory.dir("ruby-staging")
val rubyOut = providers.gradleProperty("codegenOut")
    .map { File(it) }
    .orElse(rootProject.file("ruby/lib/lingara/models"))

val cleanRubyStaging = tasks.register<Delete>("cleanRubyStaging") {
    delete(rubyStaging)
}

val generateRubyModels = tasks.register<GenerateTask>("generateRubyModels") {
    dependsOn(cleanRubyStaging)
    outputs.upToDateWhen { false }
    generatorName.set("ruby")
    inputSpec.set(view.path)
    outputDir.set(rubyStaging.get().asFile.path)
    ignoreFileOverride.set(file("ruby.openapi-generator-ignore").path)
    globalProperties.set(mapOf("models" to "", "modelTests" to "false", "modelDocs" to "false"))
    // The spec's `Error` component (the /v1 refusal envelope) would be
    // Lingara::Error, reopening the K3 base class every error inherits
    // from; the core never decodes the envelope into a model.
    modelNameMappings.set(mapOf("Error" to "ErrorEnvelope"))
    configOptions.set(
        mapOf(
            "moduleName" to "Lingara",
            "gemName" to "lingara",
            "hideGenerationTimestamp" to "true",
            "enumUnknownDefaultCase" to "true",
        ),
    )
}

tasks.register<Sync>("generateRuby") {
    dependsOn(generateRubyModels)
    outputs.upToDateWhen { false }
    from(rubyStaging.map { it.dir("lib/lingara/models") })
    into(rubyOut)
}

// ── PHP (ADR 29.9.26u D2) ────────────────────────────────────────────────────
//
// generatePhp is openapi-generator's `php-nextgen` models plus the two
// supporting files they reference: an empty staging directory, then a Sync of
// the staged src/Model/** and src/ObjectSerializer.php into php/src/. Every
// other file under php/src/ is preserved, so the hand-written core and
// php/codegen/generate.php's outputs survive the Sync, while a model the spec
// no longer produces is deleted. The four unions and their branch components
// are php/codegen/generate.php's, which make codegen-php runs next.
// `-PcodegenOut=<dir>` changes only the sync target.

val phpStaging = layout.buildDirectory.dir("php-staging")
val phpOut = providers.gradleProperty("codegenOut")
    .map { File(it) }
    .orElse(rootProject.file("php/src"))

val cleanPhpStaging = tasks.register<Delete>("cleanPhpStaging") {
    delete(phpStaging)
}

val generatePhpModels = tasks.register<GenerateTask>("generatePhpModels") {
    dependsOn(cleanPhpStaging)
    outputs.upToDateWhen { false }
    generatorName.set("php-nextgen")
    inputSpec.set(view.path)
    outputDir.set(phpStaging.get().asFile.path)
    ignoreFileOverride.set(file("php.openapi-generator-ignore").path)
    // ObjectSerializer without GuzzleHttp\Psr7\Utils and Configuration, and
    // with enumUnknownDefaultCase honoured on decode: the pin's reaches both
    // and throws on an unlisted enum value (D2's reference guard, AC2, AC33).
    templateDir.set(file("templates/php").path)
    invokerPackage.set("Lingara")
    modelPackage.set("Model")
    globalProperties.set(
        mapOf(
            "models" to "",
            "modelTests" to "false",
            "modelDocs" to "false",
            "supportingFiles" to "ModelInterface.php,ObjectSerializer.php",
        ),
    )
    configOptions.set(
        mapOf(
            "hideGenerationTimestamp" to "true",
            "enumUnknownDefaultCase" to "true",
        ),
    )
    // A re-serialised \DateTime need not match the server's bytes, which the
    // conformance harness compares, so timestamps and ids stay strings.
    typeMappings.set(mapOf("DateTime" to "string", "UUID" to "string"))
}

tasks.register<Sync>("generatePhp") {
    dependsOn(generatePhpModels)
    outputs.upToDateWhen { false }
    from(phpStaging.map { it.dir("src") }) {
        include("Model/**", "ObjectSerializer.php")
    }
    into(phpOut)
    preserve {
        include("**")
        exclude("Model/**", "ObjectSerializer.php")
    }
}
