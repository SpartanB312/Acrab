package net.evrt.acrab.analysis.android

import com.android.aapt.Resources
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.evrt.acrab.analysis.index.JvmIndexService
import net.evrt.acrab.analysis.index.ProjectRepository
import net.evrt.acrab.analysis.support.analysisJson
import net.evrt.acrab.core.AcrabConfig
import net.evrt.acrab.core.AcrabContext
import net.evrt.acrab.core.ToolInputException
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes as AsmOpcodes
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AndroidIntegrationTest {
    @Test
    fun `raw DEX preserves original entry name descriptors and code-unit coordinates`() = workspace { context, projects ->
        val source = projects.createFromBytes("sample.dex", dex())
        val summary = call(context, "android_dex_summary", "projectId" to source.projectId)
        assertEquals("sample.dex", summary["dexFiles"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(2, summary["methodCount"]!!.jsonPrimitive.content.toInt())
        val method = call(context, "android_method_bytecode", "projectId" to source.projectId,
            "className" to "example.Android", "methodName" to "run", "descriptor" to "()V", "limit" to 2)
        assertEquals(source.sha256, method["inputSha256"]!!.jsonPrimitive.content)
        assertTrue(method["truncated"]!!.jsonPrimitive.content.toBoolean())
        val instructions = method["instructions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(0, 1), instructions.map { it["codeOffset"]!!.jsonPrimitive.content.toInt() })
        assertEquals(listOf(1, 2), instructions.map { it["codeUnits"]!!.jsonPrimitive.content.toInt() })
        assertEquals("sample-text", instructions.last()["reference"]!!.jsonPrimitive.content)
    }

    @Test
    fun `overloads and duplicate multidex classes must not silently select the first match`() = workspace { context, projects ->
        val source = projects.createFromBytes("sample.apk", zip("classes.dex" to dex(), "classes2.dex" to dex()))
        val args = arrayOf("projectId" to source.projectId, "className" to "example.Android", "methodName" to "run")
        assertFailsWith<ToolInputException> { call(context, "android_method_bytecode", *args) }
        assertFailsWith<ToolInputException> { call(context, "android_method_bytecode", *args, "descriptor" to "()V") }
        assertFailsWith<ToolInputException> { call(context, "android_class_summary", "projectId" to source.projectId, "className" to "example.Android") }
        val selected = call(context, "android_method_bytecode", *args, "descriptor" to "()V", "dexFile" to "classes2.dex")
        assertEquals("classes2.dex", selected["dexFile"]!!.jsonPrimitive.content)
    }

    @Test
    fun `APK and nested AAB inventories retain every DEX source`() = workspace { context, projects ->
        for ((name, entries) in listOf("sample.apk" to listOf("classes.dex", "classes2.dex"),
            "sample.aab" to listOf("base/dex/classes.dex", "feature/dex/classes2.dex"))) {
            val source = projects.createFromBytes(name, zip(*entries.map { it to dex() }.toTypedArray()))
            val summary = call(context, "android_dex_summary", "projectId" to source.projectId)
            assertEquals(entries, summary["dexFiles"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        }
    }

    @Test
    fun `Android tools reject changed stored source bytes and JVM tools reject DEX projects`() = workspace { context, projects ->
        val source = projects.createFromBytes("sample.dex", dex())
        assertFailsWith<IllegalArgumentException> { JvmIndexService(projects).ensureIndex(source.projectId) }
        Files.write(Path.of(source.archivePath), byteArrayOf(1, 2, 3))
        assertFailsWith<ToolInputException> { call(context, "android_dex_summary", "projectId" to source.projectId) }
        assertFailsWith<ToolInputException> { call(context, "android_dex_recompute_checksum", "projectId" to source.projectId) }
    }

    @Test
    fun `plain manifest inspection requires no DEX or converter and rejects external entities`() = workspace { context, projects ->
        val source = projects.createFromBytes("AndroidManifest.xml", """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="example.app"><application android:debuggable="true"/></manifest>""".toByteArray())
        val summary = call(context, "android_manifest_summary", "projectId" to source.projectId)
        assertEquals("example.app", summary["packageName"]!!.jsonPrimitive.content)
        assertTrue(summary["application"]!!.jsonObject["debuggable"]!!.jsonPrimitive.content.toBoolean())
        val xxe = projects.createFromBytes("bad.xml", """<!DOCTYPE manifest [<!ENTITY x SYSTEM "file:///must-not-read">]><manifest package="&x;"/>""".toByteArray())
        val rejected = call(context, "android_manifest_summary", "projectId" to xxe.projectId)
        assertEquals("plain-xml-error", rejected["parser"]!!.jsonPrimitive.content)
    }

    @Test
    fun `binary AXML is parsed while raw output retains Base64 source bytes`() = workspace { context, projects ->
        val bytes = binaryManifest()
        val source = projects.createFromBytes("sample.apk", zip("AndroidManifest.xml" to bytes))
        val summary = call(context, "android_manifest_summary", "projectId" to source.projectId)
        assertEquals("example.binary", summary["packageName"]!!.jsonPrimitive.content)
        val raw = call(context, "android_manifest_raw", "projectId" to source.projectId, "maxBytes" to 8)
        assertEquals("base64", raw["encoding"]!!.jsonPrimitive.content)
        assertTrue(raw["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertContentEquals(bytes.copyOf(8), java.util.Base64.getDecoder().decode(raw["decodedXml"]!!.jsonPrimitive.content))
    }

    @Test
    fun `AAB protobuf manifests retain base and feature module identity`() = workspace { context, projects ->
        val node = Resources.XmlNode.newBuilder().setElement(Resources.XmlElement.newBuilder().setName("manifest")
            .addAttribute(Resources.XmlAttribute.newBuilder().setName("package").setValue("example.bundle"))).build().toByteArray()
        val source = projects.createFromBytes("sample.aab", zip("base/manifest/AndroidManifest.xml" to node, "feature/manifest/AndroidManifest.xml" to node))
        val summary = call(context, "android_manifest_summary", "projectId" to source.projectId)
        assertEquals("example.bundle", summary["packageName"]!!.jsonPrimitive.content)
        assertEquals(listOf("base", "feature"), summary["modules"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
    }

    @Test
    fun `malformed DEX and unsupported versions fail explicitly`() = workspace { context, projects ->
        val invalid = projects.createFromBytes("broken.dex", byteArrayOf(1, 2, 3))
        assertFailsWith<ToolInputException> { call(context, "android_dex_summary", "projectId" to invalid.projectId) }
        val bytes = dex().also { "041".toByteArray().copyInto(it, 4) }
        val unsupported = projects.createFromBytes("new.dex", bytes)
        assertFailsWith<ToolInputException> { call(context, "android_dex_summary", "projectId" to unsupported.projectId) }
    }

    @Test
    fun `schemas reject unknown keys wrong types and limits instead of silently defaulting`() = workspace { context, projects ->
        val source = projects.createFromBytes("sample.dex", dex())
        for (value in listOf(-1, 501, "5")) {
            assertFailsWith<ToolInputException> { call(context, "android_list_classes", "projectId" to source.projectId, "limit" to value) }
        }
        assertFailsWith<ToolInputException> { call(context, "android_list_classes", "projectId" to source.projectId, "dexPath" to "classes.dex") }
        assertFailsWith<ToolInputException> { call(context, "android_class_version_switch", "projectId" to source.projectId, "targetMajorVersion" to 44) }
    }

    @Test
    fun `checksum repair creates a separate importable DEX and preserves the original`() = workspace { context, projects ->
        val original = dex().also { it[8] = (it[8].toInt() xor 1).toByte() }
        val source = projects.createFromBytes("sample.dex", original)
        val repaired = call(context, "android_dex_recompute_checksum", "projectId" to source.projectId)
        assertTrue(repaired["completed"]!!.jsonPrimitive.content.toBoolean())
        assertContentEquals(original, Files.readAllBytes(Path.of(source.archivePath)))
        val artifact = repaired["outputs"]!!.jsonArray.single().jsonObject
        val imported = call(context, "import_android_artifact", "projectId" to source.projectId,
            "artifactPath" to artifact["path"]!!.jsonPrimitive.content, "expectedSha256" to artifact["sha256"]!!.jsonPrimitive.content)
        assertNotEquals(source.projectId, imported["projectId"]!!.jsonPrimitive.content)
        assertEquals(1, call(context, "android_dex_summary", "projectId" to imported["projectId"]!!.jsonPrimitive.content)["classCount"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `artifact import rejects cross-project paths incorrect hashes and traversal`() = workspace { _, projects ->
        val source = projects.createFromBytes("sample.dex", dex())
        val other = projects.createFromBytes("other.dex", dex())
        val store = AndroidProjectStore(projects)
        val output = store.createArtifact(source.projectId, "artifacts/result.dex", "android_smali_assemble", dex())
        val path = store.projectRoot(source.projectId).resolve(output.relativePath).toString()
        assertFailsWith<IllegalArgumentException> { store.importArtifact(other.projectId, path, null) }
        assertFailsWith<IllegalArgumentException> { store.importArtifact(source.projectId, path, "0".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { store.createArtifact(source.projectId, "artifacts/../result.dex", "android_smali_assemble", dex()) }
        assertEquals(2, projects.list().size)
    }

    @Test
    fun `missing converters report failure without publishing artifacts`() = workspace { _, projects ->
        val source = projects.createFromBytes("sample.dex", dex())
        val service = AndroidToolsService(AndroidProjectStore(projects), AndroidCommandRunner(toolRoot = null, pathFallbackRoot = null))
        runBlocking {
            assertFalse(service.dex2jar(source.projectId).completed)
            assertFalse(service.baksmali(source.projectId).completed)
            assertFalse(service.jar2dex(source.projectId).outputExists)
            assertFalse(service.jar2jasmin(source.projectId).completed)
        }
        assertEquals(1, projects.list().size)
        assertFalse(Files.exists(projects.workspaceRoot.resolve("android")))
    }

    @Test
    fun `class header rewrite creates a JAR that can enter the existing JVM workflow`() = workspace { context, projects ->
        val writer = ClassWriter(0)
        writer.visit(AsmOpcodes.V21, AsmOpcodes.ACC_PUBLIC, "example/Jvm", null, "java/lang/Object", null)
        writer.visitEnd()
        val source = projects.createFromBytes("sample.jar", zip("example/Jvm.class" to writer.toByteArray()))
        val changed = call(context, "android_class_version_switch", "projectId" to source.projectId, "targetMajorVersion" to 52)
        assertTrue(changed["completed"]!!.jsonPrimitive.content.toBoolean())
        val imported = call(context, "import_android_artifact", "projectId" to source.projectId,
            "artifactPath" to changed["outputJarPath"]!!.jsonPrimitive.content)
        val indexed = JvmIndexService(projects).ensureIndex(imported["projectId"]!!.jsonPrimitive.content)
        assertEquals("example.Jvm", indexed.classes.single().name)
        assertTrue(projects.verifyIdentity(source.projectId).matches)
    }

    @Test
    fun `trusted executable discovery rejects arbitrary names paths and PATH fallback`() {
        val runner = AndroidCommandRunner(toolRoot = null, pathFallbackRoot = null)
        for (name in listOf("java", "smali", "../smali", "smali.bat", "C:\\tools\\smali")) assertEquals(null, runner.locate(name))
        val options = D8ConversionOptions.fromEnvironment(mapOf("ACRAB_ANDROID_MIN_API" to "26", "ACRAB_ANDROID_D8_VERSION" to "8.0"))
        assertEquals(26, options.minApi)
        assertEquals("8.0", options.expectedToolVersion)
        assertFailsWith<IllegalArgumentException> { D8ConversionOptions.fromEnvironment(mapOf("ACRAB_ANDROID_MIN_API" to "0")) }
    }

    private fun workspace(action: (AcrabContext, ProjectRepository) -> Unit) {
        val root = Files.createTempDirectory("acrab-android-test-")
        try {
            AcrabContext(AcrabConfig(workspacePath = root.toString())).use { context ->
                ProjectRepository(context.config).use { projects -> registerAndroidTools(context, projects); action(context, projects) }
            }
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun call(context: AcrabContext, name: String, vararg arguments: Pair<String, Any>): JsonObject {
        val result = context.tools.call(name, JsonObject(arguments.associate { (key, value) ->
            key to when (value) { is Int -> JsonPrimitive(value); else -> JsonPrimitive(value.toString()) }
        }))
        return analysisJson.parseToJsonElement(result.content.single().text).jsonObject
    }

    private fun dex(): ByteArray {
        val owner = "Lexample/Android;"
        val body = ImmutableMethodImplementation(1, listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("sample-text")),
            ImmutableInstruction10x(Opcode.RETURN_VOID)), emptyList(), emptyList())
        val methods = listOf(emptyList(), listOf(ImmutableMethodParameter("I", emptySet(), null))).map { parameters ->
            ImmutableMethod(owner, "run", parameters, "V", 9, emptySet(), emptySet(), body)
        }
        val clazz = ImmutableClassDef(owner, 1, "Ljava/lang/Object;", emptyList(), null, emptySet(), emptyList(), methods)
        val output = MemoryDataStore()
        return try {
            DexPool.writeTo(output, ImmutableDexFile(Opcodes.forApi(21), listOf(clazz)))
            output.data
        } finally { output.close() }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()

    private fun binaryManifest(): ByteArray {
        val strings = listOf("manifest", "package", "example.binary").map { text ->
            ByteBuffer.allocate(4 + text.length * 2).order(ByteOrder.LITTLE_ENDIAN).putShort(text.length.toShort()).put(text.toByteArray(Charsets.UTF_16LE)).putShort(0).array()
        }
        val stringSize = strings.sumOf { it.size }
        val poolSize = (40 + stringSize + 3) / 4 * 4
        val pool = ByteBuffer.allocate(poolSize).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1).putShort(28).putInt(poolSize).putInt(3).putInt(0).putInt(0).putInt(40).putInt(0)
        var offset = 0
        strings.forEach { pool.putInt(offset); offset += it.size }
        strings.forEach(pool::put)
        val start = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x102).putShort(16).putInt(56).putInt(1).putInt(-1).putInt(-1).putInt(0)
            .putShort(20).putShort(20).putShort(1).putShort(0).putShort(0).putShort(0)
            .putInt(-1).putInt(1).putInt(2).putShort(8).put(0).put(3).putInt(2).array()
        val end = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x103).putShort(16).putInt(24).putInt(1).putInt(-1).putInt(-1).putInt(0).array()
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort(3).putShort(8).putInt(8 + poolSize + start.size + end.size).array()
        return header + pool.array() + start + end
    }
}
