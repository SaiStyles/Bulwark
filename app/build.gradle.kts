import com.android.build.api.artifact.SingleArtifact
import java.util.Properties

/**
 * Release signing, read from a file that is **never committed**.
 *
 * `keystore.properties` and the keystore it points at are gitignored. The key
 * has to be generated offline and kept off CI - `ROADMAP.md` v1.0 - so nothing
 * here creates one, and a checkout without it still builds: the release APK
 * simply comes out unsigned, exactly as it did before this existed.
 *
 * See `keystore.properties.example` for the four keys.
 */
val signingProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val canSignRelease = signingProperties.getProperty("storeFile")?.let {
    rootProject.file(it).exists()
} == true

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

        // **Not 1.0.** `ROADMAP.md` calls this v0.1 and nothing has shipped;
        // a first public build numbered 1.0 is a claim, and this app is careful
        // about claims everywhere else. Changed 2026-09-15.
        versionName = "0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (canSignRelease) {
                storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
                // v1 is off: an APK a user sideloads on Android 8+ is verified
                // by v2/v3, and leaving v1 on widens the signature surface for
                // nothing. minSdk is 26.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // **No git metadata in the APK.** AGP writes
            // META-INF/version-control-info.textproto with the commit that
            // built it, which makes the binary depend on git state rather than
            // on source. Two builds of identical code at different commits then
            // differ - measured 2026-09-15, and it was the only one of 108
            // entries that did.
            //
            // README tells people they can build this and compare it against a
            // release. That has to hold for somebody working from a source
            // tarball with no `.git`, or the promise is narrower than it reads.
            // Provenance is carried by the published fingerprint and the
            // release tag instead, neither of which a verifier has to
            // reconstruct.
            vcsInfo {
                include = false
            }

            // Null when no keystore.properties is present, which leaves the
            // APK unsigned rather than failing the build. A contributor
            // without the key can still build and test release.
            signingConfig = if (canSignRelease) signingConfigs.getByName("release") else null

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
        // AIDL was enabled for the Shizuku user service, which does not work
        // on MediaTek and was deleted. No .aidl files remain, so this ran an
        // extra compile task over nothing on every build.
        aidl = false
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
    implementation(libs.hiddenapibypass)
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
