package net.evrt.acrab.analysis.android

import net.evrt.acrab.analysis.index.ProjectRecord

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.BasicVerifier
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.Adler32
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal class AndroidToolsService(
    private val projects: AndroidProjectStore,
    private val runner: AndroidCommandRunner = AndroidCommandRunner(),
    private val d8Options: D8ConversionOptions = D8ConversionOptions.fromEnvironment(),
) {
    fun dex2jarStatus(): AndroidDex2JarStatusResult {
        val components = listOf("d2j-dex2jar", "dex2jar", "baksmali", "smali", "d8", "dx", "jasmin").associateWith { runner.locate(it) }
        val available = components.values.any { it != null }
        return AndroidDex2JarStatusResult(available, if (!available) "unavailable" else if (runner.bundledRoot != null) "bundled-java" else "trusted-root", components, runner.bundledRoot?.toString() ?: "Explicit trusted roots", listOf("android_dex2jar_convert", "android_baksmali_convert", "android_smali_assemble", "android_jar2dex_convert", "android_jar2jasmin_convert", "android_jasmin2jar_convert"), if (available) emptyList() else listOf("No allowlisted Android conversion tool was found; built-in verification and checksum tools remain available."))
    }

    fun catalog(): AndroidDexToolsCatalogResult = AndroidDexToolsCatalogResult(
        sourceDirectory = runner.bundledRoot?.toString(),
        bundledArtifact = if (runner.bundledRoot != null) "Bundled Java toolchains; fixed versions and isolated verified classpaths" else "Bundled tools not selected/detected; using explicit trusted roots when configured",
        tools = listOf(
            AndroidDexToolInfo("d2j-dex2jar", "d2j-dex2jar", "com.googlecode.dex2jar.tools.Dex2jarCmd", "dex-to-jar", "android_dex2jar_convert", "Bundled Java converter; explicit trusted roots may override it."),
            AndroidDexToolInfo("baksmali", "baksmali", "com.android.tools.smali.baksmali.Main", "dex-to-smali", "android_baksmali_convert", "Bundled Java disassembler; explicit trusted roots may override it."),
            AndroidDexToolInfo("smali", "smali", "com.android.tools.smali.smali.Main", "smali-to-dex", "android_smali_assemble", "Bundled Java assembler; explicit trusted roots may override it."),
            AndroidDexToolInfo("d8", "d8", "com.android.tools.r8.D8", "jar-to-dex", "android_jar2dex_convert", "Bundled D8; platform/application libraries remain input-specific."),
            AndroidDexToolInfo("jasmin", "jasmin", "jasmin.Main", "jasmin-assemble", "android_jasmin2jar_convert", "Assembler-only tool root entry; Jar-to-Jasmin disassembly is unsupported and creates no artifact."),
        ),
        warnings = listOf("Bundled tools use the current JVM, allowlisted main classes and checksum-verified isolated classpaths; they never use shell wrappers or download at runtime. Explicit external roots disable bundled fallback; Windows batch overrides retain argument safety checks."),
    )

    suspend fun asmVerify(projectId: String): AndroidAsmVerifyResult {
        val project = projects.getProject(projectId); val issues = mutableListOf<AndroidAsmVerifyIssueInfo>(); var classes = 0; var methods = 0
        val path = Path.of(project.archivePath)
        if (!project.inputType.equals("jar", true) && !project.inputType.equals("war", true) && !project.inputType.equals("ear", true)) return AndroidAsmVerifyResult(projectId, project.fileName, 0, 0, 1, false, listOf(AndroidAsmVerifyIssueInfo("<input>", "<archive>", "", "ASM verification expects a JAR/WAR/EAR input.")), emptyList())
        JarFile(path.toFile()).use { jar ->
            jar.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") }.forEach { entry ->
                if (issues.size >= MAX_ISSUES) return@forEach
                classes++
                val bytes = readBoundedEntry(jar.getInputStream(entry), entryName = entry.name)
                runCatching {
                    val node = ClassNode(); ClassReader(bytes).accept(node, 0)
                    @Suppress("UNCHECKED_CAST")
                    (node.methods as List<MethodNode>).forEach { method ->
                        methods++
                        if (method.instructions.size() == 0) return@forEach
                        runCatching { Analyzer(BasicVerifier()).analyze(node.name, method) }.onFailure { failure -> issues += AndroidAsmVerifyIssueInfo(node.name.replace('/', '.'), method.name, method.desc, failure.message.orEmpty()) }
                    }
                }.onFailure { failure -> issues += AndroidAsmVerifyIssueInfo(entry.name, "<class>", "", failure.message.orEmpty()) }
            }
        }
        return AndroidAsmVerifyResult(projectId, project.fileName, classes, methods, issues.size, issues.size >= MAX_ISSUES, issues.take(MAX_ISSUES), emptyList())
    }

    suspend fun recomputeChecksum(projectId: String): AndroidDexChecksumResult {
        val project = projects.getProject(projectId); val input = Path.of(project.archivePath); val bytes = Files.readAllBytes(input)
        val output = if (project.inputType == "dex") recomputeDex(bytes) else rewriteDexEntries(bytes)
        val count = if (project.inputType == "dex") 1 else countDexEntries(bytes)
        val fileName = "android-checksum-${shortId()}.${project.fileName.substringAfterLast('.', "bin")}"; val artifact = projects.createArtifact(projectId, "artifacts/$fileName", "android_dex_recompute_checksum", bytes = output)
        val path = projectRoot(project).resolve(artifact.relativePath).normalize()
        return AndroidDexChecksumResult(projectId, project.fileName, path.toString(), count, listOf(AndroidGeneratedFileInfo(fileName, path.toString(), output.size.toLong(), sha256(output))), true, listOf("Only DEX checksum/signature metadata is changed; payload instructions are not rewritten."))
    }

    suspend fun dex2jar(projectId: String): AndroidDex2JarConvertResult {
        val project = projects.getProject(projectId)
        val executable = when {
            runner.locate("d2j-dex2jar") != null -> "d2j-dex2jar"
            runner.locate("dex2jar") != null -> "dex2jar"
            else -> return AndroidDex2JarConvertResult(projectId, project.fileName, null, "converted.jar", 0, 0, 0, 0, false, commandUnavailable("d2j-dex2jar", emptyList(), "No allowlisted dex2jar executable is available."), listOf("Install an allowlisted dex2jar executable or use android_dex2jar_status to inspect availability."))
        }
        val converted = Dex2jarConverterAdapter { tool, arguments, work -> runner.run(tool, arguments, work) }
            .convert(project, executable, workDirectory(project, "dex2jar"))
        val bytes = converted.bytes ?: return AndroidDex2JarConvertResult(projectId, project.fileName, null, "converted.jar", converted.dexClassCount, 0, 0, 0, false, converted.command, converted.warnings, converted.toolVersion, converted.sourceDexFiles, converted.commands)
        val artifact = projects.createArtifact(projectId, "artifacts/android-dex2jar-${shortId()}.jar", "android_dex2jar_convert", bytes = bytes)
        val path = projectRoot(project).resolve(artifact.relativePath)
        return AndroidDex2JarConvertResult(projectId, project.fileName, path.toString(), path.fileName.toString(), converted.dexClassCount, converted.classCount, converted.methodCount, converted.fieldCount, true, converted.command, converted.warnings, converted.toolVersion, converted.sourceDexFiles, converted.commands)
    }

    suspend fun baksmali(projectId: String): AndroidBaksmaliResult {
        val project = projects.getProject(projectId); val binary = runner.locate("baksmali"); val input = Path.of(project.archivePath)
        if (binary == null) return AndroidBaksmaliResult(projectId, project.fileName, null, 0, 0, emptyList(), false, commandUnavailable("baksmali", emptyList(), "No allowlisted baksmali executable is available."), listOf("No artifact is created when baksmali is unavailable."))
        val versionProbe = probeSmaliToolVersion(project, "baksmali", "baksmali", SMALI_TOOL_VERSION)
        if (!versionProbe.valid) return AndroidBaksmaliResult(projectId, project.fileName, null, 0, 0, emptyList(), false, versionProbe.command, listOf(versionProbe.message), toolVersion = versionProbe.version, commands = listOf(versionProbe.command))
        val inputHash = sha256File(input)
        val dexArchive = try {
            DexArchiveReader().read(project)
        } catch (error: IOException) {
            return AndroidBaksmaliResult(projectId, project.fileName, null, 0, 0, emptyList(), false, validationFailure("baksmali", "The input is not a valid DEX archive: ${error.message}"), listOf("baksmali requires a structurally valid DEX/APK/AAB input."), toolVersion = versionProbe.version, commands = listOf(versionProbe.command))
        } catch (error: IllegalArgumentException) {
            return AndroidBaksmaliResult(projectId, project.fileName, null, 0, 0, emptyList(), false, validationFailure("baksmali", "The input is not a valid DEX archive: ${error.message}"), listOf("baksmali requires a structurally valid DEX/APK/AAB input."), toolVersion = versionProbe.version, commands = listOf(versionProbe.command))
        }
        val work = workDirectory(project, "baksmali")
        val inputs = work.resolve("inputs").also(Files::createDirectories)
        val outputRoot = work.resolve("smali").also(Files::createDirectories)
        val commands = mutableListOf<AndroidToolCommandOutput>()
        val sourceDexFiles = mutableListOf<String>()
        val allFiles = mutableListOf<Path>()
        var aggregateBytes = 0L
        dexArchive.files.forEachIndexed { index, dexFile ->
            val staged = inputs.resolve("$index.dex")
            Files.write(staged, dexFile.bytes)
            sourceDexFiles += dexFile.name
            val output = if (dexArchive.files.size == 1) outputRoot else outputRoot.resolve("dex-$index").also(Files::createDirectories)
            val args = listOf("disassemble", staged.toString(), "-o", output.toString())
            val command = runner.run("baksmali", args, work)
            commands += command
            if (!command.completed) {
                return AndroidBaksmaliResult(projectId, project.fileName, null, dexArchive.files.size, allFiles.size, allFiles.take(10).map(Path::toString), false, command, listOf("External converter did not complete for source DEX ${dexFile.name}."), toolVersion = versionProbe.version, sourceDexFiles = sourceDexFiles.toList(), commands = commands.toList())
            }
            val validation = validateSmaliOutput(output)
            if (!validation.valid) {
                return AndroidBaksmaliResult(projectId, project.fileName, null, dexArchive.files.size, allFiles.size, allFiles.take(10).map(Path::toString), false, command, listOf(validation.message), toolVersion = versionProbe.version, sourceDexFiles = sourceDexFiles.toList(), commands = commands.toList())
            }
            allFiles += validation.files
            aggregateBytes += validation.files.sumOf { Files.size(it) }
            if (allFiles.size > MAX_SMALI_TOTAL_FILES || aggregateBytes > MAX_SMALI_AGGREGATE_BYTES) {
                return AndroidBaksmaliResult(projectId, project.fileName, null, dexArchive.files.size, allFiles.size, allFiles.take(10).map(Path::toString), false, command, listOf("Baksmali output exceeds the aggregate smali budget."), toolVersion = versionProbe.version, sourceDexFiles = sourceDexFiles.toList(), commands = commands.toList())
            }
        }
        if (sha256File(input) != inputHash) return AndroidBaksmaliResult(projectId, project.fileName, null, dexArchive.files.size, allFiles.size, allFiles.take(10).map(Path::toString), false, commands.last(), listOf("baksmali modified the input artifact."), toolVersion = versionProbe.version, sourceDexFiles = sourceDexFiles.toList(), commands = commands.toList())
        val archive = zipDirectory(allFiles, outputRoot); val artifact = projects.createArtifact(projectId, "artifacts/android-baksmali-${shortId()}.zip", "android_baksmali_convert", bytes = archive); val path = projectRoot(project).resolve(artifact.relativePath)
        return AndroidBaksmaliResult(projectId, project.fileName, path.toString(), dexArchive.files.size, allFiles.size, allFiles.take(10).map(Path::toString), true, commands.last(), emptyList(), toolVersion = versionProbe.version, sourceDexFiles = sourceDexFiles.toList(), commands = commands.toList())
    }

    suspend fun smaliAssemble(projectId: String, inputPath: String?): AndroidSmaliAssembleResult {
        val project = projects.getProject(projectId); val binary = runner.locate("smali"); val root = projectRoot(project); val input = Path.of(inputPath ?: "").toAbsolutePath().normalize()
        require(inputPath != null && input.startsWith(root)) { "inputPath must be inside the project workspace." }
        if (Files.exists(input)) {
            if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(input, LinkOption.NOFOLLOW_LINKS)) {
                return AndroidSmaliAssembleResult(projectId, input.toString(), null, 0, 0, 0, false, validationFailure("smali", "inputPath must be a regular file or directory."), listOf("inputPath must be a regular file or directory."))
            }
            var current = root
            for (part in root.relativize(input)) {
                current = current.resolve(part)
                if (Files.isSymbolicLink(current)) {
                    return AndroidSmaliAssembleResult(projectId, input.toString(), null, 0, 0, 0, false, validationFailure("smali", "inputPath must not contain symbolic links."), listOf("inputPath must not contain symbolic links."))
                }
            }
            if (!input.toRealPath().startsWith(root.toRealPath())) {
                return AndroidSmaliAssembleResult(projectId, input.toString(), null, 0, 0, 0, false, validationFailure("smali", "inputPath must resolve inside the project workspace."), listOf("inputPath must resolve inside the project workspace."))
            }
        }
        if (binary == null) return AndroidSmaliAssembleResult(projectId, input.toString(), null, 0, 0, 0, false, commandUnavailable("smali", emptyList(), "No allowlisted smali executable is available."), listOf("No artifact is created when smali is unavailable."))
        val versionProbe = probeSmaliToolVersion(project, "smali", "smali", SMALI_TOOL_VERSION)
        if (!versionProbe.valid) return AndroidSmaliAssembleResult(projectId, input.toString(), null, 0, 0, 0, false, versionProbe.command, listOf(versionProbe.message), toolVersion = versionProbe.version)
        val inputValidation = validateSmaliInput(input)
        if (!inputValidation.valid) return AndroidSmaliAssembleResult(projectId, input.toString(), null, inputValidation.files.size, 0, 0, false, validationFailure("smali", inputValidation.message), listOf(inputValidation.message), toolVersion = versionProbe.version)
        val inputDigest = smaliTreeDigest(inputValidation.allFiles, input)
        val work = workDirectory(project, "smali"); val output = work.resolve("classes.dex"); val args = listOf("assemble", input.toString(), "-o", output.toString()); val command = runner.run("smali", args, work)
        if (!command.completed || !Files.isRegularFile(output)) return AndroidSmaliAssembleResult(projectId, input.toString(), null, inputValidation.files.size, 0, 0, false, command, listOf("External assembler did not produce DEX output."), toolVersion = versionProbe.version)
        val bytes = readBounded(output)
        val counts = validateAssembledDex(project, output, bytes)
        if (counts == null) return AndroidSmaliAssembleResult(projectId, input.toString(), null, inputValidation.files.size, 0, 0, false, command, listOf("External assembler produced an invalid DEX artifact."), toolVersion = versionProbe.version)
        val inputUnchanged = try {
            smaliTreeDigest(snapshotSmaliTree(input).files, input) == inputDigest
        } catch (_: IOException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
        if (!inputUnchanged) return AndroidSmaliAssembleResult(projectId, input.toString(), null, inputValidation.files.size, 0, 0, false, command, listOf("smali assemble modified the input smali tree."), toolVersion = versionProbe.version)
        val artifact = projects.createArtifact(projectId, "artifacts/android-smali-${shortId()}.dex", "android_smali_assemble", bytes = bytes); return AndroidSmaliAssembleResult(projectId, input.toString(), root.resolve(artifact.relativePath).toString(), inputValidation.files.size, counts.first, counts.second, true, command, emptyList(), toolVersion = versionProbe.version)
    }

    suspend fun jar2dex(projectId: String): AndroidJar2DexConvertResult {
        val project = projects.getProject(projectId)
        val executable = when {
            runner.locate("d8") != null -> "d8"
            runner.locate("dx") != null -> "dx"
            else -> return AndroidJar2DexConvertResult(projectId, project.fileName, null, false, 0, 0, 0, commandUnavailable("d8", emptyList(), "No allowlisted D8 or dx executable is available."), listOf("No artifact is created when jar2dex is unavailable."))
        }
        val converted = D8ConverterAdapter(d8Options) { tool, arguments, work -> runner.run(tool, arguments, work) }
            .convert(project, executable, workDirectory(project, "jar2dex"))
        val bytes = converted.bytes ?: return AndroidJar2DexConvertResult(projectId, project.fileName, null, false, 0, 0, 0, converted.command, converted.warnings, minApi = d8Options.minApi, toolVersion = converted.toolVersion)
        val extension = if (converted.format == "dex") "dex" else "zip"
        val artifact = projects.createArtifact(projectId, "artifacts/android-jar2dex-${shortId()}.$extension", "android_jar2dex_convert", bytes = bytes)
        return AndroidJar2DexConvertResult(projectId, project.fileName, projectRoot(project).resolve(artifact.relativePath).toString(), true, bytes.size.toLong(), converted.classCount, converted.methodCount, converted.command, converted.warnings, converted.format, converted.dexFiles, d8Options.minApi, converted.toolVersion)
    }

    suspend fun jar2jasmin(projectId: String): AndroidJar2JasminResult {
        val project = projects.getProject(projectId)
        val binary = runner.locate("jasmin")
        val unavailable = binary == null
        val message = if (unavailable) {
            "No allowlisted Jasmin executable is available."
        } else {
            "Jar-to-Jasmin disassembly is unsupported by the allowlisted Jasmin assembler; no artifact is created."
        }
        val warnings = if (unavailable) {
            listOf("No artifact is created when Jasmin is unavailable.")
        } else {
            listOf("The allowlisted Jasmin tool is an assembler only; Jar-to-Jasmin disassembly is unsupported and no artifact is created.")
        }
        return AndroidJar2JasminResult(projectId, project.fileName, null, 0, 0, emptyList(), false, commandUnavailable("jasmin", emptyList(), message), warnings)
    }

    suspend fun jasmin2jar(projectId: String, inputPath: String?): AndroidJasmin2JarResult {
        val project = projects.getProject(projectId)
        val root = projectRoot(project)
        require(inputPath != null) { "inputPath must be inside the project workspace." }
        val input = Path.of(inputPath).toAbsolutePath().normalize()
        require(input.startsWith(root)) { "inputPath must be inside the project workspace." }
        if (runner.locate("jasmin") == null) return AndroidJasmin2JarResult(projectId, input.toString(), null, false, false, 0, 0, 0, commandUnavailable("jasmin", emptyList(), "No allowlisted Jasmin executable is available."), listOf("No artifact is created when Jasmin is unavailable."))
        val snapshot = try {
            snapshotJasminInput(input)
        } catch (error: IOException) {
            val message = "Jasmin input could not be read: ${error.message}"
            return AndroidJasmin2JarResult(projectId, input.toString(), null, false, false, 0, 0, 0, validationFailure("jasmin", message), listOf(message))
        } catch (error: IllegalArgumentException) {
            val message = error.message ?: "Jasmin input is invalid."
            return AndroidJasmin2JarResult(projectId, input.toString(), null, false, false, 0, 0, 0, validationFailure("jasmin", message), listOf(message))
        }
        if (snapshot.jasminFiles.isEmpty()) {
            val message = "Jasmin input contains no .j files."
            return AndroidJasmin2JarResult(projectId, input.toString(), null, false, false, 0, 0, 0, validationFailure("jasmin", message), listOf(message))
        }
        val work = workDirectory(project, "jasmin2jar")
        val classes = work.resolve("classes").also(Files::createDirectories)
        val sources = work.resolve("sources").also(Files::createDirectories)
        // Classic Jasmin's scanner can loop at EOF without a final newline; normalize only staged copies.
        val staged = snapshot.jasminFiles.map { source ->
            val relative = if (Files.isRegularFile(input)) source.fileName else input.relativize(source)
            val target = sources.resolve(relative).normalize()
            require(target.startsWith(sources)) { "Jasmin staging path escaped its root." }
            Files.createDirectories(target.parent)
            val bytes = readBounded(source)
            Files.write(target, if (bytes.lastOrNull() == '\n'.code.toByte()) bytes else bytes + '\n'.code.toByte())
            target.toString()
        }
        val arguments = listOf("-d", classes.toString()) + staged
        val command = runner.run("jasmin", arguments, work)
        fun failure(message: String) = AndroidJasmin2JarResult(projectId, input.toString(), null, false, false, snapshot.jasminFiles.size, 0, 0, command, listOf(message))
        if (!command.completed || command.timedOut) return failure("External assembler did not complete; partial output is rejected.")
        if (jasminReportedFailure(command)) return failure("External assembler reported errors; no artifact is created.")
        val validation = validateJasminClasses(classes, snapshot.jasminFiles.size)
        if (!validation.valid) return failure(validation.message)
        val unchanged = try {
            snapshotJasminInput(input).digest == snapshot.digest
        } catch (_: IOException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
        if (!unchanged) return failure("External assembler modified the Jasmin input tree; no artifact is created.")
        val bytes = try {
            buildJasminJar(validation.classFiles, snapshot.resourceFiles)
        } catch (error: IOException) {
            return failure("Jasmin output could not be packaged: ${error.message}")
        } catch (error: IllegalArgumentException) {
            return failure(error.message ?: "Jasmin output could not be packaged.")
        }
        val artifact = projects.createArtifact(projectId, "artifacts/android-jasmin2jar-${shortId()}.jar", "android_jasmin2jar_convert", bytes = bytes)
        return AndroidJasmin2JarResult(projectId, input.toString(), root.resolve(artifact.relativePath).toString(), true, true, snapshot.jasminFiles.size, validation.classCount, validation.methodCount, command, emptyList())
    }

    suspend fun classVersionSwitch(projectId: String, targetMajorVersion: Int): AndroidClassVersionSwitchResult {
        require(targetMajorVersion in 45..65) { "targetMajorVersion must be between 45 and 65." }
        val project = projects.getProject(projectId); val input = Path.of(project.archivePath); require(project.inputType in setOf("jar", "war", "ear")) { "class version switching expects a JVM archive." }
        val output = ByteArrayOutputStream(); var classes = 0; var changed = 0
        ZipFile(input.toFile()).use { zip -> ZipOutputStream(output).use { out ->
            zip.entries().asSequence().forEach { entry ->
                val bytes = readBoundedEntry(zip.getInputStream(entry), entryName = entry.name); val rewritten = if (entry.name.endsWith(".class") && bytes.size >= 8 && bytes[0] == 0xca.toByte() && bytes[1] == 0xfe.toByte() && bytes[2] == 0xba.toByte() && bytes[3] == 0xbe.toByte()) { classes++; val copy = bytes.copyOf(); val old = ((copy[6].toInt() and 0xff) shl 8) or (copy[7].toInt() and 0xff); if (old != targetMajorVersion) { copy[6] = (targetMajorVersion ushr 8).toByte(); copy[7] = targetMajorVersion.toByte(); changed++ }; copy } else bytes
                out.putNextEntry(ZipEntry(entry.name)); out.write(rewritten); out.closeEntry()
            }
        } }
        val bytes = output.toByteArray(); val artifact = projects.createArtifact(projectId, "artifacts/android-class-version-${shortId()}.jar", "android_class_version_switch", bytes = bytes); return AndroidClassVersionSwitchResult(projectId, project.fileName, projectRoot(project).resolve(artifact.relativePath).toString(), targetMajorVersion, classes, changed, true, listOf("Only class-file major version bytes were rewritten; input archive bytes remain unchanged."))
    }

    private fun readBounded(path: Path, limit: Long = MAX_FILE_BYTES.toLong()): ByteArray {
        val size = Files.size(path)
        require(size <= limit) { "Android tool output exceeds the ${limit / 1024 / 1024} MiB limit." }
        return Files.newInputStream(path).use { input -> input.readNBytes(limit.toInt() + 1) }.also { bytes ->
            require(bytes.size <= limit) { "Android tool output exceeds the ${limit / 1024 / 1024} MiB limit." }
        }
    }

    private fun readBoundedEntry(input: java.io.InputStream, limit: Int = MAX_FILE_BYTES, entryName: String = "<entry>"): ByteArray {
        val bytes = input.readNBytes(limit + 1)
        require(bytes.size <= limit) { "Archive entry '$entryName' exceeds the ${limit / 1024 / 1024} MiB limit." }
        return bytes
    }

    private fun snapshotJasminInput(root: Path): JasminTreeSnapshot {
        if (Files.isSymbolicLink(root)) throw IllegalArgumentException("Jasmin input must not be a symbolic link.")
        if (!Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw IllegalArgumentException("Jasmin input is not a regular file or directory.")
        val jasminFiles = mutableListOf<Path>()
        val resources = mutableMapOf<String, ByteArray>()
        val hashedEntries = mutableListOf<Pair<String, ByteArray>>()
        var totalBytes = 0L
        var entries = 0
        Files.walk(root).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (++entries > MAX_JASMIN_WALK_ENTRIES) throw IllegalArgumentException("Jasmin tree exceeds the $MAX_JASMIN_WALK_ENTRIES walk entry limit.")
                if (Files.isSymbolicLink(path)) throw IllegalArgumentException("Jasmin tree contains a symbolic link: ${root.relativize(path)}.")
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue
                val size = Files.size(path)
                require(size <= MAX_JASMIN_FILE_BYTES) { "Jasmin file exceeds the ${MAX_JASMIN_FILE_BYTES / 1024} KiB limit: ${path.fileName}." }
                totalBytes += size
                require(totalBytes <= MAX_JASMIN_TREE_BYTES) { "Jasmin tree exceeds the ${MAX_JASMIN_TREE_BYTES / 1024 / 1024} MiB file budget." }
                val relative = if (Files.isDirectory(root)) root.relativize(path).toString().replace('\\', '/') else path.fileName.toString()
                val bytes = Files.newInputStream(path).use { input -> input.readNBytes(MAX_JASMIN_FILE_BYTES + 1) }
                require(bytes.size <= MAX_JASMIN_FILE_BYTES) { "Jasmin file exceeds the ${MAX_JASMIN_FILE_BYTES / 1024} KiB limit: ${path.fileName}." }
                hashedEntries.add(relative to bytes)
                if (path.fileName.toString().endsWith(".j", true)) {
                    require(jasminFiles.size < MAX_JASMIN_FILE_COUNT) { "Jasmin tree exceeds the $MAX_JASMIN_FILE_COUNT file limit." }
                    jasminFiles.add(path)
                } else {
                    resources[relative] = bytes
                }
            }
        }
        jasminFiles.sort()
        val digest = MessageDigest.getInstance("SHA-256")
        hashedEntries.sortedBy { it.first }.forEach { (name, bytes) ->
            digest.update(name.toByteArray(StandardCharsets.UTF_8)); digest.update(0); digest.update(bytes); digest.update(0)
        }
        return JasminTreeSnapshot(jasminFiles, resources, digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }

    private fun jasminReportedFailure(command: AndroidToolCommandOutput): Boolean {
        val output = "${command.stdout}\n${command.stderr}"
        return JASMIN_ERROR_MARKERS.any { output.contains(it, ignoreCase = true) }
    }

    private fun validateJasminClasses(directory: Path, expectedClassCount: Int): JasminClassValidation {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return JasminClassValidation(false, emptyMap(), 0, 0, "External assembler did not produce a class directory.")
        var classes = 0
        var methods = 0
        val classFiles = mutableMapOf<String, ByteArray>()
        val binaryNames = mutableSetOf<String>()
        Files.walk(directory).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (Files.isSymbolicLink(path)) return JasminClassValidation(false, emptyMap(), classes, methods, "Assembler class output contains a symbolic link: ${directory.relativize(path)}.")
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue
                if (!path.fileName.toString().endsWith(".class", true)) continue
                if (classes >= MAX_JASMIN_CLASS_COUNT) return JasminClassValidation(false, emptyMap(), classes, methods, "Assembler output exceeds the $MAX_JASMIN_CLASS_COUNT class limit.")
                val bytes = Files.newInputStream(path).use { input -> input.readNBytes(MAX_JASMIN_CLASS_BYTES + 1) }
                if (bytes.size > MAX_JASMIN_CLASS_BYTES) return JasminClassValidation(false, emptyMap(), classes, methods, "Assembler class output exceeds the ${MAX_JASMIN_CLASS_BYTES / 1024 / 1024} MiB limit: ${path.fileName}.")
                if (bytes.size < 10 || bytes[0] != 0xca.toByte() || bytes[1] != 0xfe.toByte() || bytes[2] != 0xba.toByte() || bytes[3] != 0xbe.toByte()) return JasminClassValidation(false, emptyMap(), classes, methods, "Assembler produced an invalid class header: ${path.fileName}.")
                val node = try {
                    val parsed = ClassNode()
                    ClassReader(bytes).accept(parsed, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                    parsed
                } catch (error: RuntimeException) {
                    return JasminClassValidation(false, emptyMap(), classes, methods, "Assembler produced an invalid class file: ${path.fileName}.")
                } catch (error: IOException) {
                    return JasminClassValidation(false, emptyMap(), classes, methods, "Assembler class output could not be read: ${path.fileName}.")
                }
                val name = directory.relativize(path).toString().replace('\\', '/')
                if (name != node.name + ".class") return JasminClassValidation(false, emptyMap(), classes, methods, "Assembler class path '$name' does not match binary name '${node.name}'.")
                if (!binaryNames.add(node.name)) return JasminClassValidation(false, emptyMap(), classes, methods, "Assembler produced duplicate binary name '${node.name}'.")
                val major = node.version and 0xffff
                if (major !in 45..CLASSIC_JAVA_MAJOR) return JasminClassValidation(false, emptyMap(), classes, methods, "Assembler produced class '${node.name}' with version $major, outside the classic Jasmin Java 8 support range.")
                if (hasModernConstants(node)) return JasminClassValidation(false, emptyMap(), classes, methods, "Assembler produced invokedynamic or constant-dynamic bytecode in '${node.name}', which is outside the classic Jasmin support range.")
                @Suppress("UNCHECKED_CAST")
                (node.methods as List<MethodNode>).forEach { method ->
                    methods++
                    if (method.instructions.size() == 0) return@forEach
                    runCatching { Analyzer(BasicVerifier()).analyze(node.name, method) }
                        .onFailure { return JasminClassValidation(false, emptyMap(), classes, methods, "Assembler produced invalid bytecode in '${node.name}.${method.name}${method.desc}': ${it.message}") }
                }
                classFiles[name] = bytes
                classes++
            }
        }
        if (classes == 0) return JasminClassValidation(false, emptyMap(), 0, 0, "External assembler produced no class files.")
        if (classes != expectedClassCount) return JasminClassValidation(false, emptyMap(), classes, methods, "External assembler produced $classes class file(s) for $expectedClassCount Jasmin file(s).")
        return JasminClassValidation(true, classFiles, classes, methods, "")
    }

    private fun hasModernConstants(node: ClassNode): Boolean {
        if (node.methods.any { method ->
                method.instructions.any { instruction ->
                    instruction is org.objectweb.asm.tree.InvokeDynamicInsnNode ||
                        (instruction is org.objectweb.asm.tree.LdcInsnNode && instruction.cst is org.objectweb.asm.ConstantDynamic)
                }
            }
        ) return true
        return node.fields.any { field -> field.value is org.objectweb.asm.ConstantDynamic }
    }

    private fun buildJasminJar(classFiles: Map<String, ByteArray>, resources: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        val manifestEntry = resources.keys.firstOrNull { it.equals("META-INF/MANIFEST.MF", ignoreCase = true) }
        val stream = if (manifestEntry == null) {
            JarOutputStream(output)
        } else {
            JarOutputStream(output, java.util.jar.Manifest(resources.getValue(manifestEntry).inputStream()))
        }
        stream.use { jar ->
            classFiles.toSortedMap().forEach { (name, bytes) ->
                val normalized = name.replace('\\', '/')
                require(!normalized.startsWith("/") && normalized.split('/').none { it == ".." }) { "Assembler class output escaped its root." }
                jar.putNextEntry(JarEntry(normalized)); jar.write(bytes); jar.closeEntry()
            }
            resources.toSortedMap().forEach { (name, bytes) ->
                if (name == manifestEntry) return@forEach
                require(!name.startsWith("/") && name.split('/').none { it == ".." }) { "Jasmin resource output escaped its root." }
                jar.putNextEntry(JarEntry(name)); jar.write(bytes); jar.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private data class JasminTreeSnapshot(val jasminFiles: List<Path>, val resourceFiles: Map<String, ByteArray>, val digest: String)
    private data class JasminClassValidation(val valid: Boolean, val classFiles: Map<String, ByteArray>, val classCount: Int, val methodCount: Int, val message: String)

    private fun zipDirectory(files: List<Path>, directory: Path): ByteArray {
        val output = ByteArrayOutputStream(); ZipOutputStream(output).use { zip ->
            files.forEach { path ->
                require(path.toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize())) { "Archive source escaped its root." }
                val name = directory.relativize(path).toString().replace('\\', '/')
                zip.putNextEntry(ZipEntry(name))
                Files.newInputStream(path).use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }; return output.toByteArray()
    }

    private fun rewriteDexEntries(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        var entryCount = 0
        var totalBytes = 0L
        var dexCount = 0
        ZipInputStream(bytes.inputStream()).use { input -> ZipOutputStream(output).use { zip ->
            while (true) {
                val entry = input.nextEntry ?: break
                require(++entryCount <= DexArchiveReader.MAX_ENTRY_COUNT) { "Archive entry count exceeds the parser budget." }
                val data = readBoundedEntry(input, entryName = entry.name)
                totalBytes += data.size
                require(totalBytes <= DexArchiveReader.MAX_TOTAL_UNCOMPRESSED_BYTES) { "Archive expanded bytes exceed the parser budget." }
                val baseName = entry.name.substringAfterLast('/')
                val dexEntry = baseName.matches(Regex("classes(\\d*)\\.dex"))
                val rewritten = if (dexEntry) { dexCount++; recomputeDex(data) } else data
                val copy = ZipEntry(entry.name).apply {
                    method = entry.method; time = entry.time; comment = entry.comment
                    if (method == ZipEntry.STORED) {
                        size = rewritten.size.toLong()
                        compressedSize = size
                        crc = CRC32().also { it.update(rewritten) }.value
                    }
                }
                zip.putNextEntry(copy); zip.write(rewritten); zip.closeEntry()
            }
        } }
        require(dexCount > 0) { "No classes*.dex entry was found; no checksum artifact was created." }
        return output.toByteArray()
    }

    private fun countDexEntries(bytes: ByteArray): Int = ZipInputStream(bytes.inputStream()).use { input -> generateSequence { input.nextEntry }.count { it.name.substringAfterLast('/').matches(Regex("classes(\\d*)\\.dex")) } }
    private fun recomputeDex(original: ByteArray): ByteArray {
        require(original.size >= 32 && original.copyOfRange(0, 4).contentEquals(byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte()))) { "Invalid DEX input." }
        val output = original.copyOf(); val signature = MessageDigest.getInstance("SHA-1").digest(output.copyOfRange(32, output.size)); signature.copyInto(output, 12)
        val adler = Adler32(); adler.update(output, 12, output.size - 12); writeLe32(output, 8, adler.value.toInt()); return output
    }
    private fun writeLe32(bytes: ByteArray, offset: Int, value: Int) { bytes[offset] = value.toByte(); bytes[offset + 1] = (value ushr 8).toByte(); bytes[offset + 2] = (value ushr 16).toByte(); bytes[offset + 3] = (value ushr 24).toByte() }

    private fun shortId(): String = UUID.randomUUID().toString().replace("-", "").take(10)
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun projectRoot(project: ProjectRecord): Path = projects.projectRoot(project.projectId)
    private fun workDirectory(project: ProjectRecord, name: String): Path {
        val logs = projectRoot(project).resolve("logs")
        try { Files.createDirectory(logs) } catch (_: java.nio.file.FileAlreadyExistsException) { }
        require(Files.isDirectory(logs, LinkOption.NOFOLLOW_LINKS)) { "Converter logs must not traverse symbolic links." }
        return Files.createDirectory(logs.resolve("android-$name-${shortId()}"))
    }
    private fun commandUnavailable(executable: String, args: List<String>, message: String) = AndroidToolCommandOutput(false, executable, args, "", message, null, 0, false)

    private fun validationFailure(executable: String, message: String) = AndroidToolCommandOutput(false, executable, emptyList(), "", message, null, 0, false)

    private data class SmaliVersionProbe(val valid: Boolean, val version: String?, val command: AndroidToolCommandOutput, val message: String)

    private suspend fun probeSmaliToolVersion(project: ProjectRecord, executable: String, expectedTool: String, pinnedVersion: String): SmaliVersionProbe {
        val work = workDirectory(project, "smali-version")
        val command = try {
            runner.run(executable, listOf("--version"), work)
        } catch (error: IOException) {
            return SmaliVersionProbe(false, null, validationFailure(executable, error.message.orEmpty()), "Unable to verify $expectedTool version: ${error.message}")
        } catch (error: IllegalArgumentException) {
            return SmaliVersionProbe(false, null, validationFailure(executable, error.message.orEmpty()), "Unable to verify $expectedTool version: ${error.message}")
        } finally {
            try {
                Files.deleteIfExists(work)
            } catch (_: IOException) {
                // Probe workspace cleanup is best-effort.
            }
        }
        val output = "${command.stdout}\n${command.stderr}".trim()
        val version = Regex("""(?m)^\s*${Regex.escape(expectedTool)}\s+([^\s]+)""").find(output)?.groupValues?.get(1)
        return when {
            !command.completed -> SmaliVersionProbe(false, version, command, "Unable to verify $expectedTool version: ${command.stderr.ifBlank { command.stdout }.ifBlank { "version probe failed." }}")
            version == null -> SmaliVersionProbe(false, null, command, "The $expectedTool version probe did not report a recognized tool identity.")
            version != pinnedVersion -> SmaliVersionProbe(false, version, command, "Unsupported $expectedTool version $version; expected $pinnedVersion.")
            else -> SmaliVersionProbe(true, version, command, "")
        }
    }

    private data class SmaliTreeSnapshot(val files: List<Path>, val smaliFiles: List<Path>, val totalBytes: Long)

    private data class SmaliOutputValidation(val valid: Boolean, val files: List<Path>, val allFiles: List<Path>, val message: String) {
        companion object {
            fun failed(message: String) = SmaliOutputValidation(false, emptyList(), emptyList(), message)
        }
    }

    private fun validateSmaliOutput(directory: Path): SmaliOutputValidation {
        return validateSmaliTree(directory, "External converter")
    }

    private fun validateSmaliInput(input: Path): SmaliOutputValidation {
        if (!Files.exists(input)) return SmaliOutputValidation.failed("Smali input path does not exist.")
        return validateSmaliTree(input, "Smali input")
    }

    private fun validateSmaliTree(root: Path, label: String): SmaliOutputValidation {
        val snapshot = try {
            snapshotSmaliTree(root)
        } catch (error: IOException) {
            return SmaliOutputValidation.failed("$label tree could not be read: ${error.message}")
        } catch (error: IllegalArgumentException) {
            return SmaliOutputValidation.failed(error.message ?: "$label tree is invalid.")
        }
        if (snapshot.smaliFiles.isEmpty()) return SmaliOutputValidation.failed("$label contains no .smali files.")
        if (snapshot.totalBytes > MAX_SMALI_TREE_BYTES) return SmaliOutputValidation.failed("$label exceeds the ${MAX_SMALI_TREE_BYTES / 1024 / 1024} MiB file budget.")
        snapshot.smaliFiles.forEach { file ->
            val bytes = try {
                readBoundedSmaliFile(file)
            } catch (error: IOException) {
                return SmaliOutputValidation.failed("$label file could not be read: ${file.fileName}.")
            } catch (error: IllegalArgumentException) {
                return SmaliOutputValidation.failed(error.message ?: "$label file is invalid: ${file.fileName}.")
            }
            if (!SMALI_CLASS_DIRECTIVE.containsMatchIn(bytes.toString(StandardCharsets.UTF_8))) {
                return SmaliOutputValidation.failed("$label file lacks a class directive: ${file.fileName}.")
            }
        }
        return SmaliOutputValidation(true, snapshot.smaliFiles, snapshot.files, "")
    }

    private fun snapshotSmaliTree(root: Path): SmaliTreeSnapshot {
        if (Files.isSymbolicLink(root)) throw IllegalArgumentException("Smali path must not be a symbolic link.")
        if (!Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw IllegalArgumentException("Smali path is not a regular file or directory.")
        val files = mutableListOf<Path>()
        val smaliFiles = mutableListOf<Path>()
        var totalBytes = 0L
        var walkEntries = 0
        Files.walk(root).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (++walkEntries > MAX_SMALI_WALK_ENTRIES) throw IllegalArgumentException("Smali tree exceeds the $MAX_SMALI_WALK_ENTRIES walk entry limit.")
                if (Files.isSymbolicLink(path)) throw IllegalArgumentException("Smali tree contains a symbolic link: ${root.relativize(path)}.")
                if (!Files.isRegularFile(path)) continue
                if (files.size >= MAX_SMALI_FILE_COUNT) throw IllegalArgumentException("Smali tree exceeds the $MAX_SMALI_FILE_COUNT file limit.")
                files.add(path)
                val size = Files.size(path)
                if (size > MAX_SMALI_FILE_BYTES) throw IllegalArgumentException("Smali file exceeds the ${MAX_SMALI_FILE_BYTES / 1024} KiB limit: ${path.fileName}.")
                totalBytes += size
                if (totalBytes > MAX_SMALI_TREE_BYTES) throw IllegalArgumentException("Smali tree exceeds the ${MAX_SMALI_TREE_BYTES / 1024 / 1024} MiB file budget.")
                if (path.fileName.toString().endsWith(".smali", true)) smaliFiles.add(path)
            }
        }
        files.sort()
        smaliFiles.sort()
        return SmaliTreeSnapshot(files, smaliFiles, totalBytes)
    }

    private fun readBoundedSmaliFile(file: Path): ByteArray {
        val bytes = Files.newInputStream(file).use { input -> input.readNBytes(MAX_SMALI_FILE_BYTES + 1) }
        require(bytes.size <= MAX_SMALI_FILE_BYTES) { "Smali file exceeds the ${MAX_SMALI_FILE_BYTES / 1024} KiB limit: ${file.fileName}." }
        return bytes
    }

    private fun validateAssembledDex(project: ProjectRecord, output: Path, bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 112 || !bytes.copyOfRange(0, 4).contentEquals(byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte()))) return null
        val snapshot = ProjectRecord(
            projectId = project.projectId,
            fileName = output.fileName.toString(),
            createdAt = project.createdAt,
            archivePath = output.toAbsolutePath().toString(),
            sha256 = sha256(bytes),
        )
        return try {
            val model = DexArchiveReader().read(snapshot)
            val classCount = model.files.sumOf { it.classes.size }
            val methodCount = model.files.sumOf { file -> file.methods.size }
            if (classCount == 0) null else classCount to methodCount
        } catch (error: IOException) {
            null
        } catch (error: IllegalArgumentException) {
            null
        }
    }

    private fun smaliTreeDigest(files: List<Path>, root: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { file ->
            digest.update(root.relativize(file).toString().replace('\\', '/').toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun sha256File(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
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

    companion object {
        private const val MAX_ISSUES = 200
        private const val MAX_FILE_BYTES = 256 * 1024 * 1024
        private const val SMALI_TOOL_VERSION = "3.0.10"
        private const val MAX_SMALI_FILE_COUNT = 20_000
        private const val MAX_SMALI_WALK_ENTRIES = 50_000
        private const val MAX_SMALI_FILE_BYTES = 4 * 1024 * 1024
        private const val MAX_SMALI_TREE_BYTES = 128L * 1024 * 1024
        private const val MAX_SMALI_TOTAL_FILES = 40_000
        private const val MAX_SMALI_AGGREGATE_BYTES = 256L * 1024 * 1024
        private const val MAX_JASMIN_FILE_COUNT = 20_000
        private const val MAX_JASMIN_WALK_ENTRIES = 50_000
        private const val MAX_JASMIN_FILE_BYTES = 4 * 1024 * 1024
        private const val MAX_JASMIN_TREE_BYTES = 128L * 1024 * 1024
        private const val MAX_JASMIN_CLASS_COUNT = 20_000
        private const val MAX_JASMIN_CLASS_BYTES = 8 * 1024 * 1024
        private const val CLASSIC_JAVA_MAJOR = 52
        private val JASMIN_ERROR_MARKERS = listOf("Jasmin: Found", ": Found ", "JAS Error", "Exception", "file not found")
        private val SMALI_CLASS_DIRECTIVE = Regex("""(?m)^\s*\.class\s+""")
    }
}
