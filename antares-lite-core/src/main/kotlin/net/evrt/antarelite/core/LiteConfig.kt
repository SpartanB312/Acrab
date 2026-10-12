package net.evrt.antarelite.core

data class LiteConfig(
    val serverName: String = "antares-lite",
    val serverVersion: String = "1.3.1",
    val workspacePath: String = "data",
    val maxArchiveBytes: Long = 128L * 1024 * 1024,
    val httpHost: String = "127.0.0.1",
    val httpPort: Int = 8080,
    val allowRemoteHttp: Boolean = false,
    val maxHttpRequestBytes: Long = 4L * ((maxArchiveBytes + 2) / 3) + 1024 * 1024,
    val httpAllowedHosts: Set<String> = setOf("127.0.0.1", "localhost", "::1"),
    val httpAllowedOrigins: Set<String> = emptySet(),
    val httpBearerToken: String? = null,
    val httpMaxConcurrentRequests: Int = 4,
    val httpMaxSessions: Int = 256,
    val httpSessionIdleTimeoutMs: Long = 30L * 60 * 1000,
    val httpRequestTimeoutMs: Long = 120_000,
    val httpReadTimeoutMs: Long = 30_000,
    val httpSseHeartbeatMs: Long = 15_000,
    val httpMaxSseStreams: Int = 32,
) {
    init {
        require(serverName.isNotBlank()) { "serverName must not be blank." }
        require(serverVersion.isNotBlank()) { "serverVersion must not be blank." }
        require(maxArchiveBytes > 0) { "maxArchiveBytes must be greater than zero." }
        require(httpHost.isNotBlank()) { "httpHost must not be blank." }
        require(httpPort in 0..65535) { "httpPort must be between 0 and 65535." }
        require(maxHttpRequestBytes in 1..Int.MAX_VALUE.toLong()) { "maxHttpRequestBytes must fit a JVM byte array." }
        require(httpAllowedHosts.isNotEmpty() && httpAllowedHosts.all { it.isNotBlank() }) { "httpAllowedHosts must not be empty." }
        require(httpBearerToken == null || httpBearerToken.isNotBlank()) { "httpBearerToken must not be blank." }
        require(httpMaxConcurrentRequests in 1..256) { "httpMaxConcurrentRequests must be between 1 and 256." }
        require(httpMaxSessions > 0 && httpMaxSseStreams > 0) { "HTTP resource limits must be positive." }
        require(httpSessionIdleTimeoutMs > 0 && httpRequestTimeoutMs > 0 && httpReadTimeoutMs > 0 && httpSseHeartbeatMs > 0) {
            "HTTP timeouts and heartbeat interval must be positive."
        }
    }
}

