plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    api("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.test {
    useJUnitPlatform()
    // The interop test depends on live external state, so never serve it from a cache.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    // Interop tests talk to a real agent; pass its invite/relay through the environment.
    environment("REMOTECTL_INVITE", System.getenv("REMOTECTL_INVITE") ?: "")
    testLogging { showStandardStreams = true }
}
