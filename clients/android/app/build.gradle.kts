import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "org.nighthawklabs.treasure"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.nighthawklabs.treasure"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val localProps = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        fun str(v: String) = "\"" + v + "\""
        // Same Clerk instance and router as AutoTelemetry and the web app. The publishable key is public by design.
        buildConfigField("String", "CLERK_PUBLISHABLE_KEY", str(localProps.getProperty("CLERK_PUBLISHABLE_KEY", "pk_test_cHJpbWFyeS1qYXktMjEuY2xlcmsuYWNjb3VudHMuZGV2JA==")))
        // Public address of the router, no trailing slash. -PROUTER_BASE_URL=... on the command line wins.
        buildConfigField(
            "String", "ROUTER_BASE_URL",
            str((project.findProperty("ROUTER_BASE_URL") as String?) ?: localProps.getProperty("ROUTER_BASE_URL", "https://harness-router.nighthawklabs.org"))
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    // Real engine responses, shared with the iOS tests (clients/fixtures); decoded by ContractTest.
    sourceSets.getByName("test").resources.srcDir("../../fixtures")
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.clerk.android.api)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play)
    // On-device OCR and document scanning: nothing leaves the phone.
    implementation(libs.mlkit.text)
    implementation(libs.mlkit.scanner)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
