plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tobirg.rootmate"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tobirg.rootmate"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.0"
    }

    // Permanent release key (CI passes it via env from GitHub secrets) so that updates install over
    // older versions. Without the env (local builds) the debug key is used.
    val releaseKeystore = System.getenv("SIGNING_KEYSTORE_FILE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("rootmate") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = "rootmate"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("rootmate")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Output file name: <app name>-<version>.apk
    applicationVariants.all {
        val variant = this
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "RootMate-${variant.versionName}.apk"
        }
    }

    buildFeatures {
        viewBinding = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("com.google.android.material:material:1.12.0")
}
