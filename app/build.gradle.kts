@file:Suppress("UnstableApiUsage")

import java.io.FileInputStream
import java.util.Properties

val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
keystoreProperties.load(FileInputStream(keystorePropertiesFile))

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization") version "2.3.20"
}

kotlin {
    compilerOptions {
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4
    }
}

android {
    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    ndkVersion = ("30.0.16248370")

    compileSdk = 37

    namespace = ("com.starlight")

    defaultConfig {

        androidResources.localeFilters += arrayOf(
            "en",
            "it",
            "pl",
            "bg",
            "ja",
            "ru",
            "nl",
            "zh-rCN",
            "zh-rTW",
            "ko",
            "es",
            "fr",
            "de",
            "ro",
            "uk",
            "no",
            "vi",
            "hu",
            "el",
            "pt",
            "cs",
            "sv",
            "in",
            "tr"
        )

        minSdk = 29
        targetSdk = 37

        versionName = ("14.2.0")
        versionCode = 70
        applicationId = ("com.github.kev506.starlight")

        externalNativeBuild {
            ndkBuild {
                abiFilters("armeabi-v7a", "arm64-v8a")
                arguments("APP_LDFLAGS+=-Wl,-z,max-page-size=16384")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        encoding = ("UTF-8")
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        //baseline = file("lint-baseline.xml")
        lintConfig = file("lint.xml")
    }


    bundle {
        language {
            enableSplit = false
        }
        density {
            enableSplit = true
        }
    }

    buildTypes {
        release {
            isDefault = true
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )

            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false

            /*
             To whoever is releasing or using an APK in release mode with
             Starlight's official application ID, please don't.
             It's unacceptable to release an APK using someone
             else's application ID. There is no legitimate reason, that
             anyone would need to comment out the following line, except myself
             when I release an official signed Starlight build.

             If you wish to release an APK with a different application ID,
             that would be acceptable and I wish you the best of luck. All I ask
             is to PLEASE change the applicationId before you publish.*/

            // TL;DR: Leave the following line alone!
            applicationIdSuffix = (".unofficial")
        }
        debug {
            applicationIdSuffix = (".debug")
            isDebuggable = true
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path("src/main/jni/Android.mk")
        }
    }

    // Generate native debug symbols to allow Google Play to symbolize our native crashes
    android.defaultConfig.ndk.debugSymbolLevel = ("FULL")
}

dependencies {

    implementation("androidx.compose.runtime:runtime:1.12.1")
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
    implementation("org.bouncycastle:bcpkix-jdk15on:1.70")
    implementation("org.jcodec:jcodec:0.2.5")
    implementation(platform("com.squareup.okhttp3:okhttp-bom:5.5.1"))
    implementation("org.jmdns:jmdns:3.6.4")
    implementation("com.github.cgutman:ShieldControllerExtensions:1.0.1")

    implementation("com.google.crypto.tink:tink-android:1.24.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.tvprovider:tvprovider:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.11.0")

    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-crashlytics-ktx:19.4.4")
    implementation("com.google.firebase:firebase-analytics-ktx:22.5.0")
    implementation("com.google.firebase:firebase-config-ktx:22.1.2")
    implementation ("commons-net:commons-net:3.13.0")

    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.code.gson:gson:2.15.0")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    implementation("androidx.compose.runtime:runtime-saveable:1.12.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout-compose:1.1.2")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation ("androidx.compose.ui:ui")
    implementation("androidx.tv:tv-foundation:1.0.0")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.compose.runtime:runtime-livedata:1.12.1")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.slf4j:slf4j-nop:2.0.20")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:3.0.0")
    implementation("com.squareup.okhttp3:logging-interceptor")
    implementation("io.github.kev506:compose-custom-youtube-player:1.0.5")
}
