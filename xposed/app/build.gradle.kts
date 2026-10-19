plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tv.purple.xp"
    compileSdk = 36

    defaultConfig {
        applicationId = "tv.purple.xp"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.4.1-twitch31.1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // org.json ships in android.jar at runtime — compileOnly avoids a duplicate-class dex error.
    compileOnly("org.json:json:20240303")
}
