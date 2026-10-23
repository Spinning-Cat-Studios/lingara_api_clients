// Publishing to the Maven repository we host, maven.getlingara.com (Generator
// ADR 23.10.26a §2; Central's metering retired it for new versions), applied by
// java/ and kotlin/ (written here by ADR 29.9.26r D9, which creates the
// Gradle root).
//
// - com.vanniktech.maven.publish still supplies the POM, the sources and
//   javadoc jars and the signatures, signing from in-memory keys, so no
//   keyring file exists anywhere. The keys arrive as
//   ORG_GRADLE_PROJECT_signingInMemoryKey and ...signingInMemoryKeyPassword.
// - The `lingara` repository is the R2 bucket behind maven.getlingara.com,
//   written through Gradle's own S3 transport. That transport downloads the
//   artifact's maven-metadata.xml, adds the version and uploads it again — the
//   merge a sync of a fresh staging repo would get wrong. The R2 endpoint
//   arrives as -Dorg.gradle.s3.endpoint from release.yml, so no build file
//   carries the account id.
// - The artifact is `lingara-<project name>`: lingara-java, lingara-kotlin.
// - Java's javadoc jar is real javadoc output; Kotlin's is the plugin's empty
//   jar, kept so the published layout matches the versions on Central (D6a).
// - The `staging` file repository under the root build/ is what staging's
//   dry run publishes to: only a repository publish writes the .md5 and .sha1
//   files, which publishToMavenLocal does not.

import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    signAllPublications()
    coordinates("com.getlingara", "lingara-${project.name}", project.version.toString())
    pom {
        name = "lingara-${project.name}"
        description = "The official ${project.name.replaceFirstChar(Char::uppercase)} library for the Lingara API."
        url = "https://github.com/Spinning-Cat-Studios/lingara_api_clients"
        licenses {
            license {
                name = "MIT"
                url = "https://opensource.org/license/mit"
            }
        }
        scm {
            url = "https://github.com/Spinning-Cat-Studios/lingara_api_clients"
            connection = "scm:git:https://github.com/Spinning-Cat-Studios/lingara_api_clients.git"
            developerConnection = "scm:git:ssh://git@github.com/Spinning-Cat-Studios/lingara_api_clients.git"
        }
        developers {
            developer {
                id = "spinning-cat-studios"
                name = "Spinning Cat Studios"
                email = "support@getlingara.com"
            }
        }
    }
}

pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
    mavenPublishing {
        configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true))
    }
}

pluginManager.withPlugin("java-library") {
    if (!pluginManager.hasPlugin("org.jetbrains.kotlin.jvm")) {
        mavenPublishing {
            configure(JavaLibrary(javadocJar = JavadocJar.Javadoc(), sourcesJar = true))
        }
    }
}

publishing {
    repositories {
        maven {
            name = "staging"
            url = uri(rootProject.layout.buildDirectory.dir("staging-repo"))
        }
        maven {
            name = "lingara"
            url = uri("s3://lingara-maven")
            credentials(AwsCredentials::class) {
                accessKey = providers.environmentVariable("R2_MAVEN_ACCESS_KEY_ID").orNull
                secretKey = providers.environmentVariable("R2_MAVEN_SECRET_ACCESS_KEY").orNull
            }
        }
    }
}
