plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

group = "ai.pushpilot"
version = "2.0.0"

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
        singleVariant("release") { withSourcesJar() }
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    testImplementation("junit:junit:4.13.2")
    // The platform's org.json is a stub in JVM tests; use the real library there only.
    testImplementation("org.json:json:20180813")
}

// No repository is configured: `./gradlew :sdk:publishToMavenLocal` is the only way to publish for now.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "ai.pushpilot"
            artifactId = "sdk"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("PushPilot Android SDK")
                description.set("Registers an app installation with PushPilot and forwards its FCM push token.")
                url.set("https://pushpilot.ai")
            }
        }
    }
}
