import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Build-time tools (sound effect synthesis) that reuse the DSP code from :core.
plugins {
    kotlin("jvm")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    implementation(project(":core"))
}
