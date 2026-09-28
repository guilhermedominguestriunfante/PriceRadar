package dedonervoso.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** Compiles `res/` into a zip of aapt2 `.flat` files. */
@CacheableTask
abstract class Aapt2CompileTask : DefaultTask() {
    @get:Internal abstract val aapt2: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val resDir: DirectoryProperty
    @get:OutputFile abstract val compiledZip: RegularFileProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun compile() {
        val out = compiledZip.get().asFile
        out.delete()
        exec.exec {
            commandLine(aapt2.get().asFile, "compile", "--dir", resDir.get().asFile, "-o", out)
        }
    }
}

/** Links compiled resources, the manifest and assets into a resources APK (`.ap_`). */
@CacheableTask
abstract class Aapt2LinkTask : DefaultTask() {
    @get:Internal abstract val aapt2: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val compiledZip: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val manifest: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val androidJar: RegularFileProperty
    /** Merged assets root; may be absent when the app has no assets. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val assets: ConfigurableFileCollection
    @get:Input abstract val minSdk: Property<Int>
    @get:Input abstract val targetSdk: Property<Int>
    @get:Input abstract val versionCode: Property<Int>
    @get:Input abstract val versionName: Property<String>
    @get:Input abstract val debuggable: Property<Boolean>
    @get:OutputFile abstract val resourcesApk: RegularFileProperty
    @get:OutputFile abstract val proguardRules: RegularFileProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun link() {
        val args = mutableListOf<Any>(
            aapt2.get().asFile, "link",
            "-o", resourcesApk.get().asFile,
            "-I", androidJar.get().asFile,
            "--manifest", manifest.get().asFile,
            "--min-sdk-version", minSdk.get(),
            "--target-sdk-version", targetSdk.get(),
            "--version-code", versionCode.get(),
            "--version-name", versionName.get(),
            "--proguard", proguardRules.get().asFile,
            "--auto-add-overlay",
        )
        if (debuggable.get()) args += "--debug-mode"
        assets.files.firstOrNull { it.isDirectory }?.let { dir ->
            args.add("-A")
            args.add(dir)
        }
        args += compiledZip.get().asFile
        exec.exec { commandLine(args) }
    }
}

/**
 * Runs ProGuard over the program classes (app + libraries): shrinks, optionally optimizes and
 * obfuscates, and backports Java 8+ constructs (invokedynamic lambdas, string concatenation,
 * interface methods) so that any dexer — including the legacy dx — produces valid ART code.
 */
@CacheableTask
abstract class ProguardTask : DefaultTask() {
    @get:Classpath abstract val proguardClasspath: ConfigurableFileCollection
    @get:Classpath abstract val programClasspath: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val libraryJar: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val configFiles: ConfigurableFileCollection
    /** Baseline rules text; written next to the generated config at execution time. */
    @get:Input abstract val defaultRules: Property<String>
    @get:Input abstract val optimize: Property<Boolean>
    @get:Input abstract val obfuscate: Property<Boolean>
    @get:OutputFile abstract val outputJar: RegularFileProperty
    @get:OutputFile abstract val mappingFile: RegularFileProperty
    @get:OutputFile abstract val usageFile: RegularFileProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun run() {
        fun q(f: File) = "'" + f.absolutePath + "'"
        val cfg = File(temporaryDir, "proguard.pro")
        val out = outputJar.get().asFile
        out.delete()
        val lines = mutableListOf<String>()
        programClasspath.files.filter { it.exists() }.forEach { entry ->
            lines += "-injars ${q(entry)}(!META-INF/**,!**.kotlin_builtins,!**.kotlin_module)"
        }
        lines += "-outjars ${q(out)}"
        lines += "-libraryjars ${q(libraryJar.get().asFile)}"
        lines += "-printmapping ${q(mappingFile.get().asFile)}"
        lines += "-printusage ${q(usageFile.get().asFile)}"
        lines += "-android"
        lines += "-target 1.7"
        lines += "-dontpreverify"
        if (optimize.get()) {
            lines += "-optimizationpasses 3"
            lines += "-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*,!code/allocation/variable"
        } else {
            lines += "-dontoptimize"
        }
        if (!obfuscate.get()) lines += "-dontobfuscate"
        val defaults = File(temporaryDir, "proguard-android.pro")
        defaults.writeText(defaultRules.get())
        lines += "-include ${q(defaults)}"
        configFiles.files.forEach { f ->
            if (!f.isFile) throw GradleException("ProGuard config file not found: $f")
            lines += "-include ${q(f)}"
        }
        cfg.writeText(lines.joinToString("\n", postfix = "\n"))

        exec.javaexec {
            classpath(proguardClasspath)
            mainClass.set("proguard.ProGuard")
            args("@" + cfg.absolutePath)
        }
        if (!out.isFile) throw GradleException("ProGuard did not produce $out")
    }
}

/** Converts JVM bytecode to DEX using d8 when available, dx otherwise. */
@CacheableTask
abstract class DexTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val inputJar: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val libraryJar: RegularFileProperty
    @get:Input abstract val minSdk: Property<Int>
    @get:Input abstract val debuggable: Property<Boolean>
    @get:Internal abstract val dexerExecutable: RegularFileProperty
    @get:Input abstract val dexerKind: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun dex() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val tool = dexerExecutable.get().asFile
        val input = inputJar.get().asFile
        when (AndroidSdk.Dexer.Kind.valueOf(dexerKind.get())) {
            AndroidSdk.Dexer.Kind.D8 -> exec.exec {
                commandLine(
                    tool, if (debuggable.get()) "--debug" else "--release",
                    "--min-api", minSdk.get(),
                    "--lib", libraryJar.get().asFile,
                    "--output", out, input,
                )
            }
            AndroidSdk.Dexer.Kind.DX -> exec.exec {
                val args = mutableListOf<Any>(tool, "--dex", "--multi-dex", "--min-sdk-version=${minSdk.get()}")
                if (!debuggable.get()) args += "--no-locals"
                args += "--output=${out.absolutePath}"
                args += input
                commandLine(args)
            }
        }
        if (out.listFiles { f -> f.name.endsWith(".dex") }.isNullOrEmpty()) {
            throw GradleException("Dexer produced no .dex files in $out")
        }
    }
}

