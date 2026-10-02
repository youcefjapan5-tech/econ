plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "app.videowall"
    compileSdk = 34
    defaultConfig { applicationId = "app.videowall"; minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17"; freeCompilerArgs += "-opt-in=androidx.media3.common.util.UnstableApi" }
}
dependencies {
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
}
