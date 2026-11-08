plugins { id("com.android.application") }

val firebaseEnabled = file("google-services.json").exists()
check(providers.gradleProperty("requireSentryPush").orNull != "true" || firebaseEnabled) {
    "Sentry push requires app/google-services.json. Follow push-relay/SETUP.md before building."
}
// Google Play app signing certificate of com.starpilot.auto (verified from the installed Play APK).
val playAaSignerSha256 = "13AE59E1A17F956D77E46F6E10C23913CBE47291164ECB43C5D9F107B157319A"
val aaSignerSha256 = (System.getenv("AA_COMPANION_CERT_SHA256") ?: playAaSignerSha256).uppercase()
require(aaSignerSha256.isEmpty() || aaSignerSha256.matches(Regex("[0-9A-F]{64}")))
if (firebaseEnabled) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "com.starpilot.galaxy"
    compileSdk = 37
    defaultConfig {
        applicationId = "link.firestar.companion"
        minSdk = 26
        targetSdk = 36
        versionCode = 44
        versionName = "7.0.0.8"
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
            manifestPlaceholders["aaBridgeEnabled"] = "false"
            applicationId = "link.firestar.companion"
            resValue("string", "app_name", "Galaxy")
            buildConfigField("String", "DISTRIBUTION", "\"direct\"")
            buildConfigField("String", "AA_COMPANION_CERT_SHA256", "\"\"")
            signingConfig = signingConfigs.getByName("companion")
        }
        create("play") {
            dimension = "distribution"
            manifestPlaceholders["aaBridgeEnabled"] = "true"
            applicationId = "com.starpilot.galaxy"
            resValue("string", "app_name", "Galaxy")
            buildConfigField("String", "DISTRIBUTION", "\"play\"")
            buildConfigField("String", "AA_COMPANION_CERT_SHA256", "\"$aaSignerSha256\"")
            if (System.getenv("COMPANION_UPLOAD_KEY_PASSWORD") != null) signingConfig = signingConfigs.getByName("upload")
        }
    }
    if (firebaseEnabled) {
        sourceSets.getByName("direct").manifest.srcFile("src/direct/AndroidManifestFirebase.xml")
        sourceSets.getByName("play").manifest.srcFile("src/firebase/AndroidManifest.xml")
    }
    buildFeatures { buildConfig = true; resValues = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // ZXing's published POM omits the AndroidX runtime used by CaptureManager.
    implementation("androidx.core:core:1.2.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("com.google.code.gson:gson:2.11.0") { isTransitive = false }
    if (firebaseEnabled) {
        implementation(platform("com.google.firebase:firebase-bom:34.4.0"))
        implementation("com.google.firebase:firebase-messaging")
    }
}


android.sourceSets.getByName("main").kotlin.srcDir(if (firebaseEnabled) "src/firebase/java" else "src/polling/java")
