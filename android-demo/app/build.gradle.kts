import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.solanachetis.codamademo"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.solanachetis.codamademo"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // The generated clients are used as they are, straight from the JVM demo: no copy to drift.
    sourceSets["main"].java.srcDir("../../kotlin-demo/src/main/kotlin")

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Signing goes through Mobile Wallet Adapter: Seed Vault Wallet on the Seeker, or any MWA wallet.
    implementation("com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.1.1")
    // Same library the generated clients are written against.
    implementation("com.solanamobile:web3-solana:0.3.1")
}
