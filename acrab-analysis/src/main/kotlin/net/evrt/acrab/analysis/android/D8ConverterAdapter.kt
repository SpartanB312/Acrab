package net.evrt.acrab.analysis.android

import net.evrt.acrab.analysis.index.ProjectRecord
import org.objectweb.asm.ClassReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile

internal data class D8ConversionOptions(
    val minApi: Int = 21,
    val libraries: List<Path> = emptyList(),
    val classpath: List<Path> = emptyList(),
    val expectedToolVersion: String? = null,
) {
    init {
        require(minApi > 0) { "minApi must be positive." }
    }

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): D8ConversionOptions {
            fun paths(name: String): List<Path> = environment[name]?.takeIf(String::isNotBlank)
                ?.split(java.io.File.pathSeparator)?.map(Path::of).orEmpty()
            return D8ConversionOptions(
                minApi = environment["ACRAB_ANDROID_MIN_API"]?.toInt() ?: 21,
                libraries = paths("ACRAB_ANDROID_LIBRARIES"),
                classpath = paths("ACRAB_ANDROID_CLASSPATH"),
                expectedToolVersion = environment["ACRAB_ANDROID_D8_VERSION"]?.takeIf(String::isNotBlank),
            )
        }
    }
}

internal data class D8ConversionOutput(
    val command: AndroidToolCommandOutput,
    val bytes: ByteArray? = null,
    val format: String? = null,
    val dexFiles: List<String> = emptyList(),
    val classCount: Int = 0,
    val methodCount: Int = 0,
    val toolVersion: String? = null,
    val warnings: List<String> = emptyList(),
)

