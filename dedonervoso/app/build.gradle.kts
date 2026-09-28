import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Android application: rendering, input, audio, haptics and storage on top of :core.
// Packaged into an APK by the dedonervoso.android-apk plugin (see buildSrc).
plugins {
    kotlin("jvm")
    id("dedonervoso.android-apk")
}

androidApk {
    applicationId.set("com.dedonervoso.app")
    compileSdk.set(35)
    // Link resources against API 34: Debian's aapt2 cannot read API 35's compact resource table.
    resourcesSdk.set(34)
    minSdk.set(26)
    targetSdk.set(35)
    versionCode.set(1)
    versionName.set("1.0.0")
    apkBaseName.set("dedo-nervoso")
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

// Sound effects are synthesised at build time (see core/audio/SfxBank.kt).
val sfxGenerator: Configuration by configurations.creating
val generateSfx = tasks.register<JavaExec>("generateSfx") {
    group = "build"
    description = "Renders the procedural sound effects to WAV assets."
    val outDir = layout.buildDirectory.dir("generated/assets/sfx")
    classpath = sfxGenerator
    mainClass.set("com.dedonervoso.tools.SfxGeneratorKt")
    inputs.files(sfxGenerator)
    outputs.dir(outDir)
    argumentProviders.add(CommandLineArgumentProvider { listOf(outDir.get().asFile.absolutePath) })
}
androidApk.extraAssetDirs.from(generateSfx.map { layout.buildDirectory.dir("generated/assets").get() })

// Robolectric resolves the Android framework jar offline from this configuration.
val robolectricRuntime: Configuration by configurations.creating {
    isTransitive = false
}

dependencies {
    implementation(project(":core"))
    sfxGenerator(project(":tools"))

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
    systemProperty("dedo.screenshots", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

// ---- Release smoke test ------------------------------------------------------------------------
// Runs the shipped bytecode — ProGuard's optimized + obfuscated release output, i.e. the dexer's
// input — under Robolectric, driving it only through public entry points (the activity class
// name, touches, the save file). Catches shrinking/obfuscation breakage the unit tests can't see.
val releaseSmoke: SourceSet = sourceSets.create("releaseSmoke")
val sdkPlatformJar = provider { dedonervoso.gradle.AndroidSdk.locate(project).platformJar(androidApk.compileSdk.get()) }
val releaseLink = tasks.named<dedonervoso.gradle.Aapt2LinkTask>("linkReleaseResources")
val releaseSmokeConfig = tasks.register<dedonervoso.gradle.RobolectricConfigTask>("releaseSmokeRobolectricConfig") {
    manifest.set(releaseLink.flatMap { it.manifest })
    resourcesApk.set(releaseLink.flatMap { it.resourcesApk })
    assets.from(tasks.named("mergeAssets"))
    packageName.set(androidApk.applicationId)
    outputDir.set(layout.buildDirectory.dir("generated/robolectric-release"))
}
releaseSmoke.resources.srcDir(releaseSmokeConfig.flatMap { it.outputDir })
listOf(releaseSmoke.compileClasspathConfigurationName, releaseSmoke.runtimeClasspathConfigurationName).forEach { name ->
    configurations.named(name) { attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
}
tasks.named<KotlinCompile>("compileReleaseSmokeKotlin") { compilerOptions.jvmTarget.set(JvmTarget.JVM_21) }
tasks.named<JavaCompile>("compileReleaseSmokeJava") {
    sourceCompatibility = "21"
    targetCompatibility = "21"
}
dependencies {
    "releaseSmokeImplementation"("junit:junit:4.13.2")
    "releaseSmokeImplementation"("org.robolectric:robolectric:4.17") {
        exclude(group = "androidx.test")
        exclude(group = "androidx.test.espresso")
    }
    "releaseSmokeImplementation"(project(":testshim"))
    "releaseSmokeCompileOnly"(files(sdkPlatformJar))
    "releaseSmokeRuntimeOnly"(files(sdkPlatformJar))
    // The app itself only as the release ProGuard output (no unobfuscated classes on this path).
    "releaseSmokeRuntimeOnly"(files(tasks.named<dedonervoso.gradle.ProguardTask>("proguardRelease").flatMap { it.outputJar }))
}
val releaseSmokeTest = tasks.register<Test>("releaseSmokeTest") {
    group = "verification"
    description = "Plays the optimized + obfuscated release bytecode end to end under Robolectric."
    testClassesDirs = releaseSmoke.output.classesDirs
    classpath = releaseSmoke.runtimeClasspath
    useJUnit()
    dependsOn(robolectricDeps)
    maxHeapSize = "3g"
    // ProGuard emits Java 7 bytecode without stack-map frames (-dontpreverify: dex has no use for
    // them); the JVM would reject it, so skip verification of classes loaded by the sandbox.
    jvmArgs("-XX:+UnlockDiagnosticVMOptions", "-XX:-BytecodeVerificationRemote")
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDeps.get().destinationDir.absolutePath)
    systemProperty("robolectric.logging.enabled", "false")
    systemProperty("dedo.screenshots", layout.buildDirectory.dir("screenshots-release").get().asFile.absolutePath)
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
tasks.named("check") { dependsOn(releaseSmokeTest) }
