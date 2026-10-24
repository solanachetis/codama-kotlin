plugins {
    kotlin("jvm") version "2.2.20"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Same library the Solana Mobile docs use for Android native apps.
    implementation("com.solanamobile:web3-solana:0.3.1")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
    }
}