/**
 * Assembles the unsigned APK from the linked resources and the dex files. Media, fonts that are
 * streamed and `resources.arsc` are stored uncompressed (required by SoundPool file descriptors
 * and by Android 11+ for `resources.arsc`). Entry names always use `/`: the Windows aapt2 stores
 * asset subfolders as `assets/sfx\tap1.wav`, which Android's AssetManager would never find.
 */
@CacheableTask
abstract class PackageApkTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val resourcesApk: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val dexDir: DirectoryProperty
    @get:OutputFile abstract val unsignedApk: RegularFileProperty

    @TaskAction
    fun pack() {
        val out = unsignedApk.get().asFile
        out.parentFile.mkdirs()
        val seen = HashSet<String>()
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            zos.setLevel(9)
            ZipFile(resourcesApk.get().asFile).use { zf ->
                val entries = zf.entries().toList().sortedBy { if (it.name == "AndroidManifest.xml") 0 else 1 }
                for (e in entries) {
                    val name = e.name.replace('\\', '/')
                    if (e.isDirectory || !seen.add(name)) continue
                    val bytes = zf.getInputStream(e).use { it.readBytes() }
                    writeEntry(zos, name, bytes)
                }
            }
            dexDir.get().asFile.listFiles { f -> f.name.endsWith(".dex") }!!
                .sortedBy { dexOrder(it.name) }
                .forEach { dex ->
                    if (seen.add(dex.name)) writeEntry(zos, dex.name, dex.readBytes())
                }
        }
    }

    private fun dexOrder(name: String): Int =
        if (name == "classes.dex") 1 else name.removePrefix("classes").removeSuffix(".dex").toIntOrNull() ?: Int.MAX_VALUE

    private fun writeEntry(zos: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.time = FIXED_TIMESTAMP
        if (shouldStore(name)) {
            val crc = CRC32().apply { update(bytes) }
            entry.method = ZipEntry.STORED
            entry.size = bytes.size.toLong()
            entry.compressedSize = bytes.size.toLong()
            entry.crc = crc.value
        } else {
            entry.method = ZipEntry.DEFLATED
        }
        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun shouldStore(name: String): Boolean {
        if (name == "resources.arsc") return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in NO_COMPRESS
    }

    companion object {
        private const val FIXED_TIMESTAMP = 315_532_800_000L // 1980-01-01, keeps builds reproducible
        private val NO_COMPRESS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "wav", "mp2", "mp3", "ogg", "aac", "mpg", "mpeg",
            "mid", "midi", "smf", "jet", "rtttl", "imy", "xmf", "mp4", "m4a", "m4v", "3gp", "3gpp",
            "3g2", "3gpp2", "amr", "awb", "wma", "wmv", "webm", "mkv",
        )
    }
}

