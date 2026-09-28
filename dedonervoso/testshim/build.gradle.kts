// Minimal, API-compatible stand-ins for the androidx.test classes Robolectric references.
// androidx.test is only published on Google Maven, which this project does not depend on;
// these shims let Robolectric run the real Android framework on the JVM for app tests.
plugins {
    `java-library`
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

// Robolectric's annotations target Java 11; they're only compiled against here and read by the
// tests' JVM (21), so resolve the compile classpath for it.
configurations.named("compileClasspath") {
    attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
}

dependencies {
    compileOnly(files(provider { dedonervoso.gradle.AndroidSdk.locate(project).platformJar(35) }))
    // ShadowAtomicFile (a test-only shadow); the tests bring Robolectric itself.
    compileOnly("org.robolectric:annotations:4.17")
}
