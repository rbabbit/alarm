plugins {
    id("com.android.application")
}

android {
    namespace = "com.rbabbit.alarm"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rbabbit.alarm"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.core:core:1.16.0")
}
