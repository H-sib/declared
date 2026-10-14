import com.android.build.api.artifact.SingleArtifact
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.h_sib.declared.app"
    compileSdk = 37

    defaultConfig {
        // Permanent once published (DESIGN.md section 12). From the maintainer's GitHub name H-sib; an application ID cannot hold a hyphen, so it is written h_sib.
        // The release guard below still refuses any ID that contains ".placeholder.".
        applicationId = "io.github.h_sib.declared"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // The upload key lives outside the project and is never committed. Point DECLARED_KEYSTORE_PROPERTIES at its keystore.properties to
        // build a signed release (the bundle for Google Play); without it a release is built unsigned, as before.
        val propertiesPath = System.getenv("DECLARED_KEYSTORE_PROPERTIES")
        if (propertiesPath != null && File(propertiesPath).exists()) {
            val keys = Properties().apply { File(propertiesPath).inputStream().use { load(it) } }
            create("release") {
                storeFile = File(keys.getProperty("storeFile"))
                storePassword = keys.getProperty("storePassword")
                keyAlias = keys.getProperty("keyAlias")
                keyPassword = keys.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = true
        // targetSdk 36 is deliberate: it is what Google Play requires of new apps until 2027 (CONCEPT.md, Play constraints).
        disable += "OldTargetApi"
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":sensors-android"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    // The accessibility pass runs on a device or emulator: `gradle :app:connectedDebugAndroidTest` (see product/README.md).
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.compose.ui.test.junit4.accessibility)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Release guard: a build that could be uploaded must not carry the placeholder application ID.
val checkApplicationId = tasks.register("checkApplicationId") {
    val id = android.defaultConfig.applicationId.orEmpty()
    doLast {
        check(!id.contains(".placeholder.")) {
            "applicationId '$id' is still the placeholder. Set the real one in app/build.gradle.kts before building a release."
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkApplicationId) }

// Policy gate: the merged manifest of every variant must keep the promises the product makes (DESIGN.md sections 9, 10).
// "No INTERNET permission" is a headline claim of the project, so the build itself enforces it.
abstract class VerifyManifestPolicy : DefaultTask() {
    @get:InputFile
    abstract val manifest: RegularFileProperty

    @TaskAction
    fun verify() {
        // Comments are documentation, not declarations; only real elements and attributes count.
        val text = manifest.get().asFile.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        // Permissions Stage 1 may request. AndroidX adds one app-private signature permission by itself.
        val allowed = setOf(
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.QUERY_ADVANCED_PROTECTION_MODE",
            // The optional automatic checks (off until the person turns them on): to say a change needs a look, and to keep the schedule after a restart.
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.RECEIVE_BOOT_COMPLETED",
        )
        val requested = Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
        val unexpected = requested.filter { it !in allowed && !it.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") }
        check(unexpected.isEmpty()) { "Manifest requests permissions Stage 1 promised not to: $unexpected" }
        // Without these entries the app silently stops seeing other apps on Android 11 and later (lab V1).
        val requiredQueries = listOf(
            "android.accessibilityservice.AccessibilityService",
            "android.service.notification.NotificationListenerService",
            "android.view.InputMethod",
            "android.app.action.DEVICE_ADMIN_ENABLED",
        )
        val queries = Regex("<queries>.*?</queries>", RegexOption.DOT_MATCHES_ALL).find(text)?.value.orEmpty()
        val missing = requiredQueries.filter { !queries.contains("android:name=\"$it\"") }
        check(missing.isEmpty() && queries.contains("android.intent.category.LAUNCHER")) {
            "Manifest lost package-visibility entries the sensors depend on: $missing (launcher entry present: ${queries.contains("android.intent.category.LAUNCHER")})"
        }
        val forbidden = listOf("android.permission.QUERY_ALL_PACKAGES", "android.net.VpnService", "android.permission.BIND_VPN_SERVICE", "foregroundServiceType")
        val found = forbidden.filter { text.contains(it) }
        check(found.isEmpty()) { "Manifest contains something Stage 1 promised not to use: $found" }
    }
}

androidComponents {
    onVariants { variant ->
        val taskName = "verifyManifestPolicy" + variant.name.replaceFirstChar { it.uppercase() }
        val verify = tasks.register<VerifyManifestPolicy>(taskName) {
            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        tasks.matching { it.name == "check" || it.name == "assemble" + variant.name.replaceFirstChar { c -> c.uppercase() } }
            .configureEach { dependsOn(verify) }
    }
}
