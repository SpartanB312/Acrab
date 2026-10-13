package net.evrt.acrab.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.evrt.acrab.core.AcrabConfig
import net.evrt.acrab.core.AcrabContext
import net.evrt.acrab.core.ToolInputException
import java.nio.file.Files
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.io.path.outputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Regression coverage for the P0 findings: a misspelled descriptor key must be a hard error, an
 * overloaded name must not silently resolve to the first method, and every method-scoped response
 * must prove which method it addressed.
 */
class MethodResolutionToolTest {
    @Test
    fun `misspelled descriptor key is rejected instead of silently dropped`() {
        withProject { context, projectId ->
            val failure = assertFailsWith<ToolInputException> {
                call(
                    context,
                    "method_bytecode",
                    """{"projectId":"$projectId","className":"Overloads","methodName":"compute","descriptor":"()I"}""",
                )
            }
            assertTrue(failure.message!!.contains("descriptor"), failure.message!!)
            assertTrue(failure.message!!.contains("methodDescriptor"), failure.message!!)
        }
    }

    @Test
    fun `overloaded method resolves against the descriptor`() {
        withProject { context, projectId ->
            val noArg = methodBytecode(context, projectId, "compute", "()I")
            val oneArg = methodBytecode(context, projectId, "compute", "(I)I")

            assertEquals("()I", noArg["descriptor"]!!.jsonPrimitive.content)
            assertEquals("(I)I", oneArg["descriptor"]!!.jsonPrimitive.content)
            assertTrue(noArg["exactMethodMatch"]!!.jsonPrimitive.content.toBoolean())
            assertTrue(oneArg["exactMethodMatch"]!!.jsonPrimitive.content.toBoolean())
            assertEquals("DESCRIPTOR", oneArg["methodMatchKind"]!!.jsonPrimitive.content)
            // Different bodies must produce different instruction counts.
            assertTrue(
                noArg["instructionCount"]!!.jsonPrimitive.content !=
                    oneArg["instructionCount"]!!.jsonPrimitive.content,
                "both overloads returned identical instruction evidence",
            )
        }
    }

    @Test
    fun `overloaded name without a descriptor reports ambiguity`() {
        withProject { context, projectId ->
            val failure = assertFailsWith<ToolInputException> {
                call(
                    context,
                    "method_bytecode",
                    """{"projectId":"$projectId","className":"Overloads","methodName":"compute"}""",
                )
            }
            assertTrue(failure.message!!.contains("overloaded"), failure.message!!)
            assertTrue(failure.message!!.contains("(I)I"), failure.message!!)
            assertTrue(failure.message!!.contains("()I"), failure.message!!)
        }
    }

    @Test
    fun `descriptor that matches no method is rejected`() {
        withProject { context, projectId ->
            val failure = assertFailsWith<ToolInputException> {
                call(
                    context,
                    "method_bytecode",
                    """{"projectId":"$projectId","className":"Overloads","methodName":"compute","methodDescriptor":"(J)J"}""",
                )
            }
            assertTrue(failure.message!!.contains("(J)J"), failure.message!!)
            assertTrue(failure.message!!.contains("Available descriptors"), failure.message!!)
        }
    }

    @Test
    fun `decompile_method honours the descriptor and never echoes a phantom one`() {
        withProject { context, projectId ->
            val oneArg = decompile(context, projectId, "compute", "(I)I")
            assertEquals("(I)I", oneArg["methodDescriptor"]!!.jsonPrimitive.content)
            assertTrue(oneArg["exactMethodMatch"]!!.jsonPrimitive.content.toBoolean())
            assertTrue(
                oneArg["source"]!!.jsonPrimitive.content.contains("compute"),
                oneArg["source"]!!.jsonPrimitive.content,
            )

            val failure = assertFailsWith<ToolInputException> {
                call(
                    context,
                    "decompile_method",
                    """{"projectId":"$projectId","className":"Overloads","methodName":"compute","methodDescriptor":"(Ld;)V"}""",
                )
            }
            assertTrue(failure.message!!.contains("(Ld;)V"), failure.message!!)
        }
    }

    @Test
    fun `decompilation verifies types and returns for same arity overloads in both backends`() {
        withProject { context, projectId ->
            for (backend in listOf("cfr", "vineflower")) {
                for (descriptor in listOf("(C)J", "([B)I", "(J)LOverloads;", "(LOverloads;)V")) {
                    val methodName = if (descriptor.contains("Overloads")) "a" else "pick"
                    val result = Json.parseToJsonElement(call(context, "decompile_method",
                        """{"projectId":"$projectId","className":"Overloads","methodName":"$methodName","methodDescriptor":"$descriptor","backend":"$backend"}""",
                    )).jsonObject
                    assertEquals(descriptor, result["emittedDescriptor"]!!.jsonPrimitive.content)
                    assertTrue(result["exactMethodMatch"]!!.jsonPrimitive.content.toBoolean(), result.toString())
                }
            }
        }
    }

