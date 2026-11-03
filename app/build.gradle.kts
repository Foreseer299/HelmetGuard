plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.helmet.guard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.helmet.guard"
        minSdk = 31
        targetSdk = 35
        versionCode = 43
        versionName = "2.2.0-alpha04"
        manifestPlaceholders["appLabel"] = "HelmetGuard"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk.abiFilters += setOf("arm64-v8a")
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs.getByName("debug") {
        providers.environmentVariable("HELMETGUARD_DEBUG_STORE").orNull?.let {
            storeFile = file(it)
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "HelmetGuard"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Production signing is intentionally supplied by CI/local secure properties.
            // Never ship a release signed with the debug key.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    androidResources.noCompress += listOf("onnx", "fst", "txt")
}

dependencies {
    val embeddedTtsAar = file("libs/sherpa-onnx-1.13.8.aar")
    if (embeddedTtsAar.exists()) implementation(files(embeddedTtsAar))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.ui.tooling)
}
