import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin. Nothing here may import android.*; that is what makes the logic testable without a device
// and reusable by a future desktop sensor (DESIGN.md section 3).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

dependencies {
    testImplementation(libs.junit)
}
