import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
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
    versionCode.set(3)
    versionName.set("1.1.1")
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

// Online (Firebase) settings ship as assets/config/online.properties: the project ID
// (dedo.firebaseProjectId in gradle.properties, or DEDO_FIREBASE_PROJECT_ID), the Realtime Database
// of live duels (dedo.firebaseDatabaseUrl, or DEDO_FIREBASE_DATABASE_URL) and the project's public
// Web API key, which only comes from the environment (DEDO_FIREBASE_API_KEY) and is never committed.
// Without the key the build still works and online features say they're unavailable.
val onlineProjectId = providers.environmentVariable("DEDO_FIREBASE_PROJECT_ID")
    .orElse(providers.gradleProperty("dedo.firebaseProjectId")).orElse("")
val onlineDatabaseUrl = providers.environmentVariable("DEDO_FIREBASE_DATABASE_URL")
    .orElse(providers.gradleProperty("dedo.firebaseDatabaseUrl")).orElse("")
val onlineApiKey = providers.environmentVariable("DEDO_FIREBASE_API_KEY").orElse("")
val generateOnlineConfig = tasks.register("generateOnlineConfig") {
    group = "build"
    description = "Writes the Firebase project settings used by online play."
    val outDir = layout.buildDirectory.dir("generated/online-assets")
    inputs.property("projectId", onlineProjectId)
    inputs.property("databaseUrl", onlineDatabaseUrl)
    inputs.property("apiKeyDigest", onlineApiKey.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } })
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("config/online.properties").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "projectId=${onlineProjectId.get().trim()}\napiKey=${onlineApiKey.get().trim()}\ndatabaseUrl=${onlineDatabaseUrl.get().trim()}\n",
        )
        if (onlineProjectId.get().isBlank() || onlineApiKey.get().isBlank()) {
            logger.lifecycle("Online disabled in this build (DEDO_FIREBASE_API_KEY not set).")
        }
    }
}
androidApk.extraAssetDirs.from(generateOnlineConfig.map { layout.buildDirectory.dir("generated/online-assets").get() })

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
    // The versionCode the app should report (update tests are relative to it).
    systemProperty("dedo.versionCode", androidApk.versionCode.get())
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
    // Project of the opt-in live check (DEDO_LIVE_FIREBASE=1).
    systemProperty("dedo.firebaseProjectId", onlineProjectId.get())
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
tasks.named("check") { dependsOn(releaseSmokeTest) }

// ---- Release publishing -----------------------------------------------------------------------
// `./gradlew publishRelease` builds the release APK, copies it to ../release/dedo-nervoso.apk and
// writes ../release/version.json. Installed games read that manifest from the repository's
// default branch (UpdateChecker), so merging a release into `main` is what ships it to players.
val releaseDir: Directory = rootProject.layout.projectDirectory.dir("release")
val releaseBaseUrl = "https://raw.githubusercontent.com/guilhermedominguestriunfante/PriceRadar/main/dedonervoso/release"
tasks.register("publishRelease") {
    group = "distribution"
    description = "Builds the signed release APK and publishes it with version.json to release/."
    dependsOn("assembleRelease")
    val apkFile = layout.buildDirectory.file("outputs/apk/release/dedo-nervoso-release.apk")
    val notesFile = layout.projectDirectory.file("release-notes.json")
    val code = androidApk.versionCode
    val name = androidApk.versionName
    val appId = androidApk.applicationId
    // Online play requires the newest version unless a lower floor is given (-PminOnlineVersionCode=N).
    val minOnline = providers.gradleProperty("minOnlineVersionCode").map { it.toInt() }.orElse(code)
    // Releases are online builds; an offline one needs -PofflineRelease=true.
    val online = onlineProjectId.zip(onlineApiKey) { id, key -> id.isNotBlank() && key.isNotBlank() }
    val offlineAllowed = providers.gradleProperty("offlineRelease").map { it.toBoolean() }.orElse(false)
    val apksigner = provider { dedonervoso.gradle.AndroidSdk.locate(project).apksigner }
    inputs.file(apkFile)
    inputs.file(notesFile)
    inputs.property("versionCode", code)
    inputs.property("minOnlineVersionCode", minOnline)
    outputs.dir(releaseDir)
    doLast {
        if (!online.get() && !offlineAllowed.get()) {
            throw GradleException("Refusing to publish a release without online settings: set DEDO_FIREBASE_API_KEY (or pass -PofflineRelease=true).")
        }
        val apk = apkFile.get().asFile
        val verify = ProcessBuilder(apksigner.get().absolutePath, "verify", "--print-certs", apk.absolutePath)
            .redirectErrorStream(true).start()
        val certs = verify.inputStream.bufferedReader().readText()
        if (verify.waitFor() != 0) throw GradleException("apksigner could not verify $apk:\n$certs")
        if ("Android Debug" in certs) {
            throw GradleException("Refusing to publish a release signed with the debug key: set DEDO_KEYSTORE_B64 / DEDO_KEYSTORE_PASSWORD.")
        }
        val bytes = apk.readBytes()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        @Suppress("UNCHECKED_CAST")
        val notes = groovy.json.JsonSlurper().parse(notesFile.asFile) as Map<String, Any>
        val manifest = linkedMapOf(
            "schema" to 1,
            "app" to appId.get(),
            "versionCode" to code.get(),
            "versionName" to name.get(),
            "minOnlineVersionCode" to minOnline.get(),
            "apkUrl" to "$releaseBaseUrl/dedo-nervoso.apk",
            "size" to bytes.size,
            "sha256" to sha256,
            "publishedAt" to LocalDate.now(ZoneOffset.UTC).toString(),
            "notes" to notes,
        )
        val dir = releaseDir.asFile.apply { mkdirs() }
        apk.copyTo(File(dir, "dedo-nervoso.apk"), overwrite = true)
        File(dir, "version.json").writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(manifest)) + "\n")
        logger.lifecycle("Published ${name.get()} (${code.get()}) → ${dir.relativeTo(rootDir.parentFile)}; online requires ≥ ${minOnline.get()}.")
    }
}
