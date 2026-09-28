// Minimal, API-compatible stand-ins for the androidx.test classes Robolectric references.
// androidx.test is only published on Google Maven, which this project does not depend on;
// these shims let Robolectric run the real Android framework on the JVM for app tests.
plugins {
    `java-library`
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

dependencies {
    compileOnly(files(provider { dedonervoso.gradle.AndroidSdk.locate(project).platformJar(35) }))
}
