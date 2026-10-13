package net.evrt.acrab.app

import net.evrt.acrab.core.AcrabConfig
import net.evrt.acrab.core.AcrabContext
import net.evrt.acrab.mcp.HttpMcpTransport
import net.evrt.acrab.mcp.StdioMcpTransport

fun main(arguments: Array<String>) {
    val defaults = AcrabConfig()
    val config = defaults.copy(
        httpHost = System.getenv("ACRAB_HTTP_HOST") ?: defaults.httpHost,
        httpPort = environmentLong("ACRAB_HTTP_PORT", defaults.httpPort.toLong()).also {
            require(it in 0..65535) { "ACRAB_HTTP_PORT must be between 0 and 65535." }
        }.toInt(),
        allowRemoteHttp = System.getenv("ACRAB_ALLOW_REMOTE")?.equals("true", ignoreCase = true) == true,
        maxHttpRequestBytes = environmentLong("ACRAB_MAX_HTTP_REQUEST_BYTES", defaults.maxHttpRequestBytes),
        httpAllowedHosts = environmentSet("ACRAB_HTTP_ALLOWED_HOSTS", defaults.httpAllowedHosts),
        httpAllowedOrigins = environmentSet("ACRAB_HTTP_ALLOWED_ORIGINS", defaults.httpAllowedOrigins),
        httpBearerToken = System.getenv("ACRAB_HTTP_AUTH_TOKEN"),
        httpMaxConcurrentRequests = environmentLong("ACRAB_HTTP_MAX_CONCURRENT_REQUESTS", defaults.httpMaxConcurrentRequests.toLong())
            .also { require(it in 1..256) { "HTTP concurrency must be between 1 and 256." } }.toInt(),
        httpMaxSessions = environmentLong("ACRAB_HTTP_MAX_SESSIONS", defaults.httpMaxSessions.toLong())
            .also { require(it in 1..Int.MAX_VALUE.toLong()) { "HTTP session limit must be positive." } }.toInt(),
        httpSessionIdleTimeoutMs = environmentLong("ACRAB_HTTP_SESSION_IDLE_TIMEOUT_MS", defaults.httpSessionIdleTimeoutMs),
        httpRequestTimeoutMs = environmentLong("ACRAB_HTTP_REQUEST_TIMEOUT_MS", defaults.httpRequestTimeoutMs),
        httpReadTimeoutMs = environmentLong("ACRAB_HTTP_READ_TIMEOUT_MS", defaults.httpReadTimeoutMs),
        httpSseHeartbeatMs = environmentLong("ACRAB_HTTP_SSE_HEARTBEAT_MS", defaults.httpSseHeartbeatMs),
        httpMaxSseStreams = environmentLong("ACRAB_HTTP_MAX_SSE_STREAMS", defaults.httpMaxSseStreams.toLong())
            .also { require(it in 1..Int.MAX_VALUE.toLong()) { "HTTP SSE stream limit must be positive." } }.toInt(),
    )

    AcrabContext(config).use { context ->
        registerAcrabTools(context)
        when (arguments.singleOrNull() ?: "stdio") {
            "stdio" -> StdioMcpTransport(context, System.`in`, System.out).use { transport ->
                transport.serve()
            }
            "http" -> HttpMcpTransport(context, config, INTERRUPTIBLE_TOOLS).use { transport ->
                transport.start()
                println("Acrab MCP listening at ${transport.endpoint}")
                val shutdown = Thread({ transport.close(); context.close() }, "acrab-http-shutdown")
                Runtime.getRuntime().addShutdownHook(shutdown)
                try { Thread.currentThread().join() }
                finally { runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) } }
            }
            else -> throw IllegalArgumentException("Unknown transport: ${arguments.single()}")
        }
    }
}

private fun environmentLong(name: String, default: Long): Long = System.getenv(name)?.let {
    it.toLongOrNull() ?: throw IllegalArgumentException("$name must be an integer.")
} ?: default

private fun environmentSet(name: String, default: Set<String>): Set<String> = System.getenv(name)?.let {
    it.split(',').map(String::trim).filter(String::isNotEmpty).toSet()
} ?: default

// Interrupt only observational tools. Import, indexing and patch bookkeeping/publication finish normally.
private val INTERRUPTIBLE_TOOLS = setOf(
    "health", "list_tools", "list_projects", "verify_archive_identity", "archive_summary",
    "list_classes", "search_classes", "search_strings", "class_summary", "list_decompiler_backends",
    "decompile_class", "decompile_method", "method_bytecode", "method_cfg", "method_stack_slice",
    "cfg_constant_propagation", "method_references", "field_references", "method_call_context",
    "recover_strings", "compare_structure", "find_number_secrets", "find_string_transforms",
    "find_opaque_predicates", "find_control_flow_flattening", "find_invokedynamic_dispatch",
    "evidence_summary", "finder_catalog", "patch_catalog",
)
