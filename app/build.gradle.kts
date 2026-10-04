plugins {
    id("com.android.application")
}

android {
    namespace = "com.ridderx.rrawlab"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ridderx.rrawlab"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
