plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

gradlePlugin {
    plugins {
        register("androidApk") {
            id = "taptap.android-apk"
            implementationClass = "taptap.gradle.AndroidApkPlugin"
        }
    }
}
