package net.evrt.acrab.analysis.decompiler

import net.evrt.acrab.analysis.index.JvmIndexService
import net.evrt.acrab.analysis.index.ProjectRepository
import org.benf.cfr.reader.api.CfrDriver
import org.benf.cfr.reader.api.OutputSinkFactory
import org.benf.cfr.reader.api.SinkReturns
import org.jetbrains.java.decompiler.api.Decompiler
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile
import kotlin.system.measureTimeMillis

class DecompilerService(
    private val projects: ProjectRepository,
    private val indexes: JvmIndexService,
) {
    private val cache = ConcurrentHashMap<String, DecompileResult>()
    private val vineflowerLock = Any()

    fun backendInfo(backend: String = CfrBackendId): DecompilerBackendInfo = backend(backend).info

    fun backendIds(): List<String> = listOf(CfrBackendId, VineflowerBackendId)

    private fun backend(backend: String): Backend =
        when (backend.trim().lowercase()) {
            CfrBackendId -> cfr
            VineflowerBackendId -> vineflower
            else -> throw IllegalArgumentException("Unknown decompiler backend: $backend")
        }

    fun decompileClass(
        projectId: String,
        className: String,
        backend: String = CfrBackendId,
    ): DecompileResult {
        val selected = backend(backend)
        val project = projects.require(projectId)
        val classInfo = indexes.classSummary(projectId, className)
        val key = "${project.sha256}:${classInfo.internalName}:${selected.id}:${selected.info.version}:class"
        cache[key]?.let { return it }

        val warnings = mutableListOf<String>()
        val failedRegions = mutableListOf<String>()
        val sink = when (selected) {
            cfr -> CfrSinkFactory(warnings, failedRegions)
            vineflower -> VineflowerSinkFactory(warnings, failedRegions)
            else -> throw IllegalArgumentException("Unsupported backend: ${selected.id}")
        }
        val elapsed = measureTimeMillis {
            withExtractedClass(project.archivePath, classInfo.internalName) { classFile ->
                when (selected) {
                    cfr -> (sink as CfrSinkFactory).run {
                        CfrDriver.Builder()
                            .withOptions(
                                mapOf(
                                    "silent" to "true",
                                    "comments" to "false",
                                    "hideutf" to "false",
                                ),
                            )
                            .withOutputSink(this)
                            .build()
                            .analyse(listOf(classFile.toAbsolutePath().toString()))
                    }
                    vineflower -> synchronized(vineflowerLock) {
                        val vineflowerSink = sink as VineflowerSinkFactory
                        Decompiler.builder()
                            .inputs(classFile.toFile())
                            .output(vineflowerSink)
                            .logger(CapturingFernflowerLogger(warnings, failedRegions))
                            .option(IFernflowerPreferences.INCLUDE_JAVA_RUNTIME, "0")
                            .option(IFernflowerPreferences.LOG_LEVEL, "warn")
                            .option(IFernflowerPreferences.REMOVE_SYNTHETIC, "0")
                            .build()
                            .decompile()
                    }
                }
            }
        }

        val source = when (sink) {
            is CfrSinkFactory -> sink.sources.singleOrNull().orEmpty()
            is VineflowerSinkFactory -> sink.sourceFor(classInfo.internalName)
                ?: sink.sources.values.firstOrNull()
                ?: ""
            else -> ""
        }
        if (source.isBlank()) {
            failedRegions += "${selected.displayName} did not return source for $className."
        }
        failedRegions += inspectJavaSource(source, classInfo.name,
            indexes.ensureIndex(projectId).classes.map { it.name }.toSet()).failures
        val result = DecompileResult(
            backend = selected.id,
            backendVersion = selected.info.version,
            projectId = projectId,
            className = classInfo.name,
            inputSha256 = project.sha256,
            scope = "class",
            source = source,
            warnings = warnings.distinct(),
            failedRegions = failedRegions.distinct(),
            confidence = if (failedRegions.isNotEmpty()) "low" else "medium",
            elapsedMs = elapsed,
        )
        if (result.failedRegions.isEmpty()) cache[key] = result
        return result
    }

    fun decompileMethod(
        projectId: String,
        className: String,
        methodName: String,
        methodDescriptor: String? = null,
        backend: String = CfrBackendId,
    ): DecompileResult {
        // Resolve against the class file first. A descriptor that matches no method, or a name-only
        // request for an overloaded name, must fail here instead of returning whatever the
        // syntax-based extractor finds first.
        val resolved = resolveMethod(projectId, className, methodName, methodDescriptor)
        val classResult = decompileClass(projectId, className, backend)
        val inspection = inspectJavaSource(classResult.source, className,
            indexes.ensureIndex(projectId).classes.map { it.name }.toSet())
        val matches = inspection.methods.filter { it.name == methodName && it.descriptor == resolved.descriptor }
        val extraction = matches.singleOrNull()
        val exact = extraction != null
        val extractionFailure = if (exact) emptyList() else listOf(
            "Could not uniquely extract $className.$methodName${resolved.descriptor}: " +
                "${matches.size} complete signature matches. Returning full class source.",
        )
        return classResult.copy(
            scope = "method",
            methodName = methodName,
            methodDescriptor = resolved.descriptor,
            requestedDescriptor = methodDescriptor?.trim()?.takeIf { it.isNotEmpty() },
            methodMatchKind = resolved.matchKind.name,
            emittedMethodName = extraction?.name,
            emittedDescriptor = extraction?.descriptor,
            emittedDeclaration = extraction?.declaration,
            emittedParameterCount = extraction?.parameterCount,
            source = extraction?.source ?: classResult.source,
            warnings = classResult.warnings + extractionFailure +
                "Method signatures are checked against parsed Java declarations; bodies remain reconstructed.",
            failedRegions = (classResult.failedRegions + extractionFailure).distinct(),
            exactMethodMatch = exact,
            confidence = if (exact) classResult.confidence else "low",
        )
    }

    private fun resolveMethod(
        projectId: String,
        className: String,
        methodName: String,
        methodDescriptor: String?,
    ): net.evrt.acrab.analysis.support.ResolvedMethod {
        val project = projects.require(projectId)
        val classInfo = indexes.classSummary(projectId, className)
        val classNode = org.objectweb.asm.tree.ClassNode()
        withExtractedClass(project.archivePath, classInfo.internalName) { classFile ->
            org.objectweb.asm.ClassReader(Files.readAllBytes(classFile))
                .accept(classNode, org.objectweb.asm.ClassReader.SKIP_FRAMES)
        }
        return net.evrt.acrab.analysis.support.MethodSelector.resolve(
            classNode,
            methodName,
            methodDescriptor,
        )
    }

    private fun withExtractedClass(archivePath: String, internalName: String, block: (Path) -> Unit) {
        val temporaryDirectory = Files.createTempDirectory("acrab-decompile-")
        try {
            val classFile = temporaryDirectory.resolve("$internalName.class")
            classFile.parent?.let(Files::createDirectories)
            JarFile(Path.of(archivePath).toFile()).use { jar ->
                val entryName = "$internalName.class"
                val entry = jar.getJarEntry(entryName)
                    ?: entries(jar.entries()).firstOrNull { it.name.replace('\\', '/') == entryName }
                    ?: throw IllegalArgumentException("Class file not found in archive: $entryName")
                copyBounded(jar.getInputStream(entry), classFile)
            }
            block(classFile)
        } finally {
            Files.walk(temporaryDirectory)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach { path -> Files.deleteIfExists(path) }
        }
    }

    private fun copyBounded(input: InputStream, target: Path) {
        input.use {
            var total = 0L
            Files.newOutputStream(
                target,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE,
            ).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_ENTRY_BYTES) { "Class entry exceeds the decompiler input limit." }
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    private fun entries(enumeration: Enumeration<java.util.jar.JarEntry>): Sequence<java.util.jar.JarEntry> =
        generateSequence {
            if (enumeration.hasMoreElements()) enumeration.nextElement() else null
        }


    companion object {
        private const val MAX_ENTRY_BYTES = 128L * 1024 * 1024
        private const val CfrBackendId = "cfr"
        private const val VineflowerBackendId = "vineflower"

        private val cfr = Backend(
            id = CfrBackendId,
            displayName = "CFR",
            version = CfrDriver::class.java.`package`?.implementationVersion ?: "0.152",
        )

        private val vineflower = Backend(
            id = VineflowerBackendId,
            displayName = "Vineflower",
            version = runCatching { ConsoleDecompiler.version() }.getOrDefault("1.12.0"),
        )
    }
}


private data class Backend(
    val id: String,
    val displayName: String,
    val version: String,
) {
    val info: DecompilerBackendInfo
        get() = DecompilerBackendInfo(
            id = id,
            displayName = displayName,
            version = version,
            status = "available",
            notes = "Decompiler output is reconstructed and can be wrong under obfuscation; verify important claims with bytecode.",
        )
}

private class CapturingFernflowerLogger(
    private val warnings: MutableList<String>,
    private val failedRegions: MutableList<String>,
) : IFernflowerLogger() {
    override fun writeMessage(message: String, severity: IFernflowerLogger.Severity) {
        if (severity >= IFernflowerLogger.Severity.WARN) {
            warnings += "$severity: $message"
            if (severity >= IFernflowerLogger.Severity.ERROR ||
                message.contains("could not", ignoreCase = true) || message.contains("failed", ignoreCase = true)) {
                failedRegions += "$severity: $message"
            }
        }
    }

    override fun writeMessage(
        message: String,
        severity: IFernflowerLogger.Severity,
        throwable: Throwable,
    ) {
        if (severity >= IFernflowerLogger.Severity.WARN) {
            warnings += "$severity: $message: ${throwable.message}"
            failedRegions += "$severity: $message: ${throwable.message}"
        }
    }

}

private class CfrSinkFactory(
    private val warnings: MutableList<String>,
    private val failedRegions: MutableList<String>,
) : OutputSinkFactory {
    val sources = mutableListOf<String>()

    override fun getSupportedSinks(
        sinkType: OutputSinkFactory.SinkType,
        available: MutableCollection<OutputSinkFactory.SinkClass>,
    ): MutableList<OutputSinkFactory.SinkClass> = mutableListOf(
        OutputSinkFactory.SinkClass.DECOMPILED,
        OutputSinkFactory.SinkClass.EXCEPTION_MESSAGE,
        OutputSinkFactory.SinkClass.STRING,
    )

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> getSink(
        sinkType: OutputSinkFactory.SinkType,
        sinkClass: OutputSinkFactory.SinkClass,
    ): OutputSinkFactory.Sink<T> {
        val sink = OutputSinkFactory.Sink<Any> { value ->
            when (value) {
            is SinkReturns.Decompiled -> sources += value.java
            is SinkReturns.ExceptionMessage -> failedRegions += "${value.path}: ${value.message}"
            is String -> if (sinkType != OutputSinkFactory.SinkType.PROGRESS) warnings += value
            else -> if (value != null) warnings += value.toString()
        }
        }
        @Suppress("UNCHECKED_CAST")
        return sink as OutputSinkFactory.Sink<T>
    }
}
