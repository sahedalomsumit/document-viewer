import java.util.Properties

plugins {
    id("com.android.application")
}

val signingPropertiesFile = rootProject.file("key.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.isFile) signingPropertiesFile.inputStream().use { load(it) }
}

android {
    namespace = "com.sahed.document_viewer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sahed.document_viewer"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Relative paths in key.properties resolve from app/, matching Android convention.
            storeFile = signingProperties.getProperty("storeFile")?.let { file(it) }
            storePassword = signingProperties.getProperty("storePassword")
            keyAlias = signingProperties.getProperty("keyAlias")
            keyPassword = signingProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        // Device tests must never replace or uninstall the user's real app or its library.
        create("deviceTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".testbed"
            matchingFallbacks += "debug"
            resValue("string", "app_name", "Document Viewer Tests")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    testBuildType = "deviceTest"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        resValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// Keep a single, conveniently named installable debug build at the project root.
val stageInstallable = tasks.register("stageInstallable") {
    val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    val destination = rootProject.layout.projectDirectory.file("app.apk")
    dependsOn("assembleDebug")
    inputs.file(apk)
    outputs.file(destination)
    doLast { apk.get().asFile.copyTo(destination.asFile, overwrite = true) }
}

// Fail closed for release builds without preventing debug builds on an unsigned checkout.
val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    doLast {
        check(signingPropertiesFile.isFile) { "Release signing requires root key.properties." }
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword").forEach { key ->
            check(!signingProperties.getProperty(key).isNullOrBlank()) {
                "Release signing requires '$key' in key.properties."
            }
        }
        check(file(signingProperties.getProperty("storeFile")).isFile) {
            "Release keystore not found. Resolve storeFile relative to app/."
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseSigning)
}

tasks.register("stageReleaseInstallable") {
    val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    val destination = rootProject.layout.projectDirectory.file("app-release.apk")
    dependsOn("assembleRelease")
    inputs.file(apk)
    outputs.file(destination)
    doLast { apk.get().asFile.copyTo(destination.asFile, overwrite = true) }
}
