import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin game logic (engine, rules, progression, persistence model).
// No Android dependency: everything here runs and is unit tested on the JVM.
plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        // Restrict JDK APIs to Java 8 so nothing unavailable on Android (API 26+) sneaks in.
        freeCompilerArgs.addAll("-Xjdk-release=1.8", "-Xlambdas=class", "-Xsam-conversions=class")
    }
}

dependencies {
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("calibrate", System.getProperty("calibrate") ?: "false")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
