import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
}

// The rules on random cases (src/test/.../fuzz) read these: a new seed, time, rule, case or trace is a new run, not an
// up-to-date one — else a case run again by its number passed without running.
tasks.withType<Test>().configureEach {
    listOf("FORMA_FUZZ_SEED", "FORMA_FUZZ_SECONDS", "FORMA_FUZZ_ONLY", "FORMA_FUZZ_CASE", "FORMA_FUZZ_TRACE").forEach { name ->
        inputs.property(name, providers.environmentVariable(name).orElse(""))
    }
}
