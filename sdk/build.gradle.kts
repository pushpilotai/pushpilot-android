plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
    signing
}

group = "ai.pushpilot"
version = "2.1.0"

android {
    namespace = "ai.pushpilot.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        buildConfigField("String", "SDK_VERSION", "\"$version\"")
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // android.util.Log is a stub in JVM tests; let it return defaults instead of throwing.
    testOptions { unitTests.isReturnDefaultValues = true }

    publishing {
        // Maven Central requires a sources jar and a javadoc jar next to the aar.
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    testImplementation("junit:junit:4.13.2")
    // The platform's org.json is a stub in JVM tests; use the real library there only.
    testImplementation("org.json:json:20180813")
}

val stagingDir = layout.buildDirectory.dir("staging-deploy")

// `publishToMavenLocal` for local builds; `centralBundle` stages a signed release for Maven Central (see RELEASING.md).
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "ai.pushpilot"
            artifactId = "sdk"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("PushPilot Android SDK")
                description.set("Registers an app installation with PushPilot and shows its pushes, with images, buttons, and delivery and tap tracking.")
                url.set("https://github.com/pushpilotai/pushpilot-android")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("pushpilotai")
                        name.set("PushPilot")
                        url.set("https://pushpilot.ai")
                    }
                }
                scm {
                    url.set("https://github.com/pushpilotai/pushpilot-android")
                    connection.set("scm:git:https://github.com/pushpilotai/pushpilot-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/pushpilotai/pushpilot-android.git")
                }
            }
        }
    }
    repositories {
        maven {
            name = "staging"
            url = uri(stagingDir)
        }
    }
}

signing {
    // Signs only when a GnuPG key is named in ~/.gradle/gradle.properties (signing.gnupg.keyName), so local builds need no key.
    if (providers.gradleProperty("signing.gnupg.keyName").isPresent) {
        useGpgCmd()
        sign(publishing.publications["release"])
    }
}

// Start each staging run empty so an older version's files never reach the bundle.
tasks.matching { it.name == "publishReleasePublicationToStagingRepository" }.configureEach {
    doFirst { delete(stagingDir) }
}

// The zip uploaded at central.sonatype.com → Publish Component. Refuses to build without signatures.
tasks.register<Zip>("centralBundle") {
    dependsOn("publishReleasePublicationToStagingRepository")
    doFirst {
        val unsigned = stagingDir.get().asFileTree.matching { include("**/*.aar", "**/*.jar", "**/*.pom") }
            .filter { !File(it.path + ".asc").exists() }
        check(unsigned.isEmpty) { "Unsigned files (set signing.gnupg.keyName): ${unsigned.files.map { it.name }}" }
    }
    from(stagingDir) { exclude("**/maven-metadata.xml*") } // Central builds its own metadata
    archiveFileName.set("pushpilot-android-sdk-$version-central-bundle.zip")
    destinationDirectory.set(layout.buildDirectory.dir("central"))
}
