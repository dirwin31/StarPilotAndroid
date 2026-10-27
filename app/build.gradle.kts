plugins { id("com.android.application") }

val firebaseEnabled = file("google-services.json").exists()
if (firebaseEnabled) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "com.starpilot.galaxy"
    compileSdk = 37
    defaultConfig {
        applicationId = "link.firestar.companion"
        minSdk = 26
        targetSdk = 36
        versionCode = 35
        versionName = "7.0.0.0"
    }
    signingConfigs {
        create("upload") {
            storeFile = file(System.getenv("COMPANION_UPLOAD_KEYSTORE") ?: "${System.getProperty("user.home")}/.starpilot/companion-upload.jks")
            storePassword = System.getenv("COMPANION_UPLOAD_KEY_PASSWORD")
            keyAlias = "upload"
            keyPassword = System.getenv("COMPANION_UPLOAD_KEY_PASSWORD")
        }
        create("companion") {
            storeFile = file(System.getenv("COMPANION_KEYSTORE") ?: "${System.getProperty("user.home")}/.starpilot/companion.jks")
            storePassword = System.getenv("COMPANION_KEY_PASSWORD")
            keyAlias = "companion"
            keyPassword = System.getenv("COMPANION_KEY_PASSWORD")
        }
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("direct") {
            dimension = "distribution"
            applicationId = "link.firestar.companion"
            resValue("string", "app_name", "Galaxy")
            buildConfigField("String", "DISTRIBUTION", "\"direct\"")
            signingConfig = signingConfigs.getByName("companion")
        }
        create("play") {
            dimension = "distribution"
            applicationId = "com.starpilot.galaxy"
            resValue("string", "app_name", "Galaxy")
            buildConfigField("String", "DISTRIBUTION", "\"play\"")
            if (System.getenv("COMPANION_UPLOAD_KEY_PASSWORD") != null) signingConfig = signingConfigs.getByName("upload")
        }
    }
    if (firebaseEnabled) {
        sourceSets.getByName("direct").manifest.srcFile("src/direct/AndroidManifestFirebase.xml")
        sourceSets.getByName("play").manifest.srcFile("src/firebase/AndroidManifest.xml")
    }
    buildFeatures { buildConfig = true; resValues = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.code.gson:gson:2.11.0") { isTransitive = false }
    if (firebaseEnabled) {
        implementation(platform("com.google.firebase:firebase-bom:34.4.0"))
        implementation("com.google.firebase:firebase-messaging")
    }
}


android.sourceSets.getByName("main").kotlin.srcDir(if (firebaseEnabled) "src/firebase/java" else "src/polling/java")