    @Test
    fun `decompile_method rejects an overloaded name with no descriptor`() {
        withProject { context, projectId ->
            val failure = assertFailsWith<ToolInputException> {
                call(
                    context,
                    "decompile_method",
                    """{"projectId":"$projectId","className":"Overloads","methodName":"compute"}""",
                )
            }
            assertTrue(failure.message!!.contains("overloaded"), failure.message!!)
        }
    }

    @Test
    fun `method_bytecode completes mnemonics and supplies branch targets`() {
        withProject { context, projectId ->
            val result = methodBytecode(context, projectId, "branchy", "(I)Ljava/lang/String;")
            val instructions = result["instructions"]!!.jsonArray
            assertTrue(instructions.isNotEmpty())

            val unnamed = instructions.map { it.jsonObject["opcodeName"]!!.jsonPrimitive.content }
                .filter { it.startsWith("OPCODE_") }
            assertTrue(unnamed.isEmpty(), "incomplete mnemonic table: ${unnamed.distinct()}")

            val branches = instructions.map { it.jsonObject }.filter { instruction ->
                instruction["opcodeName"]!!.jsonPrimitive.content.startsWith("IF")
            }
            assertTrue(branches.isNotEmpty(), "fixture method has no conditional branches")
            assertTrue(
                branches.all { it["jumpTargetIndex"] != null },
                "conditional branches are missing jumpTargetIndex",
            )
        }
    }

    private fun withProject(block: (AcrabContext, String) -> Unit) {
        AcrabContext(AcrabConfig(workspacePath = tempWorkspace())).use { context ->
            registerAcrabTools(context)
            val archive = fixtureJar()
            val encoded = java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(archive))
            val created = call(
                context,
                "create_project",
                """{"fileName":"overloads.jar","archiveBase64":"$encoded"}""",
            )
            val projectId = Json.parseToJsonElement(created).jsonObject["projectId"]!!.jsonPrimitive.content
            call(context, "index_archive", """{"projectId":"$projectId"}""")
            block(context, projectId)
        }
    }

    private fun methodBytecode(
        context: AcrabContext,
        projectId: String,
        methodName: String,
        descriptor: String,
    ) = Json.parseToJsonElement(
        call(
            context,
            "method_bytecode",
            """{"projectId":"$projectId","className":"Overloads","methodName":"$methodName","methodDescriptor":"$descriptor"}""",
        ),
    ).jsonObject

    private fun decompile(
        context: AcrabContext,
        projectId: String,
        methodName: String,
        descriptor: String,
    ) = Json.parseToJsonElement(
        call(
            context,
            "decompile_method",
            """{"projectId":"$projectId","className":"Overloads","methodName":"$methodName","methodDescriptor":"$descriptor"}""",
        ),
    ).jsonObject

    private fun call(context: AcrabContext, tool: String, arguments: String): String =
        context.tools.call(tool, Json.parseToJsonElement(arguments).jsonObject).content.single().text

    private fun fixtureJar(): java.nio.file.Path {
        val archive = Files.createTempFile("acrab-overloads", ".jar")
        JarOutputStream(archive.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("Overloads.class"))
            output.write(compileFixture())
            output.closeEntry()
        }
        return archive
    }

    private fun compileFixture(): ByteArray {
        val source = """
            public class Overloads {
                private int state;
                public int compute() { return 1; }
                public int compute(int x) { return x + 2; }
                public long pick(char c) { return state + c; }
                public int pick(byte[] data) { return data.length; }
                private void a(Overloads value) { this.state = 9; }
                private Overloads a(long value) { return this; }
                public String branchy(int value) {
                    String alpha = "alpha";
                    String beta = "beta";
                    if (value > 10) return alpha;
                    if (value < 0) return beta;
                    while (value > 0) { value -= 1; }
                    return value == 0 ? alpha : beta;
                }
            }
        """.trimIndent()
        val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
        requireNotNull(compiler) { "JDK compiler is required." }
        val root = Files.createTempDirectory("acrab-overloads-src")
        val sourceFile = root.resolve("Overloads.java")
        Files.writeString(sourceFile, source)
        val classes = root.resolve("classes")
        Files.createDirectories(classes)
        val task = compiler.run(null, null, null, sourceFile.toString(), "-g", "-d", classes.toString())
        require(task == 0) { "Fixture compilation failed." }
        return Files.readAllBytes(classes.resolve("Overloads.class"))
    }

    private fun tempWorkspace(): String =
        Files.createTempDirectory("acrab-overloads-workspace").toString()
}
