package net.evrt.antarelite.app

import kotlinx.serialization.json.*
import net.evrt.antarelite.core.LiteConfig
import net.evrt.antarelite.core.LiteContext
import java.nio.file.Files
import java.util.Base64
import kotlin.test.*

/** The observed archive is analyzed as bytes only; its classes are never loaded or executed. */
class SnakeRegressionToolTest {
    @Test
    fun `trim branch consumes the counter loaded before the preceding guard`() {
        withSnake { context, project ->
            val result = context.tools.call("method_stack_slice", buildJsonObject {
                put("projectId", project); put("className", "e"); put("methodName", "e")
                put("methodDescriptor", "(J)V"); put("instructionIndex", 73)
            })
            assertFalse(result.isError)
            val slice = Json.parseToJsonElement(result.content.single().text).jsonObject
            assertEquals("IFNE", slice.getValue("targetOpcodeName").jsonPrimitive.content)
            val counter = slice.getValue("slices").jsonArray.single().jsonObject
            assertEquals(68, counter.getValue("producerInstructionIndex").jsonPrimitive.int)
            assertEquals(4, counter.getValue("localIndex").jsonPrimitive.int)
            val propagation = call(context, "cfg_constant_propagation", project,
                "className" to "e", "methodName" to "e", "methodDescriptor" to "(J)V")
            val branch = propagation.getValue("branchFacts").jsonArray.map { it.jsonObject }.single {
                it.getValue("instructionIndex").jsonPrimitive.int == 73
            }
            assertEquals("local[4] != 0", branch.getValue("condition").jsonPrimitive.content)
            assertEquals(86, branch.getValue("takenTargetIndex").jsonPrimitive.int)
        }
    }

    @Test
    fun `string initializers recover the exact original literals without executing classes`() {
        withSnake { context, project ->
            val title = call(context, "recover_strings", project, "className" to "c")
            assertEquals("recovered", title.getValue("status").jsonPrimitive.content, title.toString())
            assertEquals("Snake", title.getValue("strings").jsonArray.single().jsonObject.getValue("value").jsonPrimitive.content)
            assertEquals(listOf(109, 123, 13, 87, 124, 101, 116),
                title.getValue("cyclicKeys").jsonArray.map { it.jsonPrimitive.int })
            val messages = call(context, "recover_strings", project, "className" to "e")
            assertEquals("recovered", messages.getValue("status").jsonPrimitive.content, messages.toString())
            assertEquals(listOf("COLISION! \n", "Yummy!"), messages.getValue("strings").jsonArray.map {
                it.jsonObject.getValue("value").jsonPrimitive.content
            })
        }
    }

    @Test
    fun `real field writes and caller constants retain exact identity`() {
        withSnake { context, project ->
            val writes = call(context, "field_references", project, "className" to "d", "fieldName" to "e", "access" to "writes")
            assertEquals(1, writes.getValue("fields").jsonArray.size)
            val callees = call(context, "method_references", project, "className" to "c", "methodName" to "main",
                "methodDescriptor" to "([Ljava/lang/String;)V", "direction" to "callees")
            val constructor = callees.getValue("calls").jsonArray.map { it.jsonObject }.single {
                it.getValue("targetOwner").jsonPrimitive.content == "f" && it.getValue("targetName").jsonPrimitive.content == "<init>"
            }
            val result = context.tools.call("method_call_context", buildJsonObject {
                put("projectId", project); put("className", "c"); put("methodName", "main")
                put("methodDescriptor", "([Ljava/lang/String;)V"); put("instructionIndex", constructor.getValue("instructionIndex"))
            })
            assertFalse(result.isError)
            val arguments = Json.parseToJsonElement(result.content.single().text).jsonObject.getValue("calleeArgumentValues").jsonObject
            assertEquals("2429", arguments.getValue("1").jsonPrimitive.content)
        }
    }

    @Test
    fun `both backends report incomplete reconstruction and never claim the wrong overload`() {
        withSnake { context, project ->
            for (backend in listOf("cfr", "vineflower")) {
                val result = call(context, "decompile_class", project,
                    "className" to "e", "backend" to backend)
                assertTrue(result.getValue("failedRegions").jsonArray.isNotEmpty(), result.toString())
                assertEquals("low", result.getValue("confidence").jsonPrimitive.content)
                val method = call(context, "decompile_method", project,
                    "className" to "e", "methodName" to "a", "methodDescriptor" to "(J)Ld;", "backend" to backend)
                if (method.getValue("exactMethodMatch").jsonPrimitive.boolean) {
                    assertEquals("(J)Ld;", method.getValue("emittedDescriptor").jsonPrimitive.content)
                    assertFalse(method.getValue("source").jsonPrimitive.content.trimStart().startsWith("private void a(d"))
                } else {
                    assertTrue(method.getValue("failedRegions").jsonArray.isNotEmpty())
                }
            }
        }
    }

