package net.evrt.acrab.analysis.android

import net.evrt.acrab.analysis.index.ProjectRecord
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.BasicVerifier
import org.objectweb.asm.util.CheckClassAdapter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipFile

internal data class Dex2jarConversionOutput(
    val command: AndroidToolCommandOutput,
    val bytes: ByteArray? = null,
    val sourceDexFiles: List<String> = emptyList(),
    val classCount: Int = 0,
    val methodCount: Int = 0,
    val fieldCount: Int = 0,
    val toolVersion: String? = null,
    val commands: List<AndroidToolCommandOutput> = emptyList(),
    val warnings: List<String> = emptyList(),
    val dexClassCount: Int = 0,
)

/**
 * Runs the real dex2jar CLI once per validated DEX entry and merges the validated outputs.
 *
 * dex2jar's `-n` mode can exit 0 with partial output, so success is decided by structural and
 * inventory validation of the produced JAR, never by the process exit code alone.
 */
internal class Dex2jarConverterAdapter(
    private val expectedToolVersion: String = DEFAULT_TOOL_VERSION,
    private val maxOutputBytes: Long = MAX_TOTAL_OUTPUT_BYTES,
    private val maxOutputEntries: Int = MAX_ARCHIVE_ENTRIES,
    private val invoke: suspend (String, List<String>, Path) -> AndroidToolCommandOutput,
) {
    init {
        require(maxOutputBytes in 1..MAX_TOTAL_OUTPUT_BYTES)
        require(maxOutputEntries in 1..MAX_ARCHIVE_ENTRIES)
    }

    suspend fun convert(project: ProjectRecord, executable: String, work: Path): Dex2jarConversionOutput {
        require(executable == TOOL_ID || executable == LEGACY_TOOL_ID) {
            "Unsupported dex2jar executable identifier: $executable"
        }
        require(project.inputType.lowercase() in INPUT_TYPES) {
            "dex2jar requires a DEX, APK, or AAB input."
        }
        val normalizedWork = work.toAbsolutePath().normalize()
        require(Files.isDirectory(normalizedWork, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(normalizedWork)) { "dex2jar working directory is unavailable or linked." }
        require(normalizedWork.toRealPath() == normalizedWork) { "dex2jar working directory has a linked ancestor." }

        val input = Path.of(project.archivePath).toAbsolutePath().normalize()
        require(Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(input)) {
            "dex2jar input is not a regular non-symlink file."
        }
        val inputSha256 = sha256(input)
        if (project.sha256.isNotBlank() && !project.sha256.equals(inputSha256, ignoreCase = true)) {
            return failure("Input checksum does not match the project record.")
        }

        val probe = invoke(executable, listOf("--help"), work)
        val identity = parseIdentity(probe.stdout, probe.stderr)
        if (!probe.completed || probe.exitCode != 0 || probe.timedOut || identity == null) {
            return failure("dex2jar --help probe failed; no artifact is produced.", probe, listOf(probe))
        }
        if (!identity.matches(expectedToolVersion)) {
            return failure("Unsupported dex2jar identity ${identity.render()}.", probe, listOf(probe))
        }

        val dexArchive = try {
            DexArchiveReader().read(project)
        } catch (error: IOException) {
            return failure("Input archive could not be read: ${error.message}", validationFailure(executable), listOf(probe))
        } catch (error: IllegalArgumentException) {
            return failure("Input archive failed validation: ${error.message}", validationFailure(executable), listOf(probe))
        } catch (error: IndexOutOfBoundsException) {
            return failure("Input archive failed validation: ${error.message}", validationFailure(executable), listOf(probe))
        }

        val totalSourceClasses = dexArchive.files.sumOf { it.classes.size }
        val sourceDexFilesAll = dexArchive.files.map { it.name }
        val sourceClasses = linkedSetOf<String>()
        dexArchive.files.forEach { dexFile ->
            dexFile.classes.forEach { dexClass ->
                if (!sourceClasses.add(dexClass.className)) {
                    return failure(
                        "Cross-DEX duplicate class cannot be merged into one JVM class: ${dexClass.className}.",
                        validationFailure(executable),
                        listOf(probe),
                        sourceDexFilesAll,
                        totalSourceClasses,
                    )
                }
            }
        }

        val resources = try {
            readResources(project, input, dexArchive.files.map { it.name }.toSet())
        } catch (error: IOException) {
            return failure("Input resources could not be read: ${error.message}", validationFailure(executable), listOf(probe))
        } catch (error: IllegalArgumentException) {
            return failure("Input resources failed validation: ${error.message}", validationFailure(executable), listOf(probe))
        } catch (error: IndexOutOfBoundsException) {
            return failure("Input resources failed validation: malformed class data (${error.message}).", validationFailure(executable), listOf(probe))
        }

        val stageRoot = Files.createTempDirectory(normalizedWork, "dex2jar-stages-")
        val commandOutputs = mutableListOf(probe)
        val sourceDexFiles = mutableListOf<String>()
        val outputClasses = linkedMapOf<String, ByteArray>().apply { putAll(resources.files.filterKeys { it.endsWith(".class") }) }
        val outputResources = linkedMapOf<String, ByteArray>().apply { putAll(resources.files.filterKeys { !it.endsWith(".class") }) }
        val warnings = mutableListOf<String>().apply { addAll(resources.warnings) }
        val manifestBytes = outputResources[MANIFEST_ENTRY] ?: generatedManifestBytes()
        val budget = try {
            MergeBudget(
                bytes = resources.files.entries.filter { it.key != MANIFEST_ENTRY }.sumOf { it.value.size.toLong() } + manifestBytes.size,
                entries = 1 + resources.files.count { it.key != MANIFEST_ENTRY },
                byteLimit = maxOutputBytes,
                entryLimit = maxOutputEntries,
            )
        } catch (error: IllegalArgumentException) {
            return failure("Input resources failed validation: ${error.message}", validationFailure(executable), listOf(probe), sourceDexFilesAll, totalSourceClasses, expectedToolVersion)
        }
        var outputMethods = resources.methodCount
        var outputFields = resources.fieldCount

        dexArchive.files.forEachIndexed { index, dexFile ->
            val stage = Files.createDirectory(stageRoot.resolve(index.toString()))
            val stagedDex = stage.resolve("classes.dex")
            val outputJar = stage.resolve("classes.jar")
            val errorJar = stage.resolve("classes-error.zip")
            Files.newOutputStream(stagedDex, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { it.write(dexFile.bytes) }
            Files.deleteIfExists(outputJar)
            Files.deleteIfExists(errorJar)

            val command = invoke(executable, arguments(stagedDex, outputJar, errorJar), work)
            commandOutputs += command
            sourceDexFiles += dexFile.name
            if (!command.completed || command.exitCode != 0 || command.timedOut) {
                return failure(
                    "dex2jar did not complete for source DEX ${dexFile.name}.",
                    command,
                    commandOutputs.toList(),
                    sourceDexFiles.toList(),
                    totalSourceClasses,
                    expectedToolVersion,
                )
            }
            if (Files.exists(errorJar, LinkOption.NOFOLLOW_LINKS)) {
                return failure(
                    "dex2jar reported translation errors for source DEX ${dexFile.name}.",
                    command,
                    commandOutputs.toList(),
                    sourceDexFiles.toList(),
                    totalSourceClasses,
                    expectedToolVersion,
                )
            }
            if (containsFailureMarker(command.stdout) || containsFailureMarker(command.stderr)) {
                return failure(
                    "dex2jar reported a translation failure for source DEX ${dexFile.name}.",
                    command,
                    commandOutputs.toList(),
                    sourceDexFiles.toList(),
                    totalSourceClasses,
                    expectedToolVersion,
                )
            }
            if (!Files.isRegularFile(outputJar, LinkOption.NOFOLLOW_LINKS)) {
                return failure(
                    "dex2jar did not produce a JAR for source DEX ${dexFile.name}.",
                    command,
                    commandOutputs.toList(),
                    sourceDexFiles.toList(),
                    totalSourceClasses,
                    expectedToolVersion,
                )
            }

            val dexOutput = try {
                validateJar(outputJar, dexFile, budget)
            } catch (error: IOException) {
                return failure(
                    "Output could not be read for source DEX ${dexFile.name}: ${error.message}",
                    command,
                    commandOutputs.toList(),
                    sourceDexFiles.toList(),
                    totalSourceClasses,
                    expectedToolVersion,
                )
            } catch (error: IllegalArgumentException) {
                return failure(
                    "Output failed validation for source DEX ${dexFile.name}: ${error.message}",
                    command,
                    commandOutputs.toList(),
                    sourceDexFiles.toList(),
                    totalSourceClasses,
                    expectedToolVersion,
                )
            } catch (error: IndexOutOfBoundsException) {
                return failure(
                    "Output failed validation for source DEX ${dexFile.name}: ${error.message}",
                    command,
                    commandOutputs.toList(),
                    sourceDexFiles.toList(),
                    totalSourceClasses,
                    expectedToolVersion,
                )
            }
            dexOutput.classFiles.forEach { (name, bytes) ->
                if (outputClasses.put(name, bytes) != null) {
                    return failure(
                        "Duplicate output class across DEX stages: $name.",
                        command,
                        commandOutputs.toList(),
                        sourceDexFiles.toList(),
                        totalSourceClasses,
                        expectedToolVersion,
                    )
                }
            }
            dexOutput.resourceFiles.forEach { (name, bytes) ->
                if (outputResources.put(name, bytes) != null) {
                    return failure(
                        "Duplicate output resource across DEX stages: $name.",
                        command,
                        commandOutputs.toList(),
                        sourceDexFiles.toList(),
                        totalSourceClasses,
                        expectedToolVersion,
                    )
                }
            }
            outputMethods += dexOutput.methodCount
            outputFields += dexOutput.fieldCount
            warnings += dexOutput.warnings
        }

        val merged = try {
            mergeJar(outputClasses, outputResources, manifestBytes, budget, warnings)
        } catch (error: IOException) {
            return failure("Output could not be merged: ${error.message}", commandOutputs.last(), commandOutputs.toList(), sourceDexFiles.toList(), totalSourceClasses, expectedToolVersion)
        } catch (error: IllegalArgumentException) {
            return failure("Output failed merge validation: ${error.message}", commandOutputs.last(), commandOutputs.toList(), sourceDexFiles.toList(), totalSourceClasses, expectedToolVersion)
        }

        val inputUnchanged = try {
            Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(input) && sha256(input) == inputSha256
        } catch (_: IOException) {
            false
        }
        if (!inputUnchanged) {
            return failure(
                "dex2jar modified the input artifact.",
                commandOutputs.last(),
                commandOutputs.toList(),
                sourceDexFiles.toList(),
                totalSourceClasses,
                expectedToolVersion,
            )
        }

        return Dex2jarConversionOutput(
            command = commandOutputs.last(),
            bytes = merged.bytes,
            sourceDexFiles = sourceDexFiles.toList(),
            classCount = merged.classCount,
            methodCount = outputMethods,
            fieldCount = outputFields,
            toolVersion = expectedToolVersion,
            commands = commandOutputs.toList(),
            warnings = warnings.toList(),
            dexClassCount = totalSourceClasses,
        )
    }

    private fun arguments(dex: Path, output: Path, error: Path): List<String> = listOf(
        "-n",
        "-dsn",
        "-f",
        "-e",
        error.toString(),
        "-o",
        output.toString(),
        dex.toString(),
    )

    /**
     * Payload resources are preserved separately from DEX parsing. The DEX entries themselves are
     * read through [DexArchiveReader] so identification and shared budgets match the rest of the
     * Android pipeline; this pass only decides what to carry into the merged JAR.
     */
    private fun readResources(project: ProjectRecord, input: Path, dexNames: Set<String>): InputResources {
        if (project.inputType.equals("dex", ignoreCase = true) || input.fileName.toString().lowercase().endsWith(".dex")) {
            return InputResources(emptyMap(), emptyList())
        }
        val files = linkedMapOf<String, ByteArray>()
        val warnings = mutableListOf<String>()
        var totalBytes = 0L
        var methodCount = 0
        var fieldCount = 0
        ZipFile(input.toFile()).use { zip ->
            require(zip.size() <= MAX_ARCHIVE_ENTRIES) { "Archive entry count exceeds the $MAX_ARCHIVE_ENTRIES limit." }
            val entries = zip.entries().asSequence().filterNot { it.isDirectory || it.name.endsWith('/') }.toList()
            require(entries.size <= MAX_ARCHIVE_ENTRIES) { "Archive entry count exceeds the $MAX_ARCHIVE_ENTRIES limit." }
            val names = entries.map { it.name }
            require(names.distinct().size == names.size) { "Archive contains duplicate entry names." }
            require(names.all(::isSafeEntryName)) { "Archive contains an unsafe entry name." }
            entries.sortedBy { it.name }.forEach { entry ->
                if (entry.name in dexNames) return@forEach
                val remaining = maxOutputBytes - totalBytes
                val perEntryLimit = if (entry.name.endsWith(".class")) MAX_CLASS_BYTES else MAX_RESOURCE_BYTES
                val limit = minOf(perEntryLimit.toLong(), remaining).toInt()
                val bytes = zip.getInputStream(entry).use { stream -> readBounded(stream, limit, "Archive resource entry exceeds the per-entry or aggregate byte limit: ${entry.name}.") }
                require(bytes.isNotEmpty() || entry.name.endsWith("/") || entry.size == 0L) { "Archive resource entry could not be read: ${entry.name}." }
                totalBytes += bytes.size
                require(totalBytes <= maxOutputBytes) { "Archive resources exceed the aggregate output byte limit." }
                if (isObsoleteSignatureOrDigest(entry.name)) {
                    warnings += "Removed invalid or obsolete signature or digest entry from the merged JAR: ${entry.name}."
                    return@forEach
                }
                if (entry.name.equals(MANIFEST_ENTRY, ignoreCase = true)) {
                    require(files.put(MANIFEST_ENTRY, sanitizeManifest(bytes, warnings)) == null) { "Archive contains duplicate JAR manifests." }
                    return@forEach
                }
                if (entry.name.endsWith(".class")) {
                    val node = validateClass(bytes, entry.name, allowVersioned = true)
                    methodCount += node.methods.size
                    fieldCount += node.fields.size
                }
                require(files.put(entry.name, bytes) == null) { "Archive contains a duplicate resource entry: ${entry.name}." }
            }
        }
        return InputResources(files, warnings, methodCount, fieldCount)
    }

    /**
     * Keeps the manifest's application semantics, dropping only signature and digest attributes
     * whose values can no longer match the regenerated archive.
     */
    private fun sanitizeManifest(bytes: ByteArray, warnings: MutableList<String>): ByteArray {
        val manifest = try {
            Manifest(ByteArrayInputStream(bytes))
        } catch (error: IOException) {
            throw IllegalArgumentException("Input manifest is malformed: ${error.message}", error)
        }
        var changed = false
        if (removeSignedAttributes(manifest.mainAttributes)) changed = true
        manifest.entries.values.forEach { attributes -> if (removeSignedAttributes(attributes)) changed = true }
        val sanitized = ByteArrayOutputStream().also { manifest.write(it) }.toByteArray()
        if (changed) warnings += "Removed signature or digest attributes from the preserved JAR manifest."
        return sanitized
    }

    private fun removeSignedAttributes(attributes: Attributes): Boolean {
        val signed = attributes.keys.filter { name ->
            val value = name.toString()
            value.equals("Signature-Version", ignoreCase = true) ||
                value.endsWith("-Digest", ignoreCase = true) ||
                value.endsWith("-Digest-Manifest", ignoreCase = true) ||
                value.endsWith("-Digest-Manifest-Main-Attributes", ignoreCase = true)
        }
        signed.forEach { attributes.remove(it) }
        return signed.isNotEmpty()
    }

    private fun validateJar(path: Path, sourceDex: DexFileModel, budget: MergeBudget): ValidatedJar {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "dex2jar output JAR is not a regular non-symlink file."
        }
        val classFiles = linkedMapOf<String, ByteArray>()
        val resourceFiles = linkedMapOf<String, ByteArray>()
        val parsedClasses = linkedMapOf<String, ParsedClass>()
        val warnings = mutableListOf<String>()

        ZipFile(path.toFile()).use { zip ->
            require(zip.size() <= MAX_ARCHIVE_ENTRIES) { "JAR entry count exceeds the $MAX_ARCHIVE_ENTRIES limit." }
            val entries = zip.entries().asSequence().filterNot { it.isDirectory || it.name.endsWith('/') }.toList()
            val names = entries.map { it.name }
            require(names.distinct().size == names.size) { "JAR contains duplicate entry names." }
            require(names.all(::isSafeEntryName)) { "JAR contains an unsafe entry name." }
            // Only entries that survive into the merged JAR are charged; the stage manifest and
            // dropped signature entries are not preserved.
            budget.requireEntries(entries.count { !it.name.equals(MANIFEST_ENTRY, true) && !isObsoleteSignatureOrDigest(it.name) })
            entries.sortedBy { it.name }.forEach { entry ->
                // Each stage JAR carries dex2jar's own manifest; the merged JAR instead gets a
                // deterministic manifest built from the sanitized input manifest.
                if (entry.name.equals(MANIFEST_ENTRY, true)) return@forEach
                if (entry.name.endsWith(".class")) {
                    val bytes = zip.getInputStream(entry).use { stream ->
                        budget.readRetained(stream, MAX_CLASS_BYTES, "JAR class entry exceeds 8 MiB: ${entry.name}.")
                    }
                    val node = validateClass(bytes, entry.name)
                    val externalName = node.name.replace('/', '.')
                    require(parsedClasses.put(externalName, ParsedClass(node)) == null) { "JAR contains a duplicate class identity: $externalName." }
                    require(classFiles.put(entry.name, bytes) == null) { "JAR contains a duplicate class entry: ${entry.name}." }
                } else {
                    if (isObsoleteSignatureOrDigest(entry.name)) {
                        // Read the dropped entry to its per-entry limit, but do not retain it.
                        zip.getInputStream(entry).use { stream ->
                            readBounded(stream, MAX_RESOURCE_BYTES, "JAR signature entry exceeds the per-entry limit: ${entry.name}.")
                        }
                        warnings += "Removed invalid or obsolete JAR signature or digest entry: ${entry.name}."
                        return@forEach
                    }
                    val bytes = zip.getInputStream(entry).use { stream ->
                        budget.readRetained(stream, MAX_RESOURCE_BYTES, "JAR resource entry exceeds the per-entry limit: ${entry.name}.")
                    }
                    require(resourceFiles.put(entry.name, bytes) == null) { "JAR contains a duplicate resource entry: ${entry.name}." }
                }
            }
        }

        if (sourceDex.classes.isEmpty()) {
            require(parsedClasses.isEmpty()) { "dex2jar produced classes for an empty source DEX." }
            return ValidatedJar(emptyMap(), resourceFiles, 0, 0, 0, warnings)
        }
        require(parsedClasses.isNotEmpty()) { "dex2jar produced no JVM classes for ${sourceDex.name}." }

        sourceDex.classes.forEach { sourceClass ->
            val parsed = requireNotNull(parsedClasses[sourceClass.className]) {
                "dex2jar output is missing input class ${sourceClass.className}."
            }
            val outputMethods = parsed.node.methods.associateBy { it.name to it.desc }
            sourceClass.methods.forEach { sourceMethod ->
                val outputMethod = requireNotNull(outputMethods[sourceMethod.name to sourceMethod.descriptor]) {
                    "dex2jar output is missing method ${sourceClass.className}->${sourceMethod.name}${sourceMethod.descriptor}."
                }
                if (sourceMethod.code != null) {
                    require((outputMethod.instructions?.size() ?: 0) > 0) {
                        "dex2jar output is missing a code body for ${sourceClass.className}->${sourceMethod.name}${sourceMethod.descriptor}."
                    }
                }
            }
            val outputFields = parsed.node.fields.associateBy { it.name to it.desc }
            sourceClass.fields.forEach { sourceField ->
                requireNotNull(outputFields[sourceField.name to sourceField.descriptor]) {
                    "dex2jar output is missing field ${sourceClass.className}->${sourceField.name}:${sourceField.descriptor}."
                }
            }
        }
        return ValidatedJar(
            classFiles,
            resourceFiles,
            classFiles.size,
            parsedClasses.values.sumOf { it.node.methods.size },
            parsedClasses.values.sumOf { it.node.fields.size },
            warnings,
        )
    }

    private fun validateClass(bytes: ByteArray, entryName: String, allowVersioned: Boolean = false): ClassNode {
        val node = ClassNode()
        ClassReader(bytes).accept(CheckClassAdapter(ClassWriter(0), false), 0)
        ClassReader(bytes).accept(node, 0)
        val versioned = if (allowVersioned) VERSIONED_CLASS.matchEntire(entryName) else null
        val logicalEntry = versioned?.groupValues?.get(2) ?: entryName
        require(logicalEntry == node.name + ".class") { "Class entry $entryName does not match class ${node.name}." }
        val major = node.version and 0xffff
        require(major in 45..65) { "Class entry $entryName has a major version outside the supported 45..65 range." }
        if (versioned != null) {
            val release = versioned.groupValues[1].toIntOrNull()
            require(release != null && release >= 9 && major.toLong() <= release.toLong() + 44) { "Invalid multi-release class version in $entryName." }
        }
        require(node.methods.map { it.name to it.desc }.distinct().size == node.methods.size) { "Class ${node.name} contains duplicate method identities." }
        require(node.fields.map { it.name to it.desc }.distinct().size == node.fields.size) { "Class ${node.name} contains duplicate field identities." }
        val failures = mutableListOf<String>()
        if (node.superName != null && node.superName == node.name) failures += "Class ${node.name} has itself as a superclass."
        node.methods.forEach { method ->
            if ((method.instructions?.size() ?: 0) > 0) {
                runCatching { Analyzer(BasicVerifier()).analyze(node.name, method) }.onFailure {
                    failures += "ASM verification failed for ${node.name}->${method.name}${method.desc}: ${it.message.orEmpty()}"
                }
            } else if (method.access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) == 0) {
                failures += "Method ${node.name}->${method.name}${method.desc} has no code body but is not abstract or native."
            }
        }
        require(failures.isEmpty()) { failures.take(20).joinToString("; ") }
        return node
    }

    private fun mergeJar(
        classFiles: Map<String, ByteArray>,
        resources: Map<String, ByteArray>,
        manifestBytes: ByteArray,
        budget: MergeBudget,
        warnings: MutableList<String>,
    ): MergedJar {
        require(budget.retainedBytes <= maxOutputBytes) { "Merged JAR exceeds the aggregate byte limit." }
        require(budget.retainedEntries <= maxOutputEntries) { "Merged JAR exceeds the entry limit." }
        require(budget.retainedBytes == retainedBytes(classFiles, resources, manifestBytes)) {
            "Merged JAR content changed after budget accounting."
        }
        require(budget.retainedEntries == 1 + classFiles.size + resources.count { it.key != MANIFEST_ENTRY }) {
            "Merged JAR entry set changed after budget accounting."
        }
        val output = ByteArrayOutputStream()
        JarOutputStream(output).use { jar ->
            addJarEntry(jar, MANIFEST_ENTRY, manifestBytes)
            classFiles.toSortedMap().forEach { (name, bytes) -> addJarEntry(jar, name, bytes) }
            resources.toSortedMap().forEach { (name, bytes) -> if (name != MANIFEST_ENTRY) addJarEntry(jar, name, bytes) }
        }
        val bytes = output.toByteArray()
        require(bytes.size <= maxOutputBytes) { "Merged JAR exceeds the aggregate byte limit." }
        if (resources.containsKey(MANIFEST_ENTRY)) warnings += "Wrote the sanitized input manifest as the merged JAR manifest."
        return MergedJar(bytes, classFiles.size)
    }

    private fun retainedBytes(
        classFiles: Map<String, ByteArray>,
        resources: Map<String, ByteArray>,
        manifestBytes: ByteArray,
    ): Long = classFiles.values.sumOf { it.size.toLong() } +
        resources.entries.filter { it.key != MANIFEST_ENTRY }.sumOf { it.value.size.toLong() } +
        manifestBytes.size

    /**
     * Shared byte and entry accounting for everything kept in memory across every stage. Input
     * resources and the manifest are charged before any DEX output is read, so a large multidex
     * input cannot exceed 256 MiB or 100,000 entries only to fail after all stages were retained.
     */
    private class MergeBudget(bytes: Long, entries: Int, private val byteLimit: Long, private val entryLimit: Int) {
        var retainedBytes: Long = bytes
            private set
        var retainedEntries: Int = entries
            private set

        init {
            require(retainedBytes <= byteLimit) { "Input resources exceed the aggregate output byte limit." }
            require(retainedEntries <= entryLimit) { "Input resources exceed the output entry limit." }
        }

        fun requireEntries(count: Int) {
            require(count <= entryLimit - retainedEntries) {
                "Merged JAR exceeds the output entry limit."
            }
        }

        fun readRetained(stream: java.io.InputStream, perEntryLimit: Int, message: String): ByteArray {
            val remaining = byteLimit - retainedBytes
            requireEntries(1)
            val limit = minOf(perEntryLimit.toLong(), remaining).toInt()
            val bytes = stream.readNBytes(limit + 1)
            require(bytes.size <= perEntryLimit) { message }
            require(bytes.size <= remaining) { "Merged JAR exceeds the aggregate byte limit." }
            retainedBytes += bytes.size
            retainedEntries += 1
            return bytes
        }
    }

    private fun generatedManifestBytes(): ByteArray =
        ByteArrayOutputStream().also { Manifest().apply { mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0" }.write(it) }.toByteArray()

    private fun addJarEntry(jar: JarOutputStream, name: String, bytes: ByteArray) {
        jar.putNextEntry(JarEntry(name).apply { time = 0L })
        jar.write(bytes)
        jar.closeEntry()
    }

    private fun parseIdentity(stdout: String, stderr: String): Dex2jarIdentity? {
        val text = "$stdout\n$stderr"
        if (!TOOL_HEADER.containsMatchIn(text)) return null
        val reader = READER_VERSION.find(text)?.groupValues?.get(1) ?: return null
        val translator = TRANSLATOR_VERSION.find(text)?.groupValues?.get(1) ?: return null
        val ir = IR_VERSION.find(text)?.groupValues?.get(1) ?: return null
        return Dex2jarIdentity(reader, translator, ir)
    }

    private fun containsFailureMarker(text: String): Boolean =
        FAILURE_MARKERS.any { text.contains(it, ignoreCase = true) } || EXCEPTION_LINE.containsMatchIn(text)

    private fun readBounded(stream: java.io.InputStream, limit: Int, message: String): ByteArray {
        val bytes = stream.readNBytes(limit + 1)
        require(bytes.size <= limit) { message }
        return bytes
    }

    private fun failure(
        message: String,
        command: AndroidToolCommandOutput? = null,
        commands: List<AndroidToolCommandOutput> = emptyList(),
        sourceDexFiles: List<String> = emptyList(),
        dexClassCount: Int = 0,
        toolVersion: String? = null,
    ) = Dex2jarConversionOutput(
        command = command ?: validationFailure(TOOL_ID, message),
        sourceDexFiles = sourceDexFiles,
        toolVersion = toolVersion,
        commands = commands,
        warnings = listOf(message),
        dexClassCount = dexClassCount,
    )

    private fun validationFailure(executable: String, message: String = "dex2jar input validation failed.") =
        AndroidToolCommandOutput(false, executable, emptyList(), "", message, null, 0, false)

    private fun isSafeEntryName(name: String): Boolean =
        name.isNotBlank() && !name.startsWith("/") && !name.startsWith("\\") && !name.contains('\\') &&
            name.split('/').none { it == ".." || it == "." }

    private fun isObsoleteSignatureOrDigest(name: String): Boolean {
        val upper = name.uppercase()
        return upper.startsWith("META-INF/") && (
            upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") ||
                upper.endsWith(".EC") || upper.endsWith(".SIG") ||
                upper.substringAfterLast('/').startsWith("SHA-") || upper.substringAfterLast('/').startsWith("MD5-")
            )
    }

    private fun sha256(path: Path): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private data class Dex2jarIdentity(val reader: String, val translator: String, val ir: String) {
        fun matches(expected: String) = reader == expected && translator == expected && ir == expected
        fun render() = "reader-$reader, translator-$translator, ir-$ir"
    }

    private data class InputResources(val files: Map<String, ByteArray>, val warnings: List<String>, val methodCount: Int = 0, val fieldCount: Int = 0)

    private data class ParsedClass(val node: ClassNode)

    private data class ValidatedJar(
        val classFiles: Map<String, ByteArray>,
        val resourceFiles: Map<String, ByteArray>,
        val classCount: Int,
        val methodCount: Int,
        val fieldCount: Int,
        val warnings: List<String>,
    )

    private data class MergedJar(val bytes: ByteArray, val classCount: Int)

    companion object {
        const val DEFAULT_TOOL_VERSION = "2.4.38"
        private const val TOOL_ID = "d2j-dex2jar"
        private const val LEGACY_TOOL_ID = "dex2jar"
        private const val MANIFEST_ENTRY = "META-INF/MANIFEST.MF"
        private const val MAX_CLASS_BYTES = 8 * 1024 * 1024
        private const val MAX_RESOURCE_BYTES = 64 * 1024 * 1024
        private const val MAX_TOTAL_OUTPUT_BYTES = 256L * 1024 * 1024
        private const val MAX_ARCHIVE_ENTRIES = 100_000
        private val INPUT_TYPES = setOf("dex", "apk", "aab", "zip")
        private val VERSIONED_CLASS = Regex("META-INF/versions/([0-9]+)/(.+\\.class)")
        private val TOOL_HEADER = Regex("(?im)^\\s*d2j-dex2jar\\b")
        private val READER_VERSION = Regex("(?<![A-Za-z0-9_-])reader-([A-Za-z0-9._-]+)")
        private val TRANSLATOR_VERSION = Regex("(?<![A-Za-z0-9_-])translator-([A-Za-z0-9._-]+)")
        private val IR_VERSION = Regex("(?<![A-Za-z0-9_-])ir-([A-Za-z0-9._-]+)")
        private val FAILURE_MARKERS = listOf(
            "failed to translate", "fail to translate", "failed to convert code",
            "detail error information", "please report this file",
            "not a .dex or .zip file", "unrecognized option", "invalid option",
            "com.googlecode.d2j.dexexception",
        )
        private val EXCEPTION_LINE = Regex("(?im)^\\s*(?:[\\w.$]+\\.)?[A-Za-z_$][\\w$]*(?:Exception|Error)(?::|\\s|$)")
    }
}
