package dedonervoso.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File
import java.util.Properties

/**
 * Locates the Android SDK pieces used by the APK pipeline.
 *
 * SDK root resolution order: `sdk.dir` in `local.properties`, then `ANDROID_HOME`, then
 * `ANDROID_SDK_ROOT`. Build tools come from the newest `build-tools/<version>` directory that
 * contains `aapt2`; anything missing there is searched on the `PATH` (Debian/Ubuntu ship
 * `aapt2`, `dx`, `zipalign` and `apksigner` as regular system packages). On Windows the SDK's
 * tools carry an extension (`aapt2.exe`, `d8.bat`, `apksigner.bat`…), which is tried as well.
 */
class AndroidSdk(val root: File) {

    val buildToolsDir: File? by lazy {
        root.resolve("build-tools").listFiles()
            ?.filter { executable(it, "aapt2") != null }
            ?.maxWithOrNull(Comparator { a, b -> compareVersions(a.name, b.name) })
    }

    fun platformJar(api: Int): File {
        val jar = root.resolve("platforms/android-$api/android.jar")
        if (!jar.isFile) {
            throw GradleException(
                "Android platform $api not found at $jar. Install 'platforms;android-$api' " +
                    "or point sdk.dir (local.properties) / ANDROID_HOME to an SDK that has it."
            )
        }
        return jar
    }

    fun findTool(vararg names: String): File? {
        for (name in names) {
            buildToolsDir?.let { executable(it, name) }?.let { return it }
        }
        for (name in names) {
            which(name)?.let { return it }
        }
        return null
    }

    fun requireTool(vararg names: String): File = findTool(*names)
        ?: throw GradleException(
            "Android build tool '${names.first()}' not found in ${buildToolsDir ?: "$root/build-tools"} or on PATH."
        )

    val aapt2: File get() = requireTool("aapt2")
    val zipalign: File get() = requireTool("zipalign")
    val apksigner: File get() = requireTool("apksigner")

    /** Prefers d8 (modern SDKs); falls back to dx (Debian's `dalvik-exchange`). */
    val dexer: Dexer
        get() {
            findTool("d8")?.let { return Dexer(it, Dexer.Kind.D8) }
            findTool("dx", "dalvik-exchange")?.let { return Dexer(it, Dexer.Kind.DX) }
            throw GradleException("Neither d8 nor dx was found. Install Android build-tools.")
        }

    val adb: File?
        get() = executable(root.resolve("platform-tools"), "adb") ?: which("adb")

    data class Dexer(val executable: File, val kind: Kind) {
        enum class Kind { D8, DX }
    }

    companion object {
        fun locate(project: Project): AndroidSdk {
            val localProps = project.rootProject.file("local.properties")
            val fromLocal = if (localProps.isFile) {
                Properties().apply { localProps.inputStream().use { load(it) } }.getProperty("sdk.dir")
            } else {
                null
            }
            val candidates = listOfNotNull(
                fromLocal,
                System.getenv("ANDROID_HOME"),
                System.getenv("ANDROID_SDK_ROOT"),
            )
            val root = candidates.map(::File).firstOrNull { it.isDirectory }
                ?: throw GradleException(
                    "Android SDK not found. Set sdk.dir in local.properties or the ANDROID_HOME variable."
                )
            return AndroidSdk(root)
        }

        /** Extensions of executables: none on Unix; the Windows SDK ships `.exe` and `.bat` tools. */
        private val EXTENSIONS = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) listOf(".exe", ".bat", ".cmd", "") else listOf("")

        /** [name] in [dir] as an executable file (trying the platform's extensions), or null. */
        private fun executable(dir: File, name: String): File? =
            EXTENSIONS.map { File(dir, name + it) }.firstOrNull { it.isFile && it.canExecute() }

        private fun which(name: String): File? =
            System.getenv("PATH").orEmpty().split(File.pathSeparator)
                .firstNotNullOfOrNull { executable(File(it), name) }

        /** Numeric-aware comparison; non-numeric names (e.g. "debian") sort first. */
        private fun compareVersions(a: String, b: String): Int {
            val pa = a.split('.', '-').map { it.toIntOrNull() }
            val pb = b.split('.', '-').map { it.toIntOrNull() }
            val aNumeric = pa.firstOrNull() != null
            val bNumeric = pb.firstOrNull() != null
            if (aNumeric != bNumeric) return if (aNumeric) 1 else -1
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrNull(i) ?: 0
                val y = pb.getOrNull(i) ?: 0
                if (x != y) return x.compareTo(y)
            }
            return a.compareTo(b)
        }
    }
}