    @Test
    fun `real packed constants propagate and unconditional loop jump is known`() {
        withSnake { context, project ->
            val main = call(context, "cfg_constant_propagation", project,
                "className" to "c", "methodName" to "main", "methodDescriptor" to "([Ljava/lang/String;)V")
            val values = main.getValue("instructionFacts").jsonArray.map {
                it.jsonObject.getValue("value").jsonPrimitive.content
            }
            assertTrue("134279452297597" in values, values.toString())
            assertTrue("2048941838" in values, values.toString())
            val loop = call(context, "cfg_constant_propagation", project,
                "className" to "e", "methodName" to "run", "methodDescriptor" to "()V")
            val facts = loop.getValue("branchFacts").jsonArray.map { it.jsonObject }
            assertTrue(facts.any { it.getValue("opcodeName").jsonPrimitive.content == "GOTO" &&
                it.getValue("knownOutcome").jsonPrimitive.content == "taken" })
            val loopValues = loop.getValue("instructionFacts").jsonArray.map {
                it.jsonObject.getValue("value").jsonPrimitive.content
            }
            assertTrue("53836601486112" in loopValues)
            assertTrue("41896598891982" in loopValues)
            assertTrue(loop.getValue("blocks").jsonArray.any {
                it.jsonObject.getValue("localFacts").jsonArray.any { local -> local.jsonObject["value"] != null }
            })
        }
    }

    @Test
    fun `event switches are not called flattening and boolean guards have their own category`() {
        withSnake { context, project ->
            val flattening = call(context, "find_control_flow_flattening", project)
            assertTrue(flattening.getValue("findings").jsonArray.isEmpty())
            val guards = call(context, "find_opaque_predicates", project)
            assertTrue(guards.getValue("findings").jsonArray.any {
                val finding = it.jsonObject
                finding.getValue("className").jsonPrimitive.content == "e" &&
                    finding.getValue("methodName").jsonPrimitive.content == "b" &&
                    finding.getValue("category").jsonPrimitive.content == "opaque-guard" &&
                    finding.getValue("subpattern").jsonPrimitive.content == "static-boolean-guard"
            })
        }
    }

    @Test
    fun `CFG includes switch targets without a false switch fallthrough`() {
        withSnake { context, project ->
            val cfg = call(context, "method_cfg", project,
                "className" to "a", "methodName" to "keyPressed",
                "methodDescriptor" to "(Ljava/awt/event/KeyEvent;)V")
            val edges = cfg.getValue("edges").jsonArray.map { it.jsonObject }
            val switch = edges.filter { it.getValue("kind").jsonPrimitive.content == "switch" }
            assertEquals(setOf("37", "38", "39", "40", "default"),
                switch.map { it.getValue("caseKey").jsonPrimitive.content }.toSet())
            assertFalse(edges.any { it.getValue("source") == switch.first().getValue("source") &&
                it.getValue("kind").jsonPrimitive.content == "fallthrough" })
        }
    }

    private fun call(context: LiteContext, tool: String, project: String, vararg args: Pair<String, String>): JsonObject {
        val result = context.tools.call(tool, buildJsonObject {
            put("projectId", project)
            args.forEach { (name, value) -> put(name, value) }
        })
        assertFalse(result.isError)
        return Json.parseToJsonElement(result.content.single().text).jsonObject
    }

    private fun withSnake(block: (LiteContext, String) -> Unit) {
        val archive = javaClass.getResourceAsStream("/snake-obfuscated.jar")!!.use { it.readBytes() }
        LiteContext(LiteConfig(workspacePath = Files.createTempDirectory("antares-snake-regression").toString())).use { context ->
            registerLiteTools(context)
            val result = context.tools.call("create_project", buildJsonObject {
                put("fileName", "snake.jar")
                put("archiveBase64", Base64.getEncoder().encodeToString(archive))
            })
            val project = Json.parseToJsonElement(result.content.single().text).jsonObject
                .getValue("projectId").jsonPrimitive.content
            block(context, project)
        }
    }
}
