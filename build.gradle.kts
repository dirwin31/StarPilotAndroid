buildscript {
    if (file("app/google-services.json").exists()) {
        repositories { google(); mavenCentral() }
        dependencies { classpath("com.google.gms:google-services:4.4.4") }
    }
}
plugins { id("com.android.application") version "9.4.1" apply false }
