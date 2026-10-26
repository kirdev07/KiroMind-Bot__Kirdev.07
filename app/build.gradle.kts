// Single source of the app version. CI passes -PappVersion=1.2.3 from the git tag.
val appVersion: String = (findProperty("appVersion") as String?) ?: "1.0.1"
// Must match SemVer.versionCode in the app: 1.2.3 -> 10203.
val appVersionCode: Int = appVersion.split(".").map { it.toInt() }.let { (a, b, c) -> a * 10_000 + b * 100 + c }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.kirdev.kiromindbot"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kirdev.kiromindbot"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Release signing comes only from environment variables (CI secrets); nothing secret is in the repo.
    signingConfigs {
        create("release") {
            val path = System.getenv("KIROMIND_KEYSTORE_PATH")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("KIROMIND_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KIROMIND_KEY_ALIAS")
                keyPassword = System.getenv("KIROMIND_KEY_PASSWORD")
            }
        }
    }

    // Exported Room schemas are checked in and used by migration tests.
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // Unsigned when the keystore is not provided: such an APK cannot be installed or self-updated.
            if (System.getenv("KIROMIND_KEYSTORE_PATH") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Duplicated metadata from the Anthropic SDK transitive dependencies.
        resources.excludes += setOf("/META-INF/DEPENDENCIES", "/META-INF/LICENSE*", "/META-INF/NOTICE*", "/META-INF/INDEX.LIST", "/META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    // Official Anthropic SDK (Java, used from Kotlin) for the optional Claude fallback.
    implementation("com.anthropic:anthropic-java:2.34.0")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests; Android provides it at runtime.
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.room:room-testing:2.7.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}
