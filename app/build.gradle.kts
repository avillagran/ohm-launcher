import java.util.Properties

plugins {
    id("com.android.application")
}

val releaseSigningProperties = Properties().apply {
    val propertiesFile = rootProject.file("key.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use(::load)
}

android {
    namespace = "cl.villagranquiroz.ohm_launcher"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "cl.villagranquiroz.ohm_launcher"
        minSdk = 24
        targetSdk = 36
        // Google Play requires a new internal code for every uploaded bundle.
        // The public release remains 0.0.4.
        versionCode = 6
        versionName = "0.0.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    signingConfigs {
        if (releaseSigningProperties.isNotEmpty()) {
            create("release") {
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
                storeFile = file(releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "PLAY_STORE_DISTRIBUTION", "false")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "PLAY_STORE_DISTRIBUTION", "false")
            signingConfig = signingConfigs.findByName("release")
                ?: error("Release signing requires key.properties")
        }
        create("playRelease") {
            initWith(getByName("release"))
            buildConfigField("boolean", "PLAY_STORE_DISTRIBUTION", "true")
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.12.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.transition:transition:1.5.1")
    implementation("androidx.recyclerview:recyclerview:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.github.termux.termux-app:terminal-view:v0.118.3")

    testImplementation("junit:junit:4.12")
    testImplementation("org.json:json:20180813")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
