package net.evrt.acrab.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import net.evrt.acrab.core.AcrabConfig
import net.evrt.acrab.core.AcrabContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

object ToolCatalogGenerator {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 1) { "Usage: ToolCatalogGenerator <output-file>" }
        val output = Path.of(arguments.single()).toAbsolutePath().normalize()
        Files.createDirectories(output.parent)
        val workspace = Files.createTempDirectory("acrab-tool-catalog-")

        try {
            AcrabContext(AcrabConfig(workspacePath = workspace.toString())).use { context ->
                registerAcrabTools(context)
                val definitions = context.tools.definitions()
                val document = buildJsonObject {
                    put("formatVersion", 1)
                    put("generatedAt", java.time.Instant.now().toString())
                    put("registeredToolCount", definitions.size)
                    put("analysisToolCount", context.tools.analysisDefinitions().size)
                    put("tools", json.encodeToJsonElement(definitions))
                }
                Files.writeString(
                    output,
                    json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), document),
                )
                println("Generated ${definitions.size} registered tool definitions in $output")
            }
        } finally {
            deleteWorkspace(workspace)
        }
    }

    private fun deleteWorkspace(workspace: Path) {
        if (!Files.exists(workspace)) return
        val normalizedWorkspace = workspace.toAbsolutePath().normalize()
        Files.walk(normalizedWorkspace)
            .use { paths ->
                paths.sorted(java.util.Comparator.reverseOrder()).forEach { path ->
                    val normalizedPath = path.toAbsolutePath().normalize()
                    check(normalizedPath.startsWith(normalizedWorkspace)) {
                        "Refusing to delete outside generator workspace: $normalizedPath"
                    }
                    path.toFile().setWritable(true, false)
                    path.deleteIfExists()
                }
            }
    }
}
