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
