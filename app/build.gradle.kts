import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.refine)
}

android {
    namespace = "com.bulwark.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.bulwark.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Code that is not in the APK cannot be exploited. Shrinking is a
            // security control here, not only a size one - see
            // context/_shared/security.md. Keep rules in proguard-rules.pro
            // explain every exception.
            optimization {
                enable = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        aidl = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Privilege layer — see context/_shared/shizuku.md
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.refine.runtime)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// ---------------------------------------------------------------------------
// Zero-network enforcement.
//
// context/_shared/conventions.md makes "no network code" a hard constraint and
// context/_shared/supply-chain.md treats it as a security boundary: with no
// INTERNET permission, a compromised build cannot exfiltrate anything without
// a manifest change loud enough to show up in review and in a reproducible
// build diff.
//
// A promise a human has to remember is not a boundary. This makes the build
// fail instead.
// ---------------------------------------------------------------------------

abstract class VerifyNoNetworkPermission : DefaultTask() {

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val manifest = mergedManifest.get().asFile.readText()
        val banned = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
        )
        val found = banned.filter { manifest.contains(it) }
        if (found.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Bulwark declares a network permission. This is a hard constraint, not a lint warning.")
                    appendLine()
                    found.forEach { appendLine("  found: $it") }
                    appendLine()
                    appendLine("It may have arrived from a new dependency rather than from our own manifest;")
                    appendLine("check the merged manifest and the dependency you last added.")
                    appendLine("Rationale: context/_shared/supply-chain.md")
                }
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        val verify = tasks.register<VerifyNoNetworkPermission>(
            "verify${variant.name.replaceFirstChar { it.uppercase() }}NoNetworkPermission"
        ) {
            group = "verification"
            description = "Fails the build if a network permission reaches the merged manifest."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        // Bind to assemble so it cannot be forgotten, not just to check.
        // tasks.matching is lazy: assembleDebug does not exist yet at this point.
        val assembleName = "assemble${variant.name.replaceFirstChar { it.uppercase() }}"
        tasks.matching { it.name == assembleName }.configureEach {
            dependsOn(verify)
        }
    }
}
