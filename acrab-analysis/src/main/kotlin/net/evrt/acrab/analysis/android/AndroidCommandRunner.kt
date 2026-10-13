package net.evrt.acrab.analysis.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

internal class AndroidCommandRunner(
    private val toolRoot: Path? = System.getenv("ACRAB_ANDROID_TOOL_ROOT")?.takeIf(String::isNotBlank)?.let(Path::of),
    private val pathFallbackRoot: Path? = System.getenv("ACRAB_ANDROID_PATH_ROOT")?.takeIf(String::isNotBlank)?.let(Path::of),
    private val processLimiter: Semaphore = Semaphore(2),
) {
    suspend fun run(executable: String, arguments: List<String>, workingDirectory: Path, timeoutMs: Long = DEFAULT_TIMEOUT): AndroidToolCommandOutput {
        return processLimiter.withPermit {
            runWithPermit(executable, arguments, workingDirectory, timeoutMs)
        }
    }

    private suspend fun runWithPermit(executable: String, arguments: List<String>, workingDirectory: Path, timeoutMs: Long): AndroidToolCommandOutput {
        val resolved = locate(executable)
            ?: return AndroidToolCommandOutput(false, executable, arguments, "", "Executable is unavailable or not allowlisted: $executable", null, 0, false)
        val working = workingDirectory.toAbsolutePath().normalize()
        require(Files.isDirectory(working)) { "Working directory does not exist." }
        validateBatchArguments(resolved, arguments)?.let { message ->
            return AndroidToolCommandOutput(false, executable, arguments, "", message, null, 0, false)
        }
        val process = runCatching { ProcessBuilder(listOf(resolved) + arguments).directory(working.toFile()).start() }
            .getOrElse { return AndroidToolCommandOutput(false, executable, arguments, "", it.message.orEmpty(), null, 0, false) }
        val started = System.nanoTime()
        return try {
            coroutineScope {
                val out = async(Dispatchers.IO) { capture(process.inputStream) }
                val err = async(Dispatchers.IO) { capture(process.errorStream) }
                val exit = try {
                    withTimeout(timeoutMs) { runInterruptible(Dispatchers.IO) { process.waitFor() } }
                } catch (_: TimeoutCancellationException) {
                    terminate(process)
                    return@coroutineScope AndroidToolCommandOutput(false, executable, arguments, preview(out.await()), preview(err.await()), null, elapsed(started), true)
                }
                AndroidToolCommandOutput(exit == 0, executable, arguments, preview(out.await()), preview(err.await()), exit, elapsed(started), false)
            }
        } finally {
            if (process.isAlive) terminate(process)
        }
    }

    fun locate(executable: String): String? {
        if (executable !in ALLOWED_EXECUTABLES) return null
        if (executable != executable.substringAfterLast('/').substringAfterLast('\\')) return null
        if (executable.endsWith(".bat", true) || executable.endsWith(".cmd", true) || executable.endsWith(".exe", true)) return null
        val names = executableNames(executable)
        toolRoot?.let { root ->
            locateUnder(root, names)?.let { return it.toString() }
        }
        pathFallbackRoot?.let { root ->
            return locateUnder(root, names)?.toString()
        }
        return null
    }

    private fun locateUnder(root: Path, names: List<String>): Path? {
        val normalizedRoot = root.toAbsolutePath().normalize()
        if (!Files.isDirectory(normalizedRoot)) return null
        return names.asSequence()
            .map { normalizedRoot.resolve(it).normalize() }
            .firstOrNull { candidate ->
                candidate.startsWith(normalizedRoot) && Files.isRegularFile(candidate) && Files.isExecutable(candidate) &&
                    candidate.toRealPath().startsWith(normalizedRoot.toRealPath())
            }
    }

    private fun executableNames(executable: String): List<String> =
        if (System.getProperty("os.name").contains("Windows", true)) {
            listOf(executable, "$executable.exe", "$executable.bat", "$executable.cmd")
        } else {
            listOf(executable)
        }

    private fun validateBatchArguments(resolved: String, arguments: List<String>): String? {
        val isBatch = resolved.endsWith(".bat", true) || resolved.endsWith(".cmd", true)
        if (arguments.any(::hasUnsafeControlCharacter)) return "Android tool arguments must not contain NUL, CR, or LF characters."
        if (!isBatch) return null
        if (resolved.any(::isShellMetacharacter)) return "Android batch tool paths must not contain shell metacharacters."
        if (arguments.any { argument -> argument.any(::isShellMetacharacter) }) return "Android batch tool arguments must not contain shell metacharacters."
        return null
    }

    private fun hasUnsafeControlCharacter(value: String): Boolean =
        value.any { it == '\u0000' || it == '\r' || it == '\n' }

    private fun isShellMetacharacter(character: Char): Boolean =
        character == '&' || character == '|' || character == '<' || character == '>' ||
            character == '^' || character == '%' || character == '!' || character == '"'

    private fun preview(bytes: ByteArray): String = bytes.copyOf(minOf(bytes.size, MAX_OUTPUT)).toString(StandardCharsets.UTF_8)

    // Drain both pipes completely, retaining only the advertised preview budget.
    private fun capture(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() < MAX_OUTPUT) output.write(buffer, 0, minOf(count, MAX_OUTPUT - output.size()))
        }
        return output.toByteArray()
    }

    private fun terminate(process: Process) {
        process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
        process.destroy()
        if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
    }

    private fun elapsed(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    companion object {
        private const val DEFAULT_TIMEOUT = 120_000L
        private const val MAX_OUTPUT = 64 * 1024
        val ALLOWED_EXECUTABLES = setOf("d2j-dex2jar", "dex2jar", "baksmali", "smali", "d8", "dx", "jasmin", "r8")
    }
}
