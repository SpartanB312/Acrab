package net.evrt.acrab.analysis.index

import java.nio.file.Files
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.io.path.outputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JarIndexerTest {
    @Test
    fun `indexes classes strings and method metrics`() {
        val archive = Files.createTempFile("acrab-index", ".jar")
        val classBytes = compiledTestClass()
        JarOutputStream(archive.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("sample/Demo.class"))
            output.write(classBytes)
            output.closeEntry()
        }

        val index = JarIndexer().index("test-project", archive)

        assertEquals(1, index.summary.classCount)
        assertEquals("sample.Demo", index.classes.single().name)
        assertTrue(index.strings.any { it.value == "acrab" })
        val method = index.classes.single().methods.single { it.name == "decode" }
        assertTrue(method.metrics.numericConstantCount > 0)
        assertTrue(method.metrics.bitwiseCount > 0)
        Files.deleteIfExists(archive)
    }

    private fun compiledTestClass(): ByteArray {
        val source = """
            package sample;

            public class Demo {
                public String decode(int value) {
                    String prefix = "acrab";
                    return prefix + ((value ^ 0x5f) + 7);
                }
            }
        """.trimIndent()
        val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
        requireNotNull(compiler) { "JDK compiler is required for this test." }
        val directory = Files.createTempDirectory("acrab-classes")
        val sourceFile = directory.resolve("Demo.java")
        Files.writeString(sourceFile, source)
        val output = directory.resolve("classes")
        Files.createDirectories(output)
        val arguments = listOf(
            sourceFile.toString(),
            "-d",
            output.toString(),
        )
        val task = compiler.run(null, null, null, *arguments.toTypedArray())
        require(task == 0) { "Test fixture compilation failed." }
        return Files.readAllBytes(output.resolve("sample/Demo.class"))
    }
}
