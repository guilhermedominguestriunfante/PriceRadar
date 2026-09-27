import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
        // Compile against android.jar only (it provides the java.* API available on Android).
        noJdk.set(true)
        freeCompilerArgs.addAll("-Xlambdas=class", "-Xsam-conversions=class")
    }
}

dependencies {
    implementation(project(":core"))
}
