package net.evrt.acrab.mcp

import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import net.evrt.acrab.core.AcrabConfig
import java.net.URI
import java.security.MessageDigest

internal class HttpMcpSecurity(private val config: AcrabConfig) {
    private val hosts = config.httpAllowedHosts.map { it.lowercase().removeSurrounding("[", "]") }.toSet()
    private val origins = config.httpAllowedOrigins.map { origin(it) ?: error("Invalid allowed HTTP origin: $it") }.toSet()

    fun validate(call: ApplicationCall, authenticate: Boolean = true): HttpStatusCode? {
        val hostHeaders = call.request.headers.getAll(HttpHeaders.Host)
        val host = hostHeaders?.singleOrNull()?.let { authority(it) }
        if (host == null || host.host.lowercase().removeSurrounding("[", "]") !in hosts) return HttpStatusCode.Forbidden
        val originHeaders = call.request.headers.getAll(HttpHeaders.Origin)
        if (originHeaders != null) {
            val value = originHeaders.singleOrNull() ?: return HttpStatusCode.Forbidden
            val parsed = origin(value) ?: return HttpStatusCode.Forbidden
            if (parsed !in origins && parsed != origin("http://${host.rawAuthority}")) return HttpStatusCode.Forbidden
            call.response.header(HttpHeaders.AccessControlAllowOrigin, value)
            call.response.header(HttpHeaders.Vary, "Origin")
            call.response.header(HttpHeaders.AccessControlExposeHeaders, "Mcp-Session-Id, MCP-Protocol-Version")
        }
        val token = config.httpBearerToken
        if (authenticate && token != null) {
            val credentials = call.request.headers.getAll(HttpHeaders.Authorization)?.singleOrNull()
            val parts = credentials?.split(' ', limit = 2)
            val supplied = parts?.takeIf { it.size == 2 && it[0].equals("Bearer", true) }?.get(1)
            if (supplied == null || !MessageDigest.isEqual(token.toByteArray(Charsets.UTF_8), supplied.toByteArray(Charsets.UTF_8))) {
                call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"acrab\"")
                return HttpStatusCode.Unauthorized
            }
        }
        return null
    }

    private fun authority(value: String): URI? = runCatching { URI("http://$value") }.getOrNull()?.takeIf {
        it.host != null && it.rawUserInfo == null && it.rawPath.isNullOrEmpty() && it.rawQuery == null && it.rawFragment == null &&
            (it.port == -1 || it.port in 1..65535)
    }

    private fun origin(value: String): String? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("http", "https") || uri.host == null || uri.rawUserInfo != null ||
            !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null ||
            (uri.port != -1 && uri.port !in 1..65535)) return null
        val port = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
        return "${uri.scheme}://${uri.host.lowercase()}:$port"
    }
}
