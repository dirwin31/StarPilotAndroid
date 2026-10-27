plugins { id("com.android.application") }

val primarySignerSha256 = System.getenv("AA_PRIMARY_CERT_SHA256")?.uppercase().orEmpty()
require(primarySignerSha256.isEmpty() || primarySignerSha256.matches(Regex("[0-9A-F]{64}")))

android {
    namespace = "com.starpilot.auto"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.starpilot.auto"
        minSdk = 26
        targetSdk = 36
        versionCode = 35
        versionName = "7.0.0.0"
        buildConfigField("String", "PRIMARY_SIGNER_SHA256", "\"$primarySignerSha256\"")
    }
    signingConfigs {
        create("upload") {
            storeFile = file(System.getenv("COMPANION_UPLOAD_KEYSTORE") ?: "${System.getProperty("user.home")}/.starpilot/companion-upload.jks")
            storePassword = System.getenv("COMPANION_UPLOAD_KEY_PASSWORD")
            keyAlias = "upload"
            keyPassword = System.getenv("COMPANION_UPLOAD_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") {
            if (System.getenv("COMPANION_UPLOAD_KEY_PASSWORD") != null) signingConfig = signingConfigs.getByName("upload")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
}