internal class D8ConverterAdapter(
    private val options: D8ConversionOptions,
    private val invoke: suspend (String, List<String>, Path) -> AndroidToolCommandOutput,
) {
    fun arguments(executable: String, input: Path, output: Path): List<String> {
        require(executable == "d8" || executable == "dx") { "Unsupported DEX converter." }
        (options.libraries + options.classpath).forEach { path ->
            require(Files.isRegularFile(path) || Files.isDirectory(path)) { "Configured Android dependency is missing: $path" }
        }
        if (executable == "dx") {
            require(options.libraries.isEmpty() && options.classpath.isEmpty()) {
                "Legacy dx does not support the configured D8 library/classpath profile."
            }
            return listOf("--dex", "--min-sdk-version=${options.minApi}", "--output=${output.resolve("classes.dex")}", input.toString())
        }
        return buildList {
            addAll(listOf("--release", "--min-api", options.minApi.toString(), "--output", output.toString()))
            options.libraries.forEach { addAll(listOf("--lib", it.toAbsolutePath().normalize().toString())) }
            options.classpath.forEach { addAll(listOf("--classpath", it.toAbsolutePath().normalize().toString())) }
            add(input.toString())
        }
    }

    suspend fun convert(project: ProjectRecord, executable: String, output: Path): D8ConversionOutput {
        require(project.inputType in setOf("jar", "war", "ear")) { "jar2dex expects a JVM archive." }
        val arguments = arguments(executable, Path.of(project.archivePath), output)
        val inputClasses = inputClassNames(Path.of(project.archivePath))
        val versionProbe = invoke(executable, listOf("--version"), output)
        val version = Regex("(?i)\\b$executable\\s+(?:version\\s+)?([a-z0-9._-]+)")
            .find(versionProbe.stdout)?.groupValues?.get(1)
        if (!versionProbe.completed || versionProbe.exitCode != 0 || versionProbe.timedOut || version == null) {
            return D8ConversionOutput(versionProbe, warnings = listOf("Converter version probe failed; no artifact is registered."))
        }
        if (options.expectedToolVersion != null && version != options.expectedToolVersion) {
            return D8ConversionOutput(versionProbe, toolVersion = version, warnings = listOf("Converter version does not match the configured version; no artifact is registered."))
        }
        val command = invoke(executable, arguments, output)
        if (!command.completed || command.exitCode != 0 || command.timedOut) return D8ConversionOutput(command, toolVersion = version, warnings = listOf("Converter did not complete; partial output is not registered."))
        return try {
            validateOutput(inputClasses, output, command, version)
        } catch (error: IllegalArgumentException) {
            D8ConversionOutput(command, toolVersion = version, warnings = listOf("DEX output validation failed: ${error.message}"))
        } catch (error: IOException) {
            D8ConversionOutput(command, toolVersion = version, warnings = listOf("DEX output could not be read: ${error.message}"))
        }
    }

    private fun validateOutput(inputClasses: Set<String>, output: Path, command: AndroidToolCommandOutput, version: String): D8ConversionOutput {
        val paths = Files.list(output).use { files ->
            files.filter { DEX_NAME.matches(it.fileName.toString()) }.limit(MAX_DEX_FILES + 1L).toList().sortedBy { path ->
                val suffix = DEX_NAME.matchEntire(path.fileName.toString())!!.groupValues[1]
                if (suffix.isEmpty()) 1 else suffix.toIntOrNull() ?: throw IllegalArgumentException("Invalid DEX output ordinal.")
            }
        }
        require(paths.isNotEmpty() && paths.size <= MAX_DEX_FILES) { "Converter produced no DEX files or exceeded the output count limit." }
        var bytes = 0L
        val entries = paths.mapIndexed { index, path ->
            val expectedName = if (index == 0) "classes.dex" else "classes${index + 1}.dex"
            require(path.fileName.toString() == expectedName) { "DEX output sequence is incomplete." }
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "DEX output must be a regular non-symlink file." }
            bytes += Files.size(path)
            require(bytes <= MAX_TOTAL_OUTPUT_BYTES) { "DEX output exceeds the aggregate byte limit." }
            require(Files.size(path) <= DexArchiveReader.MAX_DEX_BYTES) { "DEX output exceeds the per-file byte limit." }
            val data = Files.newInputStream(path).use { it.readNBytes(DexArchiveReader.MAX_DEX_BYTES + 1) }
            require(data.size <= DexArchiveReader.MAX_DEX_BYTES) { "DEX output exceeds the per-file byte limit." }
            expectedName to data
        }
        require(entries.sumOf { it.second.size.toLong() } <= MAX_TOTAL_OUTPUT_BYTES) { "DEX output exceeds the aggregate byte limit." }
        val models = DexArchiveReader().readDexFiles(entries).files
        val classes = models.sumOf { it.classes.size }
        val methods = models.sumOf { it.methods.size }
        require(classes > 0) { "Converter produced an empty DEX class inventory." }
        val names = models.flatMap { it.classes }.map { it.className }
        require(names.distinct().size == names.size) { "Converter produced duplicate classes across DEX outputs." }
        require(names.toSet().containsAll(inputClasses)) {
            "Converter output is missing input program classes."
        }
        val artifact = if (models.size == 1) models.single().bytes else {
            val buffer = ByteArrayOutputStream()
            ZipOutputStream(buffer).use { zip ->
                models.forEach { model ->
                    zip.putNextEntry(ZipEntry(model.name).apply { time = 0L })
                    zip.write(model.bytes)
                    zip.closeEntry()
                }
            }
            buffer.toByteArray()
        }
        require(artifact.size <= MAX_TOTAL_OUTPUT_BYTES) { "DEX artifact exceeds the byte limit." }
        return D8ConversionOutput(command, artifact, if (models.size == 1) "dex" else "multidex-zip", models.map { it.name }, classes, methods, version)
    }

    private fun inputClassNames(input: Path): Set<String> {
        require(Files.isRegularFile(input) && Files.size(input) <= MAX_TOTAL_OUTPUT_BYTES) { "JVM archive exceeds the byte limit or is not a regular file." }
        return ZipFile(input.toFile()).use { zip ->
            val entries = zip.entries().asSequence()
                .take(DexArchiveReader.MAX_ENTRY_COUNT + 1).toList()
            require(entries.size <= DexArchiveReader.MAX_ENTRY_COUNT) { "JVM archive exceeds the entry limit." }
            require(entries.all { it.size in 0..MAX_TOTAL_OUTPUT_BYTES } && entries.sumOf { it.size } <= MAX_TOTAL_OUTPUT_BYTES) {
                "JVM archive exceeds the uncompressed byte limit."
            }
            var total = 0L
            val names = entries.filter { !it.isDirectory && it.name.endsWith(".class") }.map { entry ->
                require(entry.size in 0..MAX_CLASS_BYTES) { "Input class exceeds the byte limit." }
                val bytes = zip.getInputStream(entry).use { it.readNBytes(MAX_CLASS_BYTES + 1) }
                require(bytes.size <= MAX_CLASS_BYTES) { "Input class exceeds the byte limit." }
                total += bytes.size
                require(total <= MAX_TOTAL_OUTPUT_BYTES) { "Input classes exceed the aggregate byte limit." }
                try {
                    ClassReader(bytes).className.replace('/', '.')
                } catch (error: IndexOutOfBoundsException) {
                    throw IllegalArgumentException("Input class is malformed.", error)
                }
            }
            require(names.isNotEmpty()) { "Input archive contains no program classes." }
            require(names.distinct().size == names.size) { "Input archive contains duplicate program classes." }
            names.toSet()
        }
    }

    companion object {
        private val DEX_NAME = Regex("classes(\\d*)\\.dex")
        private const val MAX_DEX_FILES = 128
        private const val MAX_CLASS_BYTES = 32 * 1024 * 1024
        private const val MAX_TOTAL_OUTPUT_BYTES = 256L * 1024 * 1024
    }
}
