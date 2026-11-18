// The Zing MP3 source module: the Vietnamese Zing MP3 catalog as progressive MP3 128/320 kbps.
// Built into Mochy for now; written against :modules:api only, so it can later ship as its own APK.
plugins {
    alias(libs.plugins.kotlin.serialization)
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":modules:api"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
