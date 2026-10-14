plugins {
    id("com.android.application")
}

android {
    namespace = "lab.fixture"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "role"
    productFlavors {
        for (role in listOf("a11y", "notif", "admin", "ime", "perms", "hidden", "boot", "sms", "shadow")) {
            create(role) {
                dimension = "role"
                applicationId = "lab.fixture.$role"
                buildConfigField("String", "ROLE", "\"$role\"")
                manifestPlaceholders["role"] = role
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
