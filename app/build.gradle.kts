
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseVersionCode = providers.gradleProperty("versionCode")
    .orElse(providers.environmentVariable("GITHUB_RUN_NUMBER"))
    .orElse("80")
    .get()
    .toInt()

val releaseVersionName = providers.gradleProperty("versionName")
    .orElse("1.$releaseVersionCode")
    .get()

val signingStorePassword = System.getenv("KEYSTORE_PASSWORD")
val signingKeyAlias = System.getenv("KEY_ALIAS")
val signingKeyPassword = System.getenv("KEY_PASSWORD")

val hasReleaseSigning = listOf(
    signingStorePassword,
    signingKeyAlias,
    signingKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.mhd.sports"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mhd.sports"
        minSdk = 21
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersionName
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file("sportsplayer.jks")
                storeType = "PKCS12"
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false

            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        getByName("debug") {
            // Giữ cấu hình mặc định.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")

    implementation("dev.mobile:dadb:1.2.10")
}
