package net.evrt.acrab.analysis.android

import kotlinx.serialization.encodeToString
import net.evrt.acrab.analysis.support.analysisJson
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BundledAndroidToolsTest {
    @Test
    fun `bundled classpaths select fixed main classes and the running JVM without shell quoting`() = bundle { root, _ ->
        val tools = BundledAndroidTools(root)
        val command = tools.command("d8")!!
        assertEquals(Path.of(System.getProperty("java.home"), "bin", if (windows()) "java.exe" else "java").toString(), command.first())
        assertEquals("com.android.tools.r8.D8", command.last())
        assertEquals(root.resolve("d8/lib/r8-test.jar").toString(), command[2])
        assertNull(tools.command("java"))
        assertNull(tools.locate("../smali"))
    }

    @Test
    fun `bundled dependencies are reverified immediately before a process is launched`() = bundle { root, _ ->
        val tools = BundledAndroidTools(root)
        Files.write(root.resolve("d8/lib/r8-test.jar"), byteArrayOf(99))
        assertFailsWith<IllegalArgumentException> { tools.command("d8") }
    }

    @Test
    fun `invalid hashes incomplete manifests and changed main classes are rejected`() = bundle { root, manifest ->
        fun reject(changed: AndroidToolchainManifest) {
            Files.writeString(root.resolve("toolchain.json"), analysisJson.encodeToString(changed))
            assertFailsWith<IllegalArgumentException> { BundledAndroidTools(root) }
        }
        reject(manifest.copy(sha256 = manifest.sha256 + ("d8/lib/r8-test.jar" to "0".repeat(64))))
        reject(manifest.copy(tools = manifest.tools - "d8"))
        reject(manifest.copy(tools = manifest.tools + ("d8" to manifest.tools.getValue("d8").copy(mainClass = "example.Untrusted"))))
    }

    @Test
    fun `manifest dependency paths cannot escape the distribution`() = bundle { root, manifest ->
        val changed = manifest.copy(tools = manifest.tools + ("d8" to manifest.tools.getValue("d8").copy(classpath = listOf("../r8.jar"))),
            sha256 = manifest.sha256 - "d8/lib/r8-test.jar" + ("../r8.jar" to "0".repeat(64)))
        Files.writeString(root.resolve("toolchain.json"), analysisJson.encodeToString(changed))
        assertFailsWith<IllegalArgumentException> { BundledAndroidTools(root) }
    }

    @Test
    fun `an explicitly configured missing external tool does not silently fall back to the bundle`() = bundle { root, _ ->
        val tools = BundledAndroidTools(root)
        val external = Files.createDirectory(root.resolve("external"))
        val overridden = AndroidCommandRunner(toolRoot = external, pathFallbackRoot = null, bundledTools = tools)
        assertNull(overridden.locate("smali"))
        assertNull(overridden.bundledRoot)
        val bundled = AndroidCommandRunner(toolRoot = null, pathFallbackRoot = null, bundledTools = tools)
        assertNotNull(bundled.locate("smali"))
        assertEquals(root, bundled.bundledRoot)
        assertTrue(bundled.locate("smali")!!.endsWith("smali-3.test.jar"))
    }

    private fun bundle(action: (Path, AndroidToolchainManifest) -> Unit) {
        val root = Files.createTempDirectory("acrab bundle with spaces ")
        try {
            val locations = mapOf("d2j-dex2jar" to "dex2jar/lib/dex-tools-test.jar", "smali" to "smali/lib/smali-3.test.jar",
                "baksmali" to "smali/lib/smali-baksmali-test.jar", "d8" to "d8/lib/r8-test.jar", "jasmin" to "jasmin/lib/jasmin-test.jar")
            val hashes = locations.values.associateWith { relative ->
                val path = root.resolve(relative)
                Files.createDirectories(path.parent)
                val bytes = relative.toByteArray()
                Files.write(path, bytes)
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            }
            val manifest = AndroidToolchainManifest(1, BundledAndroidTools.MAIN_CLASSES.mapValues { (id, main) ->
                BundledAndroidTool(main, "test", listOf(locations.getValue(id)))
            }, hashes)
            Files.writeString(root.resolve("toolchain.json"), analysisJson.encodeToString(manifest))
            action(root, manifest)
        } finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    private fun windows(): Boolean = System.getProperty("os.name").contains("Windows", true)
}
