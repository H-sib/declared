plugins {
    id("com.android.application")
}

android {
    namespace = "lab.probe"
    compileSdk = 37

    defaultConfig {
        applicationId = "lab.probe"
        minSdk = 29
        // Override with -PprobeTarget=37 to observe API 37 behavior changes (for example local network gating).
        targetSdk = (project.findProperty("probeTarget") as String?)?.toInt() ?: 36
        versionCode = 1
        versionName = "0.1"
    }

    flavorDimensions += "visibility"
    productFlavors {
        // "queries" sees other apps only through <queries> intent declarations.
        create("queries") {
            dimension = "visibility"
            applicationIdSuffix = ".q"
        }
        // "allpkgs" holds QUERY_ALL_PACKAGES and is the baseline for comparison.
        create("allpkgs") {
            dimension = "visibility"
            applicationIdSuffix = ".a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
