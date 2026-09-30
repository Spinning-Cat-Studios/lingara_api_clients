// Maven Central publishing, exactly as ADR 29.9.26v D6a specifies, applied by
// java/ and kotlin/ (written here by ADR 29.9.26r D9, which creates the
// Gradle root).
//
// - com.vanniktech.maven.publish speaks the Central Portal API and signs from
//   in-memory keys, so no keyring file exists anywhere. The keys and the
//   Portal token arrive as ORG_GRADLE_PROJECT_signingInMemoryKey,
//   ...signingInMemoryKeyPassword, ...mavenCentralUsername and
//   ...mavenCentralPassword.
// - The artifact is `lingara-<project name>`: lingara-java, lingara-kotlin.
// - Java's javadoc jar is real javadoc output; Kotlin's is the plugin's empty
//   jar, since Central requires the file and not its contents (D6a).
// - The `staging` file repository under the root build/ is what staging's
//   dry run publishes to: only a repository publish writes the .md5 and .sha1
//   files Central requires, which publishToMavenLocal does not.

import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
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
    }
}