/** Aligns uncompressed entries to 4-byte boundaries (and .so files to pages). */
@CacheableTask
abstract class ZipAlignTask : DefaultTask() {
    @get:Internal abstract val zipalign: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val inputApk: RegularFileProperty
    @get:OutputFile abstract val alignedApk: RegularFileProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun align() {
        val out = alignedApk.get().asFile
        out.delete()
        exec.exec { commandLine(zipalign.get().asFile, "-p", "-f", "4", inputApk.get().asFile, out) }
        exec.exec { commandLine(zipalign.get().asFile, "-c", "-p", "4", out) }
    }
}

/** Signs with apksigner (v1/v2/v3 as appropriate for minSdk) and verifies the result. */
abstract class SignApkTask : DefaultTask() {
    @get:Internal abstract val apksigner: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val inputApk: RegularFileProperty
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val keystore: RegularFileProperty
    /** Keystore content (base64) when it comes from an environment secret instead of a file. */
    @get:Internal abstract val keystoreBase64: Property<String>
    /** Fingerprint of [keystoreBase64] so a key change re-signs; the secret itself is never an input. */
    @get:Optional @get:Input val keystoreDigest: Provider<String> = keystoreBase64.map { sha256Hex(it.toByteArray()) }
    @get:Internal abstract val storePassword: Property<String>
    @get:Input abstract val keyAlias: Property<String>
    @get:Internal abstract val keyPassword: Property<String>
    @get:Input abstract val minSdk: Property<Int>
    @get:OutputFile abstract val signedApk: RegularFileProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun sign() {
        val out = signedApk.get().asFile
        out.delete()
        val ks = if (keystore.isPresent) {
            keystore.get().asFile
        } else {
            // Decoded into the task's private temp dir only for the signing call, then deleted.
            File(temporaryDir, "release.keystore").also { f ->
                f.writeBytes(java.util.Base64.getDecoder().decode(keystoreBase64.get()))
                f.setReadable(false, false)
                f.setReadable(true, true)
            }
        }
        try {
            exec.exec {
                environment("DEDO_KS_PASS", storePassword.get())
                environment("DEDO_KEY_PASS", keyPassword.get())
                commandLine(
                    apksigner.get().asFile, "sign",
                    "--ks", ks,
                    "--ks-pass", "env:DEDO_KS_PASS",
                    "--key-pass", "env:DEDO_KEY_PASS",
                    "--ks-key-alias", keyAlias.get(),
                    "--min-sdk-version", minSdk.get(),
                    "--out", out,
                    inputApk.get().asFile,
                )
            }
        } finally {
            if (!keystore.isPresent) ks.delete()
        }
        val verifyOutput = ByteArrayOutputStream()
        exec.exec {
            commandLine(apksigner.get().asFile, "verify", "--verbose", out)
            standardOutput = verifyOutput
        }
        logger.lifecycle("Signed ${out.name}:\n" + verifyOutput.toString().lines().filter { it.startsWith("Verif") }.joinToString("\n"))
    }
}

/** Creates a debug keystore compatible with Android Studio's (~/.android/debug.keystore) if missing. */
abstract class DebugKeystoreTask : DefaultTask() {
    @get:Internal abstract val keystore: RegularFileProperty
    @get:Inject abstract val exec: ExecOperations

