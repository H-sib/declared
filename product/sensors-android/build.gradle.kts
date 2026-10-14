plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.h_sib.declared.sensors"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = true
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    api(project(":core"))
    testImplementation(libs.junit)
}
