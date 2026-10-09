package net.evrt.antarelite.app

import net.evrt.antarelite.core.LiteConfig
import net.evrt.antarelite.core.LiteContext
import net.evrt.antarelite.mcp.HttpMcpTransport
import net.evrt.antarelite.mcp.StdioMcpTransport

fun main(arguments: Array<String>) {
    val config = LiteConfig(
        httpHost = System.getenv("ANTARES_LITE_HTTP_HOST") ?: "127.0.0.1",
        httpPort = System.getenv("ANTARES_LITE_HTTP_PORT")?.toIntOrNull() ?: 8080,
        allowRemoteHttp = System.getenv("ANTARES_LITE_ALLOW_REMOTE")?.equals("true", ignoreCase = true) == true,
        maxHttpRequestBytes = System.getenv("ANTARES_LITE_MAX_HTTP_REQUEST_BYTES")?.toLongOrNull()
            ?: 128L * 1024 * 1024,
    )

    LiteContext(config).use { context ->
        registerLiteTools(context)
        when (arguments.singleOrNull() ?: "stdio") {
            "stdio" -> StdioMcpTransport(context, System.`in`, System.out).use { transport ->
                transport.serve()
            }
            "http" -> HttpMcpTransport(context, config).use { transport ->
                transport.start()
                println("Antares Lite MCP listening at ${transport.endpoint}")
                Thread.currentThread().join()
            }
            else -> throw IllegalArgumentException("Unknown transport: ${arguments.single()}")
        }
    }
}
