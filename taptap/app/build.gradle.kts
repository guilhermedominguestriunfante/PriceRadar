import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Android application: rendering, input, audio, haptics and storage on top of :core.
// Packaged into an APK by the taptap.android-apk plugin (see buildSrc).
plugins {
    kotlin("jvm")
    id("taptap.android-apk")
}

androidApk {
    applicationId.set("com.taptap.game")
    compileSdk.set(35)
    // Link resources against API 34: Debian's aapt2 cannot read API 35's compact resource table.
    resourcesSdk.set(34)
    minSdk.set(26)
    targetSdk.set(35)
    versionCode.set(1)
    versionName.set("1.0.0")
    apkBaseName.set("taptap")
    proguardFiles.from("proguard-rules.pro")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.addAll("-Xlambdas=class", "-Xsam-conversions=class")
    }
}

// Production code compiles against android.jar only (it provides the java.* API available on
// Android); tests run on the JVM under Robolectric and keep the JDK.
tasks.named<KotlinCompile>("compileKotlin") {
    compilerOptions.noJdk.set(true)
}

// Tests (Robolectric) run on the build JDK: resolve and compile them for JVM 21.
listOf("testCompileClasspath", "testRuntimeClasspath").forEach { name ->
    configurations.named(name) {
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}
tasks.named<KotlinCompile>("compileTestKotlin") {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}
tasks.named<JavaCompile>("compileTestJava") {
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

// Robolectric resolves the Android framework jar offline from this configuration.
val robolectricRuntime: Configuration by configurations.creating {
    isTransitive = false
}

dependencies {
    implementation(project(":core"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17") {
        // Published on Google Maven only; replaced by :testshim.
        exclude(group = "androidx.test")
        exclude(group = "androidx.test.espresso")
    }
    testImplementation(project(":testshim"))
    robolectricRuntime("org.robolectric:android-all-instrumented:15-robolectric-13954326-i7")
}

val robolectricDeps = tasks.register<Sync>("robolectricDeps") {
    from(robolectricRuntime)
    into(layout.buildDirectory.dir("robolectric-deps"))
}

tasks.test {
    useJUnit()
    dependsOn(robolectricDeps)
    maxHeapSize = "3g"
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDeps.get().destinationDir.absolutePath)
    systemProperty("robolectric.logging.enabled", "false")
    // Screenshots of rendered screens are written here by UI tests.
    systemProperty("taptap.screenshots", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}
