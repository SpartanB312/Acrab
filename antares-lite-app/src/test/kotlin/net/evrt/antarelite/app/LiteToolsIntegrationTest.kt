package net.evrt.antarelite.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.evrt.antarelite.core.LiteConfig
import net.evrt.antarelite.core.LiteContext
import java.nio.file.Files
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.io.path.outputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiteToolsIntegrationTest {
    @Test
    fun `registers thirty-two domain tools and two platform tools`() {
        LiteContext(LiteConfig(workspacePath = tempWorkspace())).use { context ->
            registerLiteTools(context)
            assertEquals(32, context.tools.analysisDefinitions().size)
            assertEquals(34, context.tools.definitions().size)
        }
    }

    @Test
    fun `every required parameter has a typed property in every published schema`() {
        fun checkSchema(schema: kotlinx.serialization.json.JsonElement, path: String) {
            when (schema) {
                is kotlinx.serialization.json.JsonObject -> {
                    val required = schema["required"] as? kotlinx.serialization.json.JsonArray
                    val properties = schema["properties"] as? kotlinx.serialization.json.JsonObject
                    required?.forEach { name ->
                        val property = properties?.get(name.jsonPrimitive.content)?.jsonObject
                        assertTrue(property != null, "$path missing ${name.jsonPrimitive.content}")
                        assertTrue(property.containsKey("type") || property.containsKey("oneOf"),
                            "$path.${name.jsonPrimitive.content} missing type")
                    }
                    schema.forEach { (key, value) -> checkSchema(value, "$path.$key") }
                }
                is kotlinx.serialization.json.JsonArray -> schema.forEachIndexed { index, value ->
                    checkSchema(value, "$path[$index]")
                }
                else -> Unit
            }
        }
        LiteContext(LiteConfig(workspacePath = tempWorkspace())).use { context ->
            registerLiteTools(context)
            context.tools.definitions().forEach { checkSchema(it.inputSchema, it.name) }
        }
    }

    @Test
    fun `patch tool schemas publish v2 hashes and operation-specific arguments`() {
        LiteContext(LiteConfig(workspacePath = tempWorkspace())).use { context ->
            registerLiteTools(context)
            val tools = context.tools.definitions().associateBy { it.name }
            val draftRequest = tools.getValue("method_patch_draft").inputSchema
                .getValue("properties").jsonObject.getValue("request").jsonObject
            val draftOperations = draftRequest.getValue("properties").jsonObject
                .getValue("operations").jsonObject.getValue("items").jsonObject
                .getValue("oneOf").jsonArray.map { it.jsonObject }
            assertEquals(6, draftOperations.size)
            val constant = draftOperations.single {
                it.getValue("properties").jsonObject.getValue("operation").jsonObject
                    .getValue("const").jsonPrimitive.content == "replace_constant"
            }
            val constantArguments = constant.getValue("properties").jsonObject
                .getValue("arguments").jsonObject
            assertEquals(
                "value",
                constantArguments.getValue("required").jsonArray.single().jsonPrimitive.content,
            )

            val previewRequest = tools.getValue("method_patch_preview").inputSchema
                .getValue("properties").jsonObject.getValue("request").jsonObject
            val previewRequired = previewRequest.getValue("required").jsonArray.map { it.jsonPrimitive.content }
            assertTrue(previewRequired.containsAll(listOf(
                "inputSha256", "methodInstructionSha256", "debugMetadataSha256",
            )))
            assertEquals(
                "2",
                previewRequest.getValue("properties").jsonObject.getValue("schemaVersion")
                    .jsonObject.getValue("const").jsonPrimitive.content,
            )
            val applyRequired = tools.getValue("apply_method_patch").inputSchema
                .getValue("required").jsonArray.map { it.jsonPrimitive.content }
            assertTrue(applyRequired.contains("previewId"))
        }
    }

    @Test
    fun `imports indexes and analyzes a jar through public tools`() {
        val workspace = tempWorkspace()
        LiteContext(LiteConfig(workspacePath = workspace)).use { context ->
            registerLiteTools(context)
            val archive = fixtureJar()
            val encoded = java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(archive))

            val created = call(context, "create_project", """
                {"fileName":"fixture.jar","archiveBase64":"$encoded"}
            """.trimIndent())
            val projectId = Json.parseToJsonElement(created).jsonObject["projectId"]!!.jsonPrimitive.content

            val indexed = call(context, "index_archive", """{"projectId":"$projectId"}""")
            assertEquals("1", Json.parseToJsonElement(indexed).jsonObject["classCount"]!!.jsonPrimitive.content)

            val classes = Json.parseToJsonElement(call(context, "list_classes", """{"projectId":"$projectId"}""")).jsonArray
            assertEquals(1, classes.size)
            assertEquals(
                "Smoke",
                Json.parseToJsonElement(call(context, "search_classes", """{"projectId":"$projectId","query":"Smoke"}""")).jsonArray.single()
                    .jsonObject["name"]!!.jsonPrimitive.content,
            )
            assertTrue(
                call(context, "search_strings", """{"projectId":"$projectId","query":"alpha"}""")
                    .contains("value"),
            )
            assertTrue(
                call(context, "class_summary", """{"projectId":"$projectId","className":"Smoke"}""")
                    .contains("decode"),
            )
            assertTrue(
                call(context, "method_bytecode", """{"projectId":"$projectId","className":"Smoke","methodName":"decode"}""")
                    .contains("opcodeName"),
            )
            assertTrue(
                call(context, "list_decompiler_backends", "{}").contains("CFR"),
            )
            assertTrue(
                call(
                    context,
                    "decompile_class",
                    """{"projectId":"$projectId","className":"Smoke","backend":"vineflower"}""",
                )
                    .contains("decode"),
            )
            assertTrue(
                call(context, "decompile_method", """{"projectId":"$projectId","className":"Smoke","methodName":"decode"}""")
                    .contains("public String decode"),
            )
            assertTrue(
                call(context, "method_cfg", """{"projectId":"$projectId","className":"Smoke","methodName":"decode"}""")
                    .contains("block_"),
            )
            val bytecode = Json.parseToJsonElement(call(context, "method_bytecode",
                """{"projectId":"$projectId","className":"Smoke","methodName":"decode"}""")).jsonObject
            val instructions = bytecode.getValue("instructions").jsonArray.map { it.jsonObject }
            val store = instructions.first { it.getValue("opcodeName").jsonPrimitive.content == "ASTORE" }
            val storeIndex = store.getValue("index").jsonPrimitive.content.toInt()
            val slice = Json.parseToJsonElement(call(context, "method_stack_slice",
                """{"projectId":"$projectId","className":"Smoke","methodName":"decode","instructionIndex":$storeIndex}"""))
                .jsonObject.getValue("slices").jsonArray.single().jsonObject
            val producer = slice.getValue("producerInstructionIndex").jsonPrimitive.content.toInt()
            assertEquals("LDC", instructions.single { it.getValue("index").jsonPrimitive.content.toInt() == producer }
                .getValue("opcodeName").jsonPrimitive.content)
            assertTrue(
                call(context, "cfg_constant_propagation", """{"projectId":"$projectId","className":"Smoke","methodName":"decode"}""")
                    .contains("unknown"),
            )
            assertTrue(
                call(context, "evidence_summary", """{"projectId":"$projectId"}""").contains("categorySummary"),
            )
            assertEquals(32, Json.parseToJsonElement(call(context, "list_tools", "{}")).jsonArray.size)
        }
    }

    private fun call(context: LiteContext, tool: String, arguments: String): String =
        context.tools.call(tool, Json.parseToJsonElement(arguments).jsonObject).content.single().text

    private fun fixtureJar(): java.nio.file.Path {
        val archive = Files.createTempFile("antares-lite-tools", ".jar")
        val classBytes = compileFixture()
        JarOutputStream(archive.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("Smoke.class"))
            output.write(classBytes)
            output.closeEntry()
        }
        return archive
    }

    private fun compileFixture(): ByteArray {
        val source = """
            public class Smoke {
                public String decode(int value) {
                    String alpha = "alpha";
                    String beta = "beta";
                    String gamma = "gamma";
                    String delta = "delta";
                    String epsilon = "epsilon";
                    String zeta = "zeta";
                    int x = (value ^ 0x5f) + 7;
                    int y = (x >>> 3) ^ 17;
                    int z = (y * 31) & 0xff;
                    if (z > 100) return alpha + beta;
                    if (z < 20) return gamma + delta;
                    return epsilon + zeta;
                }
            }
        """.trimIndent()
        val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
        requireNotNull(compiler) { "JDK compiler is required." }
        val root = Files.createTempDirectory("antares-lite-tools-src")
        val sourceFile = root.resolve("Smoke.java")
        Files.writeString(sourceFile, source)
        val classes = root.resolve("classes")
        Files.createDirectories(classes)
        val task = compiler.run(null, null, null, sourceFile.toString(), "-d", classes.toString())
        require(task == 0) { "Fixture compilation failed." }
        return Files.readAllBytes(classes.resolve("Smoke.class"))
    }

    private fun tempWorkspace(): String = Files.createTempDirectory("antares-lite-tools-workspace").toString()
}


