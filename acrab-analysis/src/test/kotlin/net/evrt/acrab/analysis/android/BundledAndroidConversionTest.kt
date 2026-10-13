package net.evrt.acrab.analysis.android

import kotlinx.coroutines.runBlocking
import net.evrt.acrab.analysis.index.JvmIndexService
import net.evrt.acrab.analysis.index.ProjectRepository
import net.evrt.acrab.core.AcrabConfig
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Release verification uses generated inputs and the actual packaged converters, never uploaded code. */
class BundledAndroidConversionTest {
    @Test
    fun `bundled converters resolve and run without external roots or PATH wrappers`() = workspace { root, _, tools, _ ->
        val runner = AndroidCommandRunner(toolRoot = null, pathFallbackRoot = null, bundledTools = tools)
        val work = Files.createDirectory(root.resolve("version probes"))
        for (name in BundledAndroidTools.MAIN_CLASSES.keys) assertNotNull(runner.locate(name))
        for ((name, arguments, version) in listOf(Triple("smali", listOf("--version"), "3.0.10"),
            Triple("baksmali", listOf("--version"), "3.0.10"), Triple("d8", listOf("--version"), "9.5.23"),
            Triple("d2j-dex2jar", listOf("--help"), "2.4.38"))) {
            val result = runBlocking { runner.run(name, arguments, work) }
            assertTrue(result.completed, "$name failed: ${result.stderr}")
            assertTrue((result.stdout + result.stderr).contains(version), "$name did not report pinned version")
        }
    }

    @Test
    fun `real D8 dex2jar baksmali and smali preserve generated program definitions across handoffs`() = workspace { _, projects, _, service ->
        val sourceBytes = jar()
        val source = projects.createFromBytes("program.jar", sourceBytes)
        val store = AndroidProjectStore(projects)
        runBlocking {
            val dex = service.jar2dex(source.projectId)
            assertTrue(dex.outputExists, "D8: ${dex.command.stderr} ${dex.warnings}")
            assertEquals("9.5.23", dex.toolVersion)
            assertEquals(1, dex.dexClassCount)
            val dexProject = store.importArtifact(source.projectId, dex.outputDexPath!!, null)
            val converted = service.dex2jar(dexProject.projectId)
            assertTrue(converted.completed, "dex2jar: ${converted.command.stderr} ${converted.warnings}")
            assertEquals("2.4.38", converted.toolVersion)
            val jvm = store.importArtifact(dexProject.projectId, converted.outputJarPath!!, null)
            val clazz = JvmIndexService(projects).ensureIndex(jvm.projectId).classes.single()
            assertEquals("example.Program", clazz.name)
            assertEquals(setOf("number", "message"), clazz.methods.map { it.name }.toSet())
            assertEquals(listOf("VALUE"), clazz.fields.map { it.name })
            assertEquals(0, service.asmVerify(jvm.projectId).issueCount)
            val smali = service.baksmali(dexProject.projectId)
            assertTrue(smali.completed, "baksmali: ${smali.command.stderr} ${smali.warnings}")
            assertEquals(1, smali.smaliFileCount)
            val assembled = service.smaliAssemble(dexProject.projectId, Path.of(smali.sampleFiles.single()).parent.toString())
            assertTrue(assembled.completed, "smali: ${assembled.command.stderr} ${assembled.warnings}")
            val reimported = store.importArtifact(dexProject.projectId, assembled.outputDexPath!!, null)
            val evidence = AndroidAnalysisService(store).classSummary(reimported.projectId, "example.Program", null)
            assertEquals(setOf("number", "message"), evidence.methods.map { it.name }.toSet())
            assertEquals(1, evidence.fieldCount)
        }
        assertContentEquals(sourceBytes, Files.readAllBytes(Path.of(source.archivePath)))
        assertTrue(projects.verifyIdentity(source.projectId).matches)
    }

    @Test
    fun `bundled Jasmin produces a validated class and preserves resources`() = workspace { _, projects, _, service ->
        val project = projects.createFromBytes("program.jar", jar())
        val input = Files.createDirectory(AndroidProjectStore(projects).projectRoot(project.projectId).resolve("jasmin input"))
        Files.writeString(input.resolve("Program.j"), """
            .class public example/JasminProgram
            .super java/lang/Object
            .method public static number()I
            .limit stack 1
            .limit locals 0
            bipush 7
            ireturn
            .end method
        """.trimIndent())
        val original = Files.readAllBytes(input.resolve("Program.j"))
        Files.writeString(input.resolve("resource.txt"), "preserved")
        val result = runBlocking { service.jasmin2jar(project.projectId, input.toString()) }
        assertTrue(result.completed, "Jasmin: ${result.command.stderr} ${result.warnings}")
        assertContentEquals(original, Files.readAllBytes(input.resolve("Program.j")))
        val imported = AndroidProjectStore(projects).importArtifact(project.projectId, result.outputJarPath!!, null)
        assertEquals("example.JasminProgram", JvmIndexService(projects).ensureIndex(imported.projectId).classes.single().name)
        java.util.zip.ZipFile(result.outputJarPath).use { zip ->
            assertEquals("preserved", zip.getInputStream(zip.getEntry("resource.txt")).readBytes().toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `unsupported Jar-to-Jasmin still creates no artifact despite the bundled assembler`() = workspace { _, projects, _, service ->
        val project = projects.createFromBytes("program.jar", jar())
        val result = runBlocking { service.jar2jasmin(project.projectId) }
        assertFalse(result.completed)
        assertEquals(null, result.outputDirectory)
        assertTrue(result.warnings.any { it.contains("unsupported", ignoreCase = true) })
    }

    private fun workspace(action: (Path, ProjectRepository, BundledAndroidTools, AndroidToolsService) -> Unit) {
        val root = Files.createTempDirectory("acrab real converters with spaces ")
        try {
            val bundle = BundledAndroidTools(Path.of(System.getProperty("acrab.bundledAndroidTools")))
            ProjectRepository(AcrabConfig(workspacePath = root.toString())).use { projects ->
                val runner = AndroidCommandRunner(toolRoot = null, pathFallbackRoot = null, bundledTools = bundle)
                action(root, projects, bundle, AndroidToolsService(AndroidProjectStore(projects), runner, D8ConversionOptions()))
            }
        } finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    private fun jar(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "example/Program", null, "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "VALUE", "I", null, 7).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "number", "()I", null, null).apply {
            visitCode(); visitIntInsn(Opcodes.BIPUSH, 7); visitInsn(Opcodes.IRETURN); visitMaxs(1, 0); visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "message", "()Ljava/lang/String;", null, null).apply {
            visitCode(); visitLdcInsn("bundled conversion"); visitInsn(Opcodes.ARETURN); visitMaxs(1, 0); visitEnd()
        }
        writer.visitEnd()
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip -> zip.putNextEntry(ZipEntry("example/Program.class")); zip.write(writer.toByteArray()); zip.closeEntry() }
        }.toByteArray()
    }
}
