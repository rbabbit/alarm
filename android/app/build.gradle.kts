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
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.webkit:webkit:1.17.1")
}
