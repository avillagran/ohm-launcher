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
        // The public release is 0.0.6.
        versionCode = 12
        versionName = "0.0.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric installs Conscrypt process-wide; its socket trust callback reflects into
        // InetAddress on the host JVM. Without this, subsequent mutual-TLS tests fail on JDK 21.
        unitTests.all { it.jvmArgs("--add-opens=java.base/java.net=ALL-UNNAMED") }
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

    packaging {
        resources.pickFirsts += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
    }

    sourceSets {
        getByName("debug").assets.directories.add("src/direct/assets")
        getByName("release").assets.directories.add("src/direct/assets")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.activity:activity-ktx:1.12.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.transition:transition:1.5.1")
    implementation("androidx.recyclerview:recyclerview:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.github.termux.termux-app:terminal-view:v0.118.3")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20180813")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
