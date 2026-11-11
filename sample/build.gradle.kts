plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// google-services.json is per developer and never committed; without it the app builds but gets no FCM token.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "ai.pushpilot.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.pushpilot.sample"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // Pass with -PpushpilotSdkKey=... (and optionally -PpushpilotBaseUrl=...) or in ~/.gradle/gradle.properties.
        buildConfigField("String", "PUSHPILOT_SDK_KEY", "\"${providers.gradleProperty("pushpilotSdkKey").getOrElse("")}\"")
        buildConfigField("String", "PUSHPILOT_BASE_URL", "\"${providers.gradleProperty("pushpilotBaseUrl").getOrElse("")}\"")
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":sdk"))
    // Only the sample uses Firebase; the SDK itself has no Firebase dependency.
    implementation("com.google.firebase:firebase-messaging:24.1.1")
}