    init {
        outputs.upToDateWhen { keystore.get().asFile.isFile }
    }

    @TaskAction
    fun create() {
        val ks = keystore.get().asFile
        if (ks.isFile) return
        ks.parentFile.mkdirs()
        val keytool = File(System.getProperty("java.home"), "bin/keytool")
        exec.exec {
            commandLine(
                keytool, "-genkeypair", "-keystore", ks, "-storepass", "android", "-alias", "androiddebugkey",
                "-keypass", "android", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10950",
                "-dname", "CN=Android Debug,O=Android,C=US",
            )
        }
    }
}

/**
 * Produces the final manifest: injects `android:versionCode`/`android:versionName` and a
 * `<uses-sdk>` element (like AGP's manifest merger does) so every consumer — aapt2 and
 * Robolectric — sees the same values.
 */
@CacheableTask
abstract class ProcessManifestTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val sourceManifest: RegularFileProperty
    @get:Input abstract val minSdk: Property<Int>
    @get:Input abstract val targetSdk: Property<Int>
    @get:Input abstract val versionCode: Property<Int>
    @get:Input abstract val versionName: Property<String>
    @get:OutputFile abstract val outputManifest: RegularFileProperty

    @TaskAction
    fun process() {
        var xml = sourceManifest.get().asFile.readText()
        val start = xml.indexOf("<manifest")
        if (start < 0) throw GradleException("No <manifest> element in ${sourceManifest.get().asFile}")
        val tagEnd = xml.indexOf('>', start)
        var tag = xml.substring(start, tagEnd)
        if (!tag.contains("android:versionCode")) tag += "\n    android:versionCode=\"${versionCode.get()}\""
        if (!tag.contains("android:versionName")) tag += "\n    android:versionName=\"${versionName.get()}\""
        xml = xml.substring(0, start) + tag + xml.substring(tagEnd)
        if (!xml.contains("<uses-sdk")) {
            val insertAt = xml.indexOf('>', xml.indexOf("<manifest")) + 1
            val usesSdk = "\n\n    <uses-sdk android:minSdkVersion=\"${minSdk.get()}\" " +
                "android:targetSdkVersion=\"${targetSdk.get()}\" />"
            xml = xml.substring(0, insertAt) + usesSdk + xml.substring(insertAt)
        }
        outputManifest.get().asFile.apply { parentFile.mkdirs() }.writeText(xml)
    }
}

/** Writes `com/android/tools/test_config.properties`, the file Robolectric uses to find app resources. */
abstract class RobolectricConfigTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val manifest: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val resourcesApk: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val assets: ConfigurableFileCollection
    @get:Input abstract val packageName: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val file = outputDir.get().asFile.resolve("com/android/tools/test_config.properties")
        file.parentFile.mkdirs()
        val assetsDir = assets.files.firstOrNull() ?: File(temporaryDir, "no-assets").apply { mkdirs() }
        // A content digest (a real key: Gradle ignores comments when fingerprinting .properties)
        // makes this test input change with resources and assets, so tests re-run.
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(manifest.get().asFile.readBytes())
        md.update(resourcesApk.get().asFile.readBytes())
        for (f in assets.asFileTree.files.sortedBy { it.path }) {
            md.update(f.path.toByteArray())
            md.update(f.readBytes())
        }
        val digest = md.digest().joinToString("") { "%02x".format(it) }
        // Forward slashes: a backslash is an escape in .properties (Windows paths would be mangled).
        file.writeText(
            """
            dedo_inputs_sha256=$digest
            android_merged_manifest=${manifest.get().asFile.absoluteFile.invariantSeparatorsPath}
            android_merged_assets=${assetsDir.absoluteFile.invariantSeparatorsPath}
            android_resource_apk=${resourcesApk.get().asFile.absoluteFile.invariantSeparatorsPath}
            android_custom_package=${packageName.get()}
            """.trimIndent() + "\n"
        )
    }
}

internal fun sha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
