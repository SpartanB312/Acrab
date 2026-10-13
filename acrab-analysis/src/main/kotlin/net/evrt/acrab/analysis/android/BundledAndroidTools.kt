package net.evrt.acrab.analysis.android

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import net.evrt.acrab.analysis.support.analysisJson
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

@Serializable
internal data class AndroidToolchainManifest(
    val formatVersion: Int,
    val tools: Map<String, BundledAndroidTool>,
    val sha256: Map<String, String>,
)

@Serializable
internal data class BundledAndroidTool(val mainClass: String, val version: String, val classpath: List<String>)

internal class BundledAndroidTools(val root: Path) {
    private val realRoot: Path
    private val manifest: AndroidToolchainManifest

    init {
        require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "Bundled Android tool directory is missing or a symbolic link." }
        realRoot = root.toRealPath()
        val path = root.resolve("toolchain.json")
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) <= 1_048_576) { "Invalid bundled Android tool manifest." }
        manifest = analysisJson.decodeFromString(Files.readString(path))
        require(manifest.formatVersion == 1 && manifest.tools.keys == MAIN_CLASSES.keys) { "Unsupported or incomplete bundled Android tool manifest." }
        manifest.tools.forEach { (id, tool) ->
            require(tool.mainClass == MAIN_CLASSES.getValue(id) && tool.version.isNotBlank() && tool.classpath.isNotEmpty()) { "Invalid bundled tool: $id" }
            require(tool.classpath.distinct().size == tool.classpath.size && tool.classpath.all { it in manifest.sha256 }) { "Invalid bundled classpath: $id" }
        }
        require(manifest.sha256.keys == manifest.tools.values.flatMap { it.classpath }.toSet()) { "Unreferenced or missing bundled tool dependencies." }
        manifest.sha256.keys.forEach(::verifyJar)
    }

    fun locate(id: String): String? {
        val prefix = when (id) {
            "d2j-dex2jar" -> "dex-tools-"
            "smali" -> "smali-3."
            "baksmali" -> "smali-baksmali-"
            "d8" -> "r8-"
            "jasmin" -> "jasmin-"
            else -> return null
        }
        return manifest.tools[id]?.classpath?.firstOrNull { Path.of(it).fileName.toString().startsWith(prefix) }
            ?.let { root.resolve(it).toString() }
    }

    fun command(id: String): List<String>? {
        val tool = manifest.tools[id] ?: return null
        tool.classpath.forEach(::verifyJar)
        val java = Path.of(System.getProperty("java.home"), "bin", if (windows()) "java.exe" else "java")
        require(Files.isRegularFile(java) && Files.isExecutable(java)) { "The current JVM's Java executable is missing." }
        return listOf(java.toString(), "-cp", tool.classpath.joinToString(File.pathSeparator) { root.resolve(it).toString() }, tool.mainClass)
    }

    private fun verifyJar(relative: String) {
        require(relative.matches(Regex("(?:dex2jar|smali|d8|jasmin)/lib/[A-Za-z0-9._-]+\\.jar"))) { "Invalid bundled dependency path." }
        val path = root.resolve(relative)
        var component = root
        for (part in Path.of(relative)) {
            component = component.resolve(part)
            require(!Files.isSymbolicLink(component)) { "Bundled tool paths must not traverse symbolic links." }
        }
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && path.toRealPath().startsWith(realRoot)) { "Bundled dependency is missing or outside its root." }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual == manifest.sha256.getValue(relative)) { "Bundled Android dependency checksum mismatch: $relative" }
    }

    companion object {
        internal val MAIN_CLASSES = linkedMapOf(
            "d2j-dex2jar" to "com.googlecode.dex2jar.tools.Dex2jarCmd",
            "smali" to "com.android.tools.smali.smali.Main",
            "baksmali" to "com.android.tools.smali.baksmali.Main",
            "d8" to "com.android.tools.r8.D8",
            "jasmin" to "jasmin.Main",
        )

        fun discover(): BundledAndroidTools? {
            val location = Path.of(BundledAndroidTools::class.java.protectionDomain.codeSource.location.toURI())
            if (!Files.isRegularFile(location) || location.parent?.fileName?.toString() != "lib") return null
            val root = location.parent.parent.resolve("tools/android")
            return if (Files.exists(root, NOFOLLOW_LINKS)) BundledAndroidTools(root) else null
        }

        private fun windows(): Boolean = System.getProperty("os.name").contains("Windows", ignoreCase = true)
    }
}
