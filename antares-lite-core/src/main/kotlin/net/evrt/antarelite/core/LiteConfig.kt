package net.evrt.antarelite.core

data class LiteConfig(
    val serverName: String = "antares-lite",
    val serverVersion: String = "1.2.1",
    val workspacePath: String = "data",
    val maxArchiveBytes: Long = 128L * 1024 * 1024,
    val httpHost: String = "127.0.0.1",
    val httpPort: Int = 8080,
    val allowRemoteHttp: Boolean = false,
    val maxHttpRequestBytes: Long = 128L * 1024 * 1024,
) {
    init {
        require(serverName.isNotBlank()) { "serverName must not be blank." }
        require(serverVersion.isNotBlank()) { "serverVersion must not be blank." }
        require(maxArchiveBytes > 0) { "maxArchiveBytes must be greater than zero." }
        require(httpHost.isNotBlank()) { "httpHost must not be blank." }
        require(httpPort in 0..65535) { "httpPort must be between 0 and 65535." }
        require(maxHttpRequestBytes > 0) { "maxHttpRequestBytes must be greater than zero." }
    }
}

