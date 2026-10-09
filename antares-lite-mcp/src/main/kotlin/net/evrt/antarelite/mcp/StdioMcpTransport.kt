package net.evrt.antarelite.mcp

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import net.evrt.antarelite.core.LiteContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.io.Reader
import java.io.Writer

/** Runs one Lite MCP session over newline-delimited JSON-RPC 2.0. */
class StdioMcpTransport(
    context: LiteContext,
    input: InputStream,
    output: OutputStream,
) : AutoCloseable {
    private val reader: Reader = BufferedReader(input.reader(Charsets.UTF_8))
    private val writer: Writer = BufferedWriter(output.writer(Charsets.UTF_8))
    private val responses = PrintWriter(writer, false)
    private val protocol = McpProtocol(context)

    fun serve() {
        while (serveOne()) {
            // Continue until stdin reaches EOF.
        }
    }

    override fun close() {
        responses.flush()
        reader.close()
        writer.close()
    }

    private fun serveOne(): Boolean {
        val line = (reader as? BufferedReader)?.readLine() ?: return false
        if (line.isBlank()) return true

        val request = try {
            protocol.parseLine(line)
        } catch (failure: JsonRpcException) {
            write(protocol.errorResponse(null, failure.code, failure.message ?: "Invalid request."))
            return true
        } catch (failure: Exception) {
            write(protocol.errorResponse(null, ParseErrorCode, "Parse error"))
            return true
        }

        try {
            val result = protocol.dispatch(request)
            if (!request.isNotification) {
                write(protocol.successResponse(request.id ?: JsonNull, result))
            }
        } catch (failure: JsonRpcException) {
            if (!request.isNotification) {
                write(protocol.errorResponse(request.id, failure.code, failure.message ?: "Invalid request."))
            }
        } catch (failure: Exception) {
            if (!request.isNotification) {
                write(protocol.errorResponse(request.id, InternalErrorCode, failure.message ?: "Internal error."))
            }
        }
        return true
    }

    private fun write(value: JsonObject) {
        responses.println(protocol.encode(value))
        responses.flush()
    }
}
