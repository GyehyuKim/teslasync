plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.teslasync"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.teslasync"
        minSdk = 33          // myAssociations + onDeviceAppeared(AssociationInfo) 깔끔히 사용
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")  // ComponentActivity + registerForActivityResult
    // HTTP는 표준 HttpURLConnection 사용 — 외부 의존성 없음. ponytail.
}
