plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mhd.sports"
    compileSdk = 34
    signingConfigs {
        // Khoá cố định: mọi bản build cùng chữ ký nên cài đè (cập nhật) được.
        // Dùng cá nhân thì để vậy; muốn phát hành công khai hãy đổi sang keystore riêng + GitHub Secrets.
        create("release") {
            storeFile = file("sportsplayer.jks")
            storePassword = "sportsplayer"
            keyAlias = "sportsplayer"
            keyPassword = "sportsplayer"
        }
    }
    defaultConfig {
        applicationId = "com.mhd.sports"
        minSdk = 21          // chạy được cả điện thoại lẫn TV Android 5.0+
        targetSdk = 34
        // CI truyền -PversionCode=<số lần build> để app so sánh với bản mới nhất trên GitHub
        val code = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionCode = code
        versionName = "1.$code"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
