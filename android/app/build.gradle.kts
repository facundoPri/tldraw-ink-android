plugins { id("com.android.application") }
android {
 namespace = "com.facundopri.tldrawink"
 compileSdk = 37
 defaultConfig {
  applicationId = "com.facundopri.tldrawink"
  minSdk = 29
  targetSdk = 37
  versionCode = 6
  versionName = "0.3.1"
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
 }
 val releaseStore = System.getenv("SIGNING_STORE_FILE")
 signingConfigs {
  if (!releaseStore.isNullOrBlank()) {
   create("distribution") {
    storeFile = file(releaseStore)
    storePassword = System.getenv("SIGNING_STORE_PASSWORD")
    keyAlias = System.getenv("SIGNING_KEY_ALIAS")
    keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
   }
  }
 }
 buildTypes {
  release {
   isDebuggable = false
   if (!releaseStore.isNullOrBlank()) signingConfig = signingConfigs.getByName("distribution")
  }
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
}
dependencies {
 androidTestImplementation("androidx.test:runner:1.7.0")
 androidTestImplementation("androidx.test.ext:junit:1.3.0")
 implementation("androidx.core:core-ktx:1.19.0")
 implementation("androidx.webkit:webkit:1.17.0")
 implementation("androidx.ink:ink-authoring:1.1.0-alpha07")
 implementation("androidx.ink:ink-brush:1.1.0-alpha07")
 implementation("androidx.ink:ink-strokes:1.1.0-alpha07")
 implementation("androidx.input:input-motionprediction:1.0.0")
}
