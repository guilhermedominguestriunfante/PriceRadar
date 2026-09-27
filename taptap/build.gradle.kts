plugins {
    kotlin("jvm") apply false
}

tasks.register<Copy>("publishApks") {
    group = "distribution"
    description = "Builds debug and release APKs and copies them to apk/."
    dependsOn(":app:assembleDebug", ":app:assembleRelease")
    from(project(":app").layout.buildDirectory.dir("outputs/apk/debug"))
    from(project(":app").layout.buildDirectory.dir("outputs/apk/release"))
    include("*.apk")
    into(layout.projectDirectory.dir("apk"))
}
