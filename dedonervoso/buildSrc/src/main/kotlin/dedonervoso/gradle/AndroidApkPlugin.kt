package dedonervoso.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.Sync
import java.io.File
import java.util.Properties

/** Configuration for [AndroidApkPlugin]. */
abstract class AndroidApkExtension {
    abstract val applicationId: Property<String>
    abstract val compileSdk: Property<Int>
    /**
     * Platform whose resource table aapt2 links against. Defaults to [compileSdk]; older aapt2
     * builds (e.g. Debian's) cannot parse the compact resource tables introduced in API 35, so
     * a lower platform can be used here — framework resource IDs are stable across versions.
     * Falls back to [compileSdk] when that platform is not installed.
     */
    abstract val resourcesSdk: Property<Int>
    abstract val minSdk: Property<Int>
    abstract val targetSdk: Property<Int>
    abstract val versionCode: Property<Int>
    abstract val versionName: Property<String>
    /** Output file names are `<apkBaseName>-debug.apk` / `<apkBaseName>-release.apk`. */
    abstract val apkBaseName: Property<String>
    /** Extra asset roots (generated assets) merged with `src/main/assets`. */
    abstract val extraAssetDirs: ConfigurableFileCollection
    /** App specific ProGuard rules (appended to the built-in Android rules). */
    abstract val proguardFiles: ConfigurableFileCollection
}

/**
 * Builds a signed Android APK without the Android Gradle Plugin, using the SDK's command line
 * tools directly:
 *
 * ```
 * res/ --aapt2 compile--> flat.zip --aapt2 link (+manifest, assets)--> resources.ap_
 * Kotlin classes + libs --ProGuard (shrink, backport)--> classes.jar --d8/dx--> classes.dex
 * resources.ap_ + dex --package--> unsigned.apk --zipalign--> aligned.apk --apksigner--> app.apk
 * ```
 *
 * Tasks per variant: `assembleDebug`, `assembleRelease`, `installDebug`, `installRelease`.
 */
class AndroidApkPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create("androidApk", AndroidApkExtension::class.java)
        ext.compileSdk.convention(35)
        ext.resourcesSdk.convention(ext.compileSdk)
        ext.minSdk.convention(26)
        ext.targetSdk.convention(35)
        ext.versionCode.convention(1)
        ext.versionName.convention("1.0.0")
        ext.apkBaseName.convention(project.name)

        val sdk: AndroidSdk by lazy { AndroidSdk.locate(project) }
        val androidJar = project.provider { sdk.platformJar(ext.compileSdk.get()) }
        val resourcesJar = project.provider {
            val wanted = sdk.root.resolve("platforms/android-${ext.resourcesSdk.get()}/android.jar")
            if (wanted.isFile) wanted else sdk.platformJar(ext.compileSdk.get())
        }

        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            val jar = project.files(androidJar)
            project.dependencies.add("compileOnly", jar)
            project.dependencies.add("testCompileOnly", jar)
            // Robolectric's own (non-sandboxed) classes reference android.* types; AGP puts a
            // "mockable" android.jar on this classpath for the same reason.
            project.dependencies.add("testRuntimeOnly", jar)
        }

        val proguardConfig = project.configurations.create("proguard") {
            isCanBeConsumed = false
            isVisible = false
        }
        project.dependencies.add("proguard", "com.guardsquare:proguard-base:7.10.0")

        val build = project.layout.buildDirectory
        val mainDir = project.file("src/main")

        val mergeAssets = project.tasks.register("mergeAssets", Sync::class.java) {
            group = "build"
            description = "Merges src/main/assets with generated assets."
            from(File(mainDir, "assets"))
            from(ext.extraAssetDirs)
            into(build.dir("intermediates/assets"))
        }

        val compileResources = project.tasks.register("compileResources", Aapt2CompileTask::class.java) {
            group = "build"
            aapt2.fileProvider(project.provider { sdk.aapt2 })
            resDir.set(File(mainDir, "res"))
            compiledZip.set(build.file("intermediates/res/compiled.flat.zip"))
        }

        val processManifest = project.tasks.register("processManifest", ProcessManifestTask::class.java) {
            group = "build"
            sourceManifest.set(File(mainDir, "AndroidManifest.xml"))
            minSdk.set(ext.minSdk)
            targetSdk.set(ext.targetSdk)
            versionCode.set(ext.versionCode)
            versionName.set(ext.versionName)
            outputManifest.set(build.file("intermediates/manifest/AndroidManifest.xml"))
        }

        val debugKeystore = project.tasks.register("debugKeystore", DebugKeystoreTask::class.java) {
            keystore.set(File(System.getProperty("user.home"), ".android/debug.keystore"))
        }

        for (variant in listOf("debug", "release")) {
            val cap = variant.replaceFirstChar { it.uppercase() }
            val debuggable = variant == "debug"

            val link = project.tasks.register("link${cap}Resources", Aapt2LinkTask::class.java) {
                group = "build"
                aapt2.fileProvider(project.provider { sdk.aapt2 })
                compiledZip.set(compileResources.flatMap { it.compiledZip })
                manifest.set(processManifest.flatMap { it.outputManifest })
                this.androidJar.fileProvider(resourcesJar)
                assets.from(mergeAssets)
                minSdk.set(ext.minSdk)
                targetSdk.set(ext.targetSdk)
                versionCode.set(ext.versionCode)
                versionName.set(ext.versionName)
                this.debuggable.set(debuggable)
                resourcesApk.set(build.file("intermediates/$variant/resources.ap_"))
                proguardRules.set(build.file("intermediates/$variant/aapt_rules.pro"))
            }

            val proguard = project.tasks.register("proguard$cap", ProguardTask::class.java) {
                group = "build"
                val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
                val main = sourceSets.getByName("main")
                proguardClasspath.from(proguardConfig)
                programClasspath.from(main.runtimeClasspath)
                libraryJar.fileProvider(androidJar)
                defaultRules.set(DEFAULT_PROGUARD_RULES)
                configFiles.from(link.flatMap { it.proguardRules }, ext.proguardFiles)
                optimize.set(!debuggable)
                obfuscate.set(!debuggable)
                outputJar.set(build.file("intermediates/$variant/classes.jar"))
                mappingFile.set(build.file("outputs/mapping/$variant/mapping.txt"))
                usageFile.set(build.file("outputs/mapping/$variant/usage.txt"))
            }

            val dex = project.tasks.register("dex$cap", DexTask::class.java) {
                group = "build"
                inputJar.set(proguard.flatMap { it.outputJar })
                libraryJar.fileProvider(androidJar)
                minSdk.set(ext.minSdk)
                this.debuggable.set(debuggable)
                dexerExecutable.fileProvider(project.provider { sdk.dexer.executable })
                dexerKind.set(project.provider { sdk.dexer.kind.name })
                outputDir.set(build.dir("intermediates/$variant/dex"))
            }

            val pack = project.tasks.register("package$cap", PackageApkTask::class.java) {
                group = "build"
                resourcesApk.set(link.flatMap { it.resourcesApk })
                dexDir.set(dex.flatMap { it.outputDir })
                unsignedApk.set(build.file("intermediates/$variant/unsigned.apk"))
            }

            val align = project.tasks.register("zipalign$cap", ZipAlignTask::class.java) {
                group = "build"
                zipalign.fileProvider(project.provider { sdk.zipalign })
                inputApk.set(pack.flatMap { it.unsignedApk })
                alignedApk.set(build.file("intermediates/$variant/aligned.apk"))
            }

            val sign = project.tasks.register("sign$cap", SignApkTask::class.java) {
                group = "build"
                apksigner.fileProvider(project.provider { sdk.apksigner })
                inputApk.set(align.flatMap { it.alignedApk })
                minSdk.set(ext.minSdk)
                signedApk.set(ext.apkBaseName.flatMap { build.file("outputs/apk/$variant/$it-$variant.apk") })
                val release = if (debuggable) null else releaseSigning(project)
                if (release == null) {
                    dependsOn(debugKeystore)
                    keystore.set(debugKeystore.flatMap { it.keystore })
                    storePassword.set("android")
                    keyAlias.set("androiddebugkey")
                    keyPassword.set("android")
                    if (!debuggable) {
                        doFirst {
                            logger.warn(
                                "WARNING: no release signing config (keystore.properties or DEDO_KEYSTORE* env). " +
                                    "The release APK is signed with the debug key."
                            )
                        }
                    }
                } else {
                    if (release.storeFile != null) keystore.set(release.storeFile) else keystoreBase64.set(release.storeBase64)
                    storePassword.set(release.storePassword)
                    keyAlias.set(release.keyAlias)
                    keyPassword.set(release.keyPassword)
                }
            }

            project.tasks.register("assemble$cap") {
                group = "build"
                description = "Builds the signed $variant APK."
                dependsOn(sign)
                doLast {
                    val apk = sign.get().signedApk.get().asFile
                    logger.lifecycle("APK ($variant): ${apk.absolutePath} (${apk.length() / 1024} KiB)")
                }
            }

            project.tasks.register("install$cap", Exec::class.java) {
                group = "install"
                description = "Installs the $variant APK on the connected device (adb install -r)."
                dependsOn(sign)
                doFirst {
                    val adb = sdk.adb ?: throw GradleException("adb not found (install platform-tools).")
                    commandLine(adb, "install", "-r", sign.get().signedApk.get().asFile)
                }
            }
        }

        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            configureRobolectric(project, ext, mergeAssets)
        }
    }

    private fun configureRobolectric(project: Project, ext: AndroidApkExtension, mergeAssets: Any) {
        val build = project.layout.buildDirectory
        val link = project.tasks.named("linkDebugResources", Aapt2LinkTask::class.java)
        val config = project.tasks.register("robolectricConfig", RobolectricConfigTask::class.java) {
            manifest.set(link.flatMap { it.manifest })
            resourcesApk.set(link.flatMap { it.resourcesApk })
            assets.from(mergeAssets)
            packageName.set(ext.applicationId)
            outputDir.set(build.dir("generated/robolectric"))
        }
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        sourceSets.getByName("test").resources.srcDir(config.flatMap { it.outputDir })
    }

    private class ReleaseSigning(
        val storeFile: File?,
        val storeBase64: String?,
        val storePassword: String,
        val keyAlias: String,
        val keyPassword: String,
    )

    /**
     * Release key, in order: `keystore.properties` (root project); `DEDO_KEYSTORE_B64` (the
     * keystore itself, base64 — suits CI and cloud environment secrets); `DEDO_KEYSTORE` (a path).
     * Passwords come from `DEDO_KEYSTORE_PASSWORD` / `DEDO_KEY_PASSWORD`; the alias from
     * `DEDO_KEY_ALIAS` (default "dedonervoso"). Nothing here is ever written into the project.
     */
    private fun releaseSigning(project: Project): ReleaseSigning? {
        val propsFile = project.rootProject.file("keystore.properties")
        if (propsFile.isFile) {
            val p = Properties().apply { propsFile.inputStream().use { load(it) } }
            return ReleaseSigning(
                project.rootProject.file(p.getProperty("storeFile")),
                null,
                p.getProperty("storePassword"),
                p.getProperty("keyAlias"),
                p.getProperty("keyPassword", p.getProperty("storePassword")),
            )
        }
        val password = System.getenv("DEDO_KEYSTORE_PASSWORD")?.takeIf { it.isNotEmpty() } ?: return null
        val alias = System.getenv("DEDO_KEY_ALIAS")?.takeIf { it.isNotEmpty() } ?: "dedonervoso"
        val keyPassword = System.getenv("DEDO_KEY_PASSWORD")?.takeIf { it.isNotEmpty() } ?: password
        val base64 = System.getenv("DEDO_KEYSTORE_B64")?.filterNot { it.isWhitespace() }?.takeIf { it.isNotEmpty() }
        if (base64 != null) return ReleaseSigning(null, base64, password, alias, keyPassword)
        val path = System.getenv("DEDO_KEYSTORE")?.takeIf { it.isNotEmpty() } ?: return null
        return ReleaseSigning(File(path), null, password, alias, keyPassword)
    }

    private companion object {
        /** Baseline rules equivalent to AGP's proguard-android-optimize.txt (written by the task, so `clean` can't drop them). */
        val DEFAULT_PROGUARD_RULES = """
            -dontusemixedcaseclassnames
            -keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
            -renamesourcefileattribute SourceFile
            -keep public class * extends android.app.Activity
            -keep public class * extends android.app.Application
            -keep public class * extends android.app.Service
            -keep public class * extends android.content.BroadcastReceiver
            -keep public class * extends android.content.ContentProvider
            -keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
            -keepclassmembers enum * {
                public static **[] values();
                public static ** valueOf(java.lang.String);
            }
            -keepclassmembers class * implements android.os.Parcelable {
                public static final ** CREATOR;
            }
            -keepclassmembers public class * extends android.view.View {
                void set*(***);
                *** get*();
            }
            -dontwarn kotlin.**
            -dontwarn org.jetbrains.annotations.**
            -dontwarn org.intellij.lang.annotations.**
            -dontnote kotlin.**
        """.trimIndent() + "\n"
    }
}
