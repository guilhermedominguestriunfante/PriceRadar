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
            id = "dedonervoso.android-apk"
            implementationClass = "dedonervoso.gradle.AndroidApkPlugin"
        }
    }
}
