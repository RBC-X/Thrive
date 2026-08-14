plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10"
}

android {
    namespace = "com.thrive.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.thrive.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "1.2.8"
    }

    signingConfigs {
        create("release") {
            // Credentials live in gradle.properties (gitignored) or the
            // THRIVE_KEYSTORE_* env vars — never in source control.
            val ksPass = providers.gradleProperty("THRIVE_KEYSTORE_PASSWORD").orNull
                ?: System.getenv("THRIVE_KEYSTORE_PASSWORD")
            val keyPass = providers.gradleProperty("THRIVE_KEYSTORE_KEY_PASSWORD").orNull
                ?: System.getenv("THRIVE_KEYSTORE_KEY_PASSWORD")
            storeFile = rootProject.file("thrive-release.keystore")
            storePassword = ksPass
                ?: error("THRIVE_KEYSTORE_PASSWORD missing: set it in local gradle.properties or the environment")
            keyAlias = providers.gradleProperty("THRIVE_KEYSTORE_KEY_ALIAS").orNull
                ?: System.getenv("THRIVE_KEYSTORE_KEY_ALIAS")
                ?: "thrive"
            keyPassword = keyPass
                ?: error("THRIVE_KEYSTORE_KEY_PASSWORD missing: set it in local gradle.properties or the environment")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
